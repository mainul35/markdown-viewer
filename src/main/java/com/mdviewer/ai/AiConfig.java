package com.mdviewer.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the assistant may talk to, and as whom.
 *
 * <p>Read from {@code ~/.mdviewer/ai.properties}, which is written once with defaults if
 * it is not there. API keys are never written by this app - a key is either put in that
 * file by hand or supplied through the environment with {@code ${env:NAME}}. Nothing here
 * prompts for a key, stores one it was not given, or prints one.
 */
public final class AiConfig {

    /** Substituted from the environment at read time, so a key need not sit in a file. */
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z0-9_]+)}");

    private static final String DEFAULTS = """
            # MDViewer assistant configuration.
            #
            # API keys: put one here, or leave the ${env:NAME} form and set that
            # environment variable instead. This file is never written by the app after it
            # is created, so anything you put here stays put.

            provider.default    = litellm

            litellm.baseUrl     = https://litellm.mainul35.dev/v1
            litellm.model       = qwen3-coder:30b
            litellm.apiKey      = ${env:LITELLM_API_KEY}

            openwebui.baseUrl   = https://ai.mainul35.dev/api
            openwebui.model     = qwen3-coder:30b
            openwebui.apiKey    = ${env:OPENWEBUI_API_KEY}

            # ----------------------------------------------------------------- others
            #
            # Every provider below speaks the OpenAI chat-completions shape, which is what
            # this app sends. They are listed ready to use; none of them can be reached
            # until you both set its key and add its host to allowedHosts, which is two
            # deliberate acts rather than one accident.
            #
            # Anthropic is not here. Its API is /v1/messages with a different request and
            # response shape and an x-api-key header, so it is not a base URL away - see
            # the note under allowedHosts. Bedrock is not here either: it authenticates
            # with AWS SigV4 request signing, which is a different problem again.

            openai.baseUrl      = https://api.openai.com/v1
            openai.model        = gpt-4o
            openai.apiKey       = ${env:OPENAI_API_KEY}

            # Ollama, on this machine. No key, and nothing leaves the machine.
            ollama.baseUrl      = http://localhost:11434/v1
            ollama.model        = qwen3-coder:30b
            ollama.apiKey       =

            ollamacloud.baseUrl = https://ollama.com/v1
            ollamacloud.model   = qwen3-coder:480b-cloud
            ollamacloud.apiKey  = ${env:OLLAMA_API_KEY}

            groq.baseUrl        = https://api.groq.com/openai/v1
            groq.model          = llama-3.3-70b-versatile
            groq.apiKey         = ${env:GROQ_API_KEY}

            openrouter.baseUrl  = https://openrouter.ai/api/v1
            openrouter.model    = anthropic/claude-sonnet-4
            openrouter.apiKey   = ${env:OPENROUTER_API_KEY}

            mistral.baseUrl     = https://api.mistral.ai/v1
            mistral.model       = mistral-large-latest
            mistral.apiKey      = ${env:MISTRAL_API_KEY}

            deepseek.baseUrl    = https://api.deepseek.com/v1
            deepseek.model      = deepseek-chat
            deepseek.apiKey     = ${env:DEEPSEEK_API_KEY}

            # Hosts this app may send document content to. A request to anything not on
            # this list is refused before it is built. Add to it deliberately: the
            # documents this tool is used on are not all public, and a provider being
            # configured above is not the same as having agreed to send this work to it.
            #
            # To use one of the others, add its host here. For example:
            #   allowedHosts = localhost, 127.0.0.1, litellm.mainul35.dev, api.openai.com
            allowedHosts        = localhost, 127.0.0.1, litellm.mainul35.dev, ai.mainul35.dev

            # How much of a codebase to send.
            #
            # The ceiling is the model's context window, not this file. Sending more than
            # the window holds does not get you more: the endpoint truncates or refuses,
            # and which of the two happens is not something this app can see.
            #
            # Work it out from the window, at roughly 4 characters per token:
            #
            #   window                            32768 tokens
            #   - the reply                       -3000
            #   - these instructions               -500
            #   - the open document               -3000   (about 2000 words)
            #   = left for sources                26000 tokens  ~=  100000 characters
            #
            # 90000 leaves a margin for a longer document. Raise it only after raising
            # OLLAMA_CONTEXT_LENGTH on the server, which costs GPU memory for the KV
            # cache; lower it if replies start coming back truncated.
            # The ceiling on a whole request - sources, the open document, the earlier
            # turns and the instructions together. context.totalChars bounds only the
            # sources; without this, a 40000-character document on top of them already
            # overruns a 32768-token window, and an endpoint does not refuse an oversized
            # request. It truncates from the front, taking the instructions first, so the
            # model keeps the files and forgets what it was asked to do with them.
            #
            #   32768 tokens x 4 characters   = 131000 characters
            #   - room for the reply          -  11000
            #   = one request                 = 120000
            #
            # Oldest turns are dropped first when this is reached, and the panel says so.
            context.windowChars  = 120000

            context.totalChars   = 90000
            context.perFileChars = 40000
            context.maxFiles     = 80

            # "Scan whole project" reads every file instead, in as many requests as it
            # takes, and answers from the findings. It exists because a real project does
            # not fit in a window at any budget: vsd-auth-server is about a million
            # characters, some 250000 tokens against a window of 32768.
            #
            # Each pass carries context.totalChars of file text, so the count follows from
            # the size of the project: a million characters at 90000 is twelve passes, and
            # twelve round trips to a local 30B model is minutes rather than seconds.
            # Raising context.totalChars makes a scan shorter as well as an ordinary
            # question richer.
            #
            # One file may not fill a pass by itself. Generated HTML templates here run to
            # 48000 characters and would otherwise become a pass of their own.
            scan.maxFileChars    = 30000

            # Markup is read too, but a shorter way in. Templates and stylesheets are
            # presentation: verbose, repetitive, and rarely what a question is about. In
            # one Spring project here the 28 Thymeleaf templates are 486000 of 1006000
            # characters - half the scan, and half the time it takes - to answer questions
            # about authentication. Their headings, forms and field names are all in the
            # first few thousand characters.
            #
            # Set this equal to scan.maxFileChars if you want them read in full.
            scan.markupChars     = 8000

            # Every pass also carries a map of the whole project - one line per file, the
            # names it declares - so a pass can say where something it is looking at is
            # defined even though it was given a different part of the project. That map
            # is what connects one pass to another; without it a scan is a set of unrelated
            # readings.
            #
            # It costs about 100 characters per file and rides in every pass, so it is the
            # first thing a growing project outgrows: at a sixth of a 90000-character pass
            # that is roughly 150 files. Past that it drops detail, then drops to bare
            # filenames, and the panel says so under Sources when it does.
            #
            # 0 means a sixth of context.totalChars. Set a number to give it more.
            scan.mapChars        = 0
            """;

    /** One configured endpoint. {@code apiKey} may be blank; that is not this class's business. */
    public record Endpoint(String name, String baseUrl, String model, String apiKey) {

        public String host() {
            try {
                return URI.create(baseUrl).getHost();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final Properties properties = new Properties();
    /**
     * Keys entered in the app this session, by provider name.
     *
     * <p>Held in memory and consulted before the file or the environment. Typing a key
     * into the window should not write it anywhere by itself - that is a separate,
     * deliberate act - so by default it lives exactly as long as the app does.
     */
    private final java.util.Map<String, String> runtimeKeys = new java.util.HashMap<>();

    /** Hosts allowed by answering the dialog, for this run only. */
    private final Set<String> sessionHosts = new LinkedHashSet<>();
    private final Path file;

    public AiConfig() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "ai.properties"));
    }

    public AiConfig(Path file) {
        this.file = file;
        load();
    }

    public Path getFile() {
        return file;
    }

    /** Endpoint names in the file, in the order they appear. */
    public List<String> providerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0 && key.endsWith(".baseUrl")) {
                names.add(key.substring(0, dot));
            }
        }
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(String::compareTo);
        return ordered;
    }

    /**
     * The providers the assistant should offer, which is not all of them.
     *
     * <p>Nine are configured so that using one is a matter of allowing it rather than
     * looking up a base URL, but a picker with nine entries when two of them work is a
     * list of things that will refuse you. This is the list someone has actually set up.
     *
     * <p>Unset, it means "the ones that could work now": a provider whose host is already
     * allowed. That keeps an existing install showing exactly what it showed before, and a
     * new one showing the two it was shipped with, without anybody choosing anything.
     */
    public List<String> enabledProviderNames() {
        String configured = value("providers.enabled");
        List<String> all = providerNames();
        if (configured.isBlank()) {
            List<String> usable = new ArrayList<>();
            for (String name : all) {
                if (isAllowed(endpoint(name).baseUrl())) {
                    usable.add(name);
                }
            }
            // Never nothing: an empty picker offers no way to reach the settings that
            // would fill it, which is a corner nobody should be able to paint into.
            return usable.isEmpty() ? all : usable;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : configured.split(",")) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty() && all.contains(trimmed)) {
                wanted.add(trimmed);
            }
        }
        return wanted.isEmpty() ? all : new ArrayList<>(wanted);
    }

    /**
     * Writes a provider's endpoint, creating it if the file has never mentioned it.
     *
     * <p>Base URL and model only. The key goes through {@link #saveKey} or
     * {@link #setRuntimeKey}, which is the difference that matters: those two decide
     * whether a secret reaches the disk, and folding them in here would make that a side
     * effect of editing an address.
     */
    public boolean saveEndpoint(String provider, String baseUrl, String model) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        boolean ok = saveProperty(provider + ".baseUrl", baseUrl == null ? "" : baseUrl.strip());
        ok &= saveProperty(provider + ".model", model == null ? "" : model.strip());
        return ok;
    }

    /** Records which providers the assistant offers. Written to the file, like a setting. */
    public boolean saveEnabledProviders(List<String> names) {
        String joined = names == null ? "" : String.join(", ", names);
        return saveProperty("providers.enabled", joined);
    }

    /**
     * Replaces one property's line in the config file, leaving every other line alone.
     *
     * <p>Shared by the key, the host list and the provider list: all three rewrite a
     * single line so the comments explaining the file survive being edited by the app.
     */
    private boolean saveProperty(String property, String newValue) {
        try {
            List<String> lines = Files.exists(file)
                    ? new ArrayList<>(Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith(property)) {
                    lines.set(i, property + " = " + newValue);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(property + " = " + newValue);
            }
            Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            properties.setProperty(property, newValue);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: could not update " + property + " in " + file);
            return false;
        }
    }

    public String defaultProvider() {
        String configured = value("provider.default");
        if (!configured.isBlank() && !endpoint(configured).baseUrl().isBlank()) {
            return configured;
        }
        List<String> names = providerNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    public Endpoint endpoint(String name) {
        String key = runtimeKeys.getOrDefault(name, value(name + ".apiKey"));
        return new Endpoint(name,
                value(name + ".baseUrl"),
                value(name + ".model"),
                key);
    }

    /** Uses {@code key} for this provider for the rest of the session. Writes nothing. */
    public void setRuntimeKey(String provider, String key) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        if (key == null || key.isBlank()) {
            runtimeKeys.remove(provider);
        } else {
            runtimeKeys.put(provider, key.strip());
        }
    }

    public boolean hasKey(String provider) {
        return !endpoint(provider).apiKey().isBlank();
    }

    /**
     * Writes a key into the config file, replacing that provider's line.
     *
     * <p>Only ever called because someone ticked the box asking for it. Rewrites the one
     * line and leaves every other line - including the comments explaining the file -
     * exactly as it was, so this cannot quietly reformat or drop anything.
     *
     * @return true if the file was updated
     */
    public boolean saveKey(String provider, String key) {
        if (provider == null || provider.isBlank() || key == null || key.isBlank()) {
            return false;
        }
        String property = provider + ".apiKey";
        try {
            List<String> lines = Files.exists(file)
                    ? new ArrayList<>(Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith(property)) {
                    lines.set(i, property + " = " + key.strip());
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(property + " = " + key.strip());
            }
            Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            restrictToOwner();
            properties.setProperty(property, key.strip());
            return true;
        } catch (IOException | RuntimeException e) {
            // Deliberately does not include the exception's message in anything shown to
            // the user, in case a path or value carrying the key ends up in it.
            System.err.println("MDViewer: could not update the key in " + file);
            return false;
        }
    }

    /**
     * Hosts the app is permitted to send document content to.
     *
     * <p>An allowlist rather than a warning. A warning is something you click through on
     * the way to somewhere else; the point of this list is that a mistyped base URL cannot
     * quietly ship a private document to a stranger, and a refusal is the only form of
     * that guarantee.
     */
    public Set<String> allowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        for (String host : value("allowedHosts").split(",")) {
            String trimmed = host.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        hosts.addAll(sessionHosts);
        return hosts;
    }

    /**
     * Allows {@code host} until the app is closed. Writes nothing.
     *
     * <p>The list exists so that a mistyped base URL cannot quietly ship a private
     * document to a stranger. That guarantee survives this: the only way in here is a
     * dialog naming the host and saying what allowing it means, which is the deliberate
     * act the list is asking for. What it replaces is a refusal with no way forward except
     * editing a file by hand - which taught people to keep the file permissive rather than
     * to think about each host.
     */
    public void allowHostForSession(String host) {
        String cleaned = hostOf(host);
        if (!cleaned.isEmpty()) {
            sessionHosts.add(cleaned);
        }
    }

    /**
     * Adds {@code host} to allowedHosts in the config file.
     *
     * <p>Rewrites the one line, like {@link #saveKey}, so the comments explaining the file
     * survive. Only ever called because someone asked for it to be remembered.
     *
     * @return true if the file was updated
     */
    public boolean saveAllowedHost(String host) {
        String cleaned = hostOf(host);
        if (cleaned.isEmpty()) {
            return false;
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String existing : value("allowedHosts").split(",")) {
            String trimmed = existing.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        if (!hosts.add(cleaned)) {
            return true; // Already there; nothing to write.
        }
        String line = "allowedHosts        = " + String.join(", ", hosts);
        try {
            List<String> lines = Files.exists(file)
                    ? new ArrayList<>(Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith("allowedHosts")) {
                    lines.set(i, line);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(line);
            }
            Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            properties.setProperty("allowedHosts", String.join(", ", hosts));
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: could not update allowedHosts in " + file + " - " + e);
            return false;
        }
    }

    /** The host part of a URL, or the text itself if it is already a bare host. */
    public static String hostOf(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) {
            return "";
        }
        String text = urlOrHost.strip();
        try {
            String host = URI.create(text).getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException e) {
            // Not a URL; treated as a bare host below.
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /** True if {@code baseUrl}'s host is on the allowlist. Anything unparseable is refused. */
    public boolean isAllowed(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(baseUrl.strip()).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        return host != null && allowedHosts().contains(host.toLowerCase(Locale.ROOT));
    }

    private String value(String key) {
        String raw = properties.getProperty(key, "").strip();
        Matcher m = ENV_REFERENCE.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        String fromEnv = System.getenv(m.group(1));
        return fromEnv == null ? "" : fromEnv.strip();
    }

    private void load() {
        /* Defaults first, then the file on top of them. The file used to be the whole
           truth, which meant a provider added to this app never reached anyone who had
           already run it once - their ai.properties was written before the entry existed
           and the app promises not to rewrite it. As a base layer, new providers simply
           appear, and every key the file does set still wins.

           allowedHosts is the one that matters: a file that lists two hosts keeps listing
           exactly those two. Being able to pick a provider is not permission to send
           anything to it. */
        loadDefaultsInMemory();
        try {
            if (!Files.exists(file)) {
                writeDefaults();
            }
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable config leaves the assistant unconfigured, which the panel
            // reports. It is never a reason to fail startup.
            System.err.println("MDViewer: could not read " + file + " - " + e);
            loadDefaultsInMemory();
        }
    }

    private void writeDefaults() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(DEFAULTS.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        restrictToOwner();
    }

    /**
     * Best-effort owner-only permissions.
     *
     * <p>The file holds an API key whenever someone chooses to put one in it rather than
     * use the environment form, so it should not be world-readable on a system that has an
     * opinion about that. Windows has no POSIX permissions and this is a no-op there.
     */
    private void restrictToOwner() {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | RuntimeException e) {
            // UnsupportedOperationException is a RuntimeException, so it is covered here.
            // Not worth failing over; the file is in the user's own home directory.
        }
    }

    private void loadDefaultsInMemory() {
        try {
            properties.load(new java.io.StringReader(DEFAULTS));
        } catch (IOException e) {
            // DEFAULTS is a compile-time constant; this cannot happen.
        }
    }

    public int intValue(String key, int defaultValue) {
        String raw = value(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Exposed so the harness can assert the shipped defaults without a home directory. */
    static List<String> defaultLines() {
        return Arrays.asList(DEFAULTS.split("\n"));
    }
}
