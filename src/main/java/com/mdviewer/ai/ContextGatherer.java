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

    /**
     * Total characters of gathered context. Roughly 30k tokens, which leaves room for the
     * document and the reply in a 64k window - the size the local models here run at.
     */
    private static final int TOTAL_BUDGET = 120_000;

    /** No single file may crowd out every other one. */
    private static final int PER_FILE_BUDGET = 24_000;

    private static final int MAX_FILES = 40;
    private static final int MAX_TREE_ENTRIES = 400;

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
        int[] budget = {TOTAL_BUDGET};

        for (Path path : absolutePathsIn(question)) {
            readPath(path, sources, skipped, budget);
        }
        if (workspaceRoot != null) {
            for (Path path : relativePathsIn(document, workspaceRoot)) {
                readPath(path, sources, skipped, budget);
            }
        }
        if (allowWeb) {
            for (String url : urlsIn(question)) {
                readUrl(url, sources, skipped, budget);
            }
        }
        return new Result(sources, skipped, TOTAL_BUDGET - budget[0]);
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
            // Trailing punctuation from prose - "look at C:\x\y." - is not part of the path.
            while (!candidate.isEmpty() && ".,;:".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (candidate.isEmpty() || !seen.add(candidate)) {
                continue;
            }
            try {
                Path path = Path.of(candidate);
                if (Files.exists(path)) {
                    paths.add(path.toAbsolutePath().normalize());
                }
            } catch (RuntimeException e) {
                // Not a usable path on this platform; nothing to read.
            }
        }
        return paths;
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

    private void readPath(Path path, List<Source> sources, List<String> skipped, int[] budget) {
        if (Files.isDirectory(path)) {
            readDirectory(path, sources, skipped, budget);
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
    private void readDirectory(Path root, List<Source> sources, List<String> skipped,
                               int[] budget) {
        List<Path> files = new ArrayList<>();
        StringBuilder tree = new StringBuilder();
        int[] counted = {0};
        walk(root, root, tree, files, counted);

        sources.add(new Source(root + "  (listing)", tree.toString(), tree.length()));
        budget[0] -= tree.length();

        int read = 0;
        for (Path file : files) {
            if (read >= MAX_FILES || budget[0] <= 0) {
                skipped.add((files.size() - read) + " more files under " + root.getFileName()
                        + " (budget reached)");
                break;
            }
            if (readFile(file, root.relativize(file).toString(), sources, skipped, budget)) {
                read++;
            }
        }
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
        if (!isText(file)) {
            skipped.add(label + " (not a text file)");
            return false;
        }
        try {
            long size = Files.size(file);
            if (size > PER_FILE_BUDGET * 4L) {
                skipped.add(label + " (" + (size / 1024) + " KB, too large)");
                return false;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > PER_FILE_BUDGET) {
                content = content.substring(0, PER_FILE_BUDGET) + "\n... (truncated)";
            }
            if (content.length() > budget[0]) {
                skipped.add(label + " (budget reached)");
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
            if (text.length() > PER_FILE_BUDGET) {
                text = text.substring(0, PER_FILE_BUDGET) + "\n... (truncated)";
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
