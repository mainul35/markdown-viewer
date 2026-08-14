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

            # Hosts this app may send document content to. A request to anything not on
            # this list is refused before it is built. Add to it deliberately: the
            # documents this tool is used on are not all public.
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
        return hosts;
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
