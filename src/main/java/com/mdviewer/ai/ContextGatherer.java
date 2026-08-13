package com.mdviewer.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns paths and URLs mentioned in a question into text the model can actually read.
 *
 * <p>Without this the assistant can only answer from the document, which is what it said
 * when asked to analyse a codebase: naming a folder in a chat message does not put it in
 * front of the model. This reads what was named, within a budget, and reports exactly
 * what it read and what it left out.
 *
 * <p>Nothing here decides on its own what to explore. A path is followed because it
 * appears in the message you typed or in the document you have open - never because it
 * looked interesting.
 */
public final class ContextGatherer {

    /** One thing that was read, ready to be handed to the model. */
    public record Source(String label, String content, int chars) {}

    /** What was gathered, and what was deliberately not. */
    public record Result(List<Source> sources, List<String> skipped, int totalChars) {

        public boolean isEmpty() {
            return sources.isEmpty();
        }
    }

    private static final int MAX_TREE_ENTRIES = 800;

    /**
     * Budgets, from the config file so a bigger model can be given more.
     *
     * <p>Raising these past what the endpoint accepts does not help: the request is then
     * refused by the model instead of being trimmed here, which is a worse failure. The
     * panel reports what was skipped so the ceiling is visible rather than mysterious.
     */
    private final int totalBudget;
    private final int perFileBudget;
    private final int maxFiles;

    public ContextGatherer() {
        // Sized for a 32k-token window, which is what a local 30B model runs at
        // here. See ai.properties for the arithmetic.
        this(90_000, 40_000, 80);
    }

    public ContextGatherer(AiConfig config) {
        this(config.intValue("context.totalChars", 90_000),
                config.intValue("context.perFileChars", 40_000),
                config.intValue("context.maxFiles", 80));
    }

    public ContextGatherer(int totalBudget, int perFileBudget, int maxFiles) {
        this.totalBudget = Math.max(1_000, totalBudget);
        this.perFileBudget = Math.max(500, perFileBudget);
        this.maxFiles = Math.max(1, maxFiles);
    }

