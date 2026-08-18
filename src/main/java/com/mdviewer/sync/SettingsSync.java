package com.mdviewer.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The desktop's settings, to and from the cloud, so a second machine is not a fresh setup.
 *
 * <p><b>Credentials never leave this machine.</b> The server strips anything that looks
 * like a secret before storing it, and this strips the same things before sending - not
 * because either is unreliable, but because a key that is never transmitted cannot be
 * intercepted, logged by a proxy, or found later in a request trace. Two independent
 * refusals, for the one thing here that cannot be taken back.
 *
 * <p>What travels is which providers exist, where they live, and which model is selected.
 * A provider arriving on a new machine is configured and unauthenticated and asks for its
 * key once - ten seconds per machine, in exchange for keys existing only where their owner
 * put them.
 */
public final class SettingsSync {

    /**
     * Property names that stay here. Matched case-insensitively as substrings, so
     * {@code apiKey}, {@code api_key} and {@code bearerToken} are caught by one entry.
     */
    private static final List<String> SECRET_MARKERS = List.of(
            "key", "secret", "token", "password", "credential", "authorization", "bearer");

    private final Path file;

    public SettingsSync() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "ai.properties"));
    }

    SettingsSync(Path file) {
        this.file = file;
    }

    /** What is being sent, and what was held back. */
    public record Outgoing(String json, List<String> withheld) { }

    /**
     * This machine's settings as JSON, with every credential removed.
     *
     * <p>Sorted, so two machines holding the same configuration produce identical
     * documents. Otherwise every sync looks like a change and the reader is told their
     * settings differ when nothing has.
     */
    public Outgoing outgoing() throws IOException {
        Map<String, String> properties = read();
        Map<String, String> safe = new TreeMap<>();
        List<String> withheld = new ArrayList<>();

        properties.forEach((key, value) -> {
            if (isSecret(key)) {
                // Recorded by name only - the name is not the secret, and naming what
                // stayed behind makes the omission a decision rather than a surprise on
                // the other machine.
                if (value != null && !value.isBlank()) {
                    withheld.add(key);
                }
            } else {
                safe.put(key, value == null ? "" : value);
            }
        });

        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(CloudClient.quote(entry.getKey())).append(':')
                    .append(CloudClient.quote(entry.getValue()));
        }
        return new Outgoing(json.append('}').toString(), List.copyOf(withheld));
    }

    /** What applying a downloaded document changed here. */
    public record Incoming(int changed, int added, List<String> refused) { }

    /**
     * Merges settings from the cloud into this machine's file.
     *
     * <p>A merge, never a replacement, and that difference is the design: this machine's
     * keys live in that file, so writing the cloud's copy over it would delete every one
     * of them - a sync that signs you out of every provider you had configured.
     *
     * <p>Anything secret in the incoming document is refused. The server strips those, so
     * one arriving means something upstream is wrong; refusing here costs nothing and
     * closes the case where it is.
     */
    public Incoming apply(String json) throws IOException {
        Map<String, String> incoming = parse(json);
        Map<String, String> current = read();
        Map<String, String> merged = new LinkedHashMap<>(current);
        List<String> refused = new ArrayList<>();
        int changed = 0;
        int added = 0;

        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (isSecret(entry.getKey())) {
                refused.add(entry.getKey());
                continue;
            }
            String existing = current.get(entry.getKey());
            if (existing == null) {
                added++;
            } else if (!existing.equals(entry.getValue())) {
                changed++;
            } else {
                continue;
            }
            merged.put(entry.getKey(), entry.getValue());
        }

        if (changed > 0 || added > 0) {
            write(merged);
        }
        return new Incoming(changed, added, List.copyOf(refused));
    }

    static boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(lower::contains);
    }

    // ------------------------------------------------------------------ file

    /*
     * Handled as lines rather than through java.util.Properties, so comments and ordering
     * survive. ai.properties is written with an explanation above each provider and is
     * meant to be read and edited by hand; Properties.store() would hand it back stripped
     * of every one of those comments.
     */
    private Map<String, String> read() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0) {
                out.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
        }
        return out;
    }

    private void write(Map<String, String> merged) throws IOException {
        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();

        // Existing keys are rewritten where they stand, so comments and grouping stay
        // where their author put them.
        Map<String, String> remaining = new LinkedHashMap<>(merged);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).trim();
            if (remaining.containsKey(key)) {
                lines.set(i, key + " = " + remaining.remove(key));
            }
        }
        if (!remaining.isEmpty()) {
            lines.add("");
            lines.add("# Arrived from cloud sync.");
            remaining.forEach((key, value) -> lines.add(key + " = " + value));
        }

        // Beside and moved into place: an interrupted write must not leave a half-truncated
        // settings file, which would lose provider configuration and read as corruption.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Flat string-to-string JSON, which is all this document ever is. */
    static Map<String, String> parse(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        int at = 0;
        while (at < json.length()) {
            int keyStart = json.indexOf('"', at);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = closingQuote(json, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) {
                break;
            }
            int valueStart = json.indexOf('"', colon);
            if (valueStart < 0) {
                break;
            }
            int valueEnd = closingQuote(json, valueStart + 1);
            if (valueEnd < 0) {
                break;
            }
            out.put(unescape(json.substring(keyStart + 1, keyEnd)),
                    unescape(json.substring(valueStart + 1, valueEnd)));
            at = valueEnd + 1;
        }
        return out;
    }

    private static int closingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            if (json.charAt(i) == '\\') {
                i++;
            } else if (json.charAt(i) == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