    /** Extensions worth sending. Anything else is named in the listing but not read. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "java", "kt", "js", "ts", "tsx", "jsx", "py", "rb",
            "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bat", "ps1", "sql", "xml",
            "json", "yaml", "yml", "properties", "toml", "ini", "cfg", "conf", "gradle",
            "html", "css", "scss", "fxml", "dockerfile", "jenkinsfile");

    /** Build output and dependency trees: enormous, and never the source of truth. */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            "node_modules", "target", "build", "dist", "out", ".git", ".idea", ".gradle",
            ".mvn", "venv", ".venv", "__pycache__", ".next", "vendor", "bin", "obj");

    /** An absolute Windows or POSIX path, optionally wrapped in backticks or quotes. */
    private static final Pattern PATH = Pattern.compile(
            "(?:`|\"|')?((?:[A-Za-z]:[\\\\/]|/)[^\\s`\"'<>|?*]+)(?:`|\"|')?");

    private static final Pattern URL = Pattern.compile("https?://[^\\s`\"'<>)\\]]+");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Reads everything named in {@code question}, plus relative paths in {@code document}
     * that resolve inside {@code workspaceRoot}.
     *
     * <p>An absolute path in the question is treated as consent for that path: you typed
     * it, in a message asking about it. A path found only in the document is followed just
     * within the workspace, because the document did not ask for anything and the
     * workspace is the boundary you set by opening it.
     */
    public Result gather(String question, String document, Path workspaceRoot,
                         boolean allowWeb) {
        List<Source> sources = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int[] budget = {totalBudget};

        for (Path path : absolutePathsIn(question)) {
            readPath(path, question, sources, skipped, budget);
        }
        if (workspaceRoot != null) {
            for (Path path : relativePathsIn(document, workspaceRoot)) {
                readPath(path, question, sources, skipped, budget);
            }
        }
        if (allowWeb) {
            for (String url : urlsIn(question)) {
                readUrl(url, sources, skipped, budget);
            }
        }
        return new Result(sources, skipped, totalBudget - budget[0]);
    }

    // ------------------------------------------------------------------ paths

    public static List<Path> absolutePathsIn(String text) {
        List<Path> paths = new ArrayList<>();
        if (text == null) {
            return paths;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = PATH.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            if (candidate.isEmpty() || !seen.add(candidate)) {
                continue;
            }
            Path path = resolveTrimmingPunctuation(candidate);
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * The path {@code candidate} names, shortening it past trailing punctuation until it
     * matches something real.
     *
     * <p>A path typed into a sentence collects the sentence's punctuation. Written as
     * "(source code here: C:\\...\\vsd-auth-server)" the closing bracket lands inside the
     * match, the folder does not exist under that name, and the whole request is silently
     * read as naming nothing - which looks from the outside exactly like an assistant
     * that cannot see the filesystem. Trying the longest form first means a directory
     * genuinely ending in a bracket still wins.
     */
    private static Path resolveTrimmingPunctuation(String candidate) {
        String text = candidate;
        while (!text.isEmpty()) {
            try {
                Path path = Path.of(text);
                if (Files.exists(path)) {
                    // toRealPath, not normalize: Windows silently ignores a trailing dot
                    // when opening a file, so "…\vsd-auth-server." exists and would be
                    // carried around under that name in every label. The real path is the
                    // name the filesystem actually has.
                    try {
                        return path.toRealPath();
                    } catch (IOException e) {
                        return path.toAbsolutePath().normalize();
                    }
                }
            } catch (RuntimeException e) {
                // Not a usable path on this platform; keep trimming, it may become one.
            }
            char last = text.charAt(text.length() - 1);
            if (".,;:!?)]}>\"'`".indexOf(last) < 0) {
                return null; // Not punctuation, so the name is simply wrong.
            }
            text = text.substring(0, text.length() - 1);
        }
        return null;
    }

    public static List<Path> relativePathsIn(String document, Path workspaceRoot) {
        List<Path> paths = new ArrayList<>();
        if (document == null || workspaceRoot == null) {
            return paths;
        }
        Set<String> seen = new LinkedHashSet<>();
        // Markdown links and inline code are where a document names its sources.
        Matcher m = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)|`([^`\\n]+)`").matcher(document);
        while (m.find()) {
            String candidate = m.group(1) != null ? m.group(1) : m.group(2);
            if (candidate == null || candidate.isBlank() || candidate.startsWith("http")
                    || !seen.add(candidate)) {
                continue;
            }
            try {
                Path resolved = workspaceRoot.resolve(candidate.strip()).normalize();
                // The workspace is the boundary. A document cannot walk out of it with
                // ../.. and have that followed.
                if (resolved.startsWith(workspaceRoot) && Files.isRegularFile(resolved)) {
                    paths.add(resolved);
                }
            } catch (RuntimeException e) {
                // Not a path - most inline code is not.
            }
        }
        return paths;
    }

    private void readPath(Path path, String question, List<Source> sources,
                          List<String> skipped, int[] budget) {
        if (Files.isDirectory(path)) {
            readDirectory(path, question, sources, skipped, budget);
        } else if (Files.isRegularFile(path)) {
            readFile(path, path.toString(), sources, skipped, budget);
        }
    }

    /**
     * A directory becomes a listing plus the text files inside it.
     *
     * <p>The listing goes first and is never truncated away, because "what is in this
     * project" is answerable from names alone and is the thing most often wanted. File
     * contents then fill whatever budget is left.
     */
    private void readDirectory(Path root, String question, List<Source> sources,
                               List<String> skipped, int[] budget) {
        List<Path> files = new ArrayList<>();
        StringBuilder tree = new StringBuilder();
        int[] counted = {0};
        walk(root, root, tree, files, counted);

        sources.add(new Source(root + "  (listing)", tree.toString(), tree.length()));
        budget[0] -= tree.length();

        // Ordered by how likely a file is to answer the question, because the budget
        // always runs out on a real project and what it is spent on decides whether the
        // answer is any good. Alphabetical order spends it on whatever starts with "a".
        // Keywords from the question with the pasted paths and URLs removed first. The
        // path is why we are here, and its words - "auth", "server", the user's own
        // account name out of the home directory - appear in every file underneath it.
        // Left in, they gave every file the same bonus and swamped the signal that is
        // supposed to put a README first.
        String keywords = keywordsOf(question);
        files.sort(java.util.Comparator.comparingInt(
                (Path f) -> -relevance(f, root, keywords)));

        /* No single file may take more than a share of the budget while a whole project
           is being read. A 40k README against a 90k budget left nothing for the source
           that was actually asked about - the reader got the project's own summary of
           itself and none of its code, which is the opposite of checking claims against
           sources. Reading the first slice of a long file is nearly as good and leaves
           room for thirty others. */
        int shareCap = Math.max(4_000, totalBudget / 6);

        int read = 0;
        for (Path file : files) {
            if (read >= maxFiles || budget[0] <= 0) {
                break;
            }
            int before = budget[0];
            if (readFile(file, root.relativize(file).toString(), sources, skipped,
                    budget, shareCap)) {
                read++;
            } else if (budget[0] == before && budget[0] < perFileBudget) {
                /* Nothing was taken and what is left could not hold a typical file.
                   Carrying on would try every remaining file and add a "budget reached"
                   line for each - which is how one exhausted budget produced a hundred
                   identical messages. Stop, and say it once below. */
                break;
            }
        }
        int unread = files.size() - read;
        if (unread > 0) {
            skipped.add(unread + " of " + files.size() + " files under "
                    + root.getFileName() + " were not read - the "
                    + (totalBudget / 1000) + "k character budget ran out. Raise "
                    + "context.totalChars in ai.properties, or ask about a subfolder.");
        }
    }

    /**
     * How likely this file is to answer the question. Higher is read sooner.
     *
     * <p>Documentation first, because it says what a project is for; then anything the
     * question actually named; then source over configuration. Crude, but the alternative
     * is alphabetical, which is no signal at all.
     */
    /**
     * The question with pasted paths and URLs removed, leaving only the words that say
     * what is actually wanted.
     */
    public static String keywordsOf(String question) {
        if (question == null) {
            return "";
        }
        return URL.matcher(PATH.matcher(question).replaceAll(" ")).replaceAll(" ")
                .toLowerCase(Locale.ROOT);
    }

    private static int relevance(Path file, Path root, String keywords) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String relative = root.relativize(file).toString().toLowerCase(Locale.ROOT);
        String asked = keywords == null ? "" : keywords;
        int score = 0;
        if (name.startsWith("readme")) {
            score += 100;
        }
        if (name.endsWith(".md")) {
            score += 40;
        }
        /* When the question is about the implementation, code outranks prose. Asked to
           "analyse the codebase" the reader previously got a README and three YAML files
           and no source at all - the project's own account of itself, which is exactly
           the thing that most needs checking against the code. */
        if (asksAboutCode(asked) && isSource(name)) {
            score += 110;
        }
        // A word from the question appearing in the path is the strongest signal there is.
        for (String word : asked.split("[^a-z0-9]+")) {
            if (word.length() >= 4 && relative.contains(word)) {
                score += 60;
            }
        }
        if (relative.contains("src") || relative.contains("main")) {
            score += 20;
        }
        if (name.endsWith(".json") || name.endsWith(".lock") || name.endsWith(".xml")) {
            score -= 20; // Manifests are long and say little about behaviour.
        }
        /* Shallow files describe the project; deeply nested ones describe a corner of it.
           Depth comes from the Path, not from splitting the string: "[\\/]" as a Java
           literal is the regex [\/], which matches a forward slash and nothing else, so
           on Windows every path counted as one segment and this penalty was a flat -3 for
           everything. That made a deeply nested source file tie with the README, and a
           stable sort then kept alphabetical order - which is exactly the bug this
           ordering was added to fix. */
        score -= root.relativize(file).getNameCount() * 3;
        return score;
    }

    /** Words that mean "look at the implementation", not "tell me what this project is". */
    private static final Set<String> CODE_WORDS = Set.of(
            "code", "codebase", "source", "implement", "implementation", "class", "classes",
            "method", "function", "api", "endpoint", "endpoints", "schema", "entity",
            "service", "config", "configuration", "logic", "supports", "support",
            "capability", "capabilities", "mechanism", "mechanisms", "architecture");

    private static boolean asksAboutCode(String keywords) {
        for (String word : keywords.split("[^a-z0-9]+")) {
            if (CODE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSource(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return Set.of("java", "kt", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs",
                "c", "h", "cpp", "hpp", "cs", "sql").contains(name.substring(dot + 1));
    }

    private void walk(Path root, Path dir, StringBuilder tree, List<Path> files, int[] counted) {
        if (counted[0] >= MAX_TREE_ENTRIES) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(entries::add);
        } catch (IOException | RuntimeException e) {
            return;
        }
        entries.sort((a, b) -> {
            boolean da = Files.isDirectory(a);
            boolean db = Files.isDirectory(b);
            return da == db ? a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString())
                    : (da ? -1 : 1);
        });
        for (Path entry : entries) {
            if (counted[0]++ >= MAX_TREE_ENTRIES) {
                tree.append("... (listing truncated)\n");
                return;
            }
            String name = entry.getFileName().toString();
            if (name.startsWith(".") && !name.equals(".github")) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                if (SKIP_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT))) {
                    tree.append(root.relativize(entry)).append("/   (skipped)\n");
                    continue;
                }
                tree.append(root.relativize(entry)).append("/\n");
                walk(root, entry, tree, files, counted);
            } else {
                tree.append(root.relativize(entry)).append('\n');
                if (isText(entry)) {
                    files.add(entry);
                }
            }
        }
    }

    private boolean readFile(Path file, String label, List<Source> sources,
                             List<String> skipped, int[] budget) {
        return readFile(file, label, sources, skipped, budget, perFileBudget);
    }

    private boolean readFile(Path file, String label, List<Source> sources,
                             List<String> skipped, int[] budget, int cap) {
        if (!isText(file)) {
            skipped.add(label + " (not a text file)");
            return false;
        }
        try {
            long size = Files.size(file);
            if (size > cap * 8L) {
                skipped.add(label + " (" + (size / 1024) + " KB, too large)");
                return false;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // cap, not perFileBudget: when a whole project is being read this is the
            // share any one file may take, and a 22k class taking a quarter of the
            // budget is how the other thirty files got left out.
            if (content.length() > cap) {
                content = content.substring(0, cap)
                        + "\n... (truncated here; " + (content.length() / 1000)
                        + "k characters in the full file)";
            }
            if (content.length() > budget[0]) {
                // Deliberately silent: the caller reports the budget once, for all the
                // files it covers. Reporting per file is how one exhausted budget turned
                // into a hundred identical lines.
                return false;
            }
            budget[0] -= content.length();
            sources.add(new Source(label, content, content.length()));
            return true;
        } catch (IOException | RuntimeException e) {
            // A file that cannot be decoded as UTF-8 is almost certainly binary.
            skipped.add(label + " (unreadable)");
            return false;
        }
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            // Extensionless files that are conventionally text.
            return name.equals("dockerfile") || name.equals("jenkinsfile")
                    || name.equals("makefile") || name.equals("readme");
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    // -------------------------------------------------------------------- web

    public static List<String> urlsIn(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        Matcher m = URL.matcher(text);
        while (m.find() && urls.size() < 5) {
            urls.add(m.group());
        }
        return urls;
    }

    private void readUrl(String url, List<Source> sources, List<String> skipped, int[] budget) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "MDViewer")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                skipped.add(url + " (HTTP " + response.statusCode() + ")");
                return;
            }
            String text = stripHtml(response.body());
            if (text.length() > perFileBudget) {
                text = text.substring(0, perFileBudget) + "\n... (truncated)";
            }
            if (text.length() > budget[0]) {
                skipped.add(url + " (budget reached)");
                return;
            }
            budget[0] -= text.length();
            sources.add(new Source(url, text, text.length()));
        } catch (Exception e) {
            skipped.add(url + " (could not fetch: " + e.getClass().getSimpleName() + ")");
        }
    }

    /** Crude but adequate: scripts and styles out, tags out, entities for the common few. */
    public static String stripHtml(String html) {
        String text = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +", "")
                .replaceAll("\n{3,}", "\n\n");
        return text.strip();
    }
}
