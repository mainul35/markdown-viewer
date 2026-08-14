package com.mdviewer.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to an OpenAI-compatible {@code /chat/completions} endpoint, streaming.
 *
 * <p>One client covers LiteLLM, Open WebUI, Ollama, LM Studio and vLLM alike: they differ
 * only in base URL, model name and auth header. That is the whole reason "OpenAI
 * compatible" is worth targeting rather than any one vendor's SDK.
 *
 * <p>No JSON dependency. The request has four fields and the response needs one, so a
 * hand-written writer and a targeted reader are smaller than the argument for taking a
 * library - the same call already made for the workspace history file. If either shape
 * grows past this, take a real parser rather than growing this one.
 */
public final class ChatProvider {

    /**
     * One turn of the conversation.
     *
     * <p>{@code images} are base64 PNG data, sent as OpenAI content parts. A model that
     * cannot see will simply ignore them or refuse - which is why the panel says which
     * model is selected next to the attachment.
     */
    public record Message(String role, String content, List<String> images) {

        public Message(String role, String content) {
            this(role, content, List.of());
        }
    }

    /** Raised before anything is sent when the endpoint is not one we are allowed to use. */
    public static final class NotAllowedException extends RuntimeException {
        public NotAllowedException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final AiConfig config;

    public ChatProvider(AiConfig config) {
        this.config = config;
    }

    /**
     * Streams a completion, calling {@code onToken} for each fragment as it arrives.
     *
     * <p>Runs on the calling thread and blocks, so callers must not be on the FX thread.
     * Fragments are handed over raw; marshalling them onto the FX thread is the caller's
     * job, because only the caller knows what it wants to do with them.
     *
     * @return the complete reply
     */
    public String stream(AiConfig.Endpoint endpoint, List<Message> messages,
                         Consumer<String> onToken) throws Exception {
        // Checked here rather than at the call site: this is the one place every request
        // passes through, and a guard anywhere else can be forgotten.
        if (!config.isAllowed(endpoint.baseUrl())) {
            throw new NotAllowedException(
                    "Refusing to send document content to " + endpoint.host()
                            + ". Add it to allowedHosts in " + config.getFile() + " first.");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(endpoint.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody(endpoint.model(), messages), StandardCharsets.UTF_8));
        if (!endpoint.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + endpoint.apiKey());
        }

        HttpResponse<java.io.InputStream> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            // The body carries the actual reason - a bad key, an unknown model - and is
            // far more use than the status alone.
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("The model endpoint returned "
                    + response.statusCode() + ": " + summarise(body));
        }

        StringBuilder whole = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String fragment = deltaOf(line);
                if (fragment == null) {
                    continue;
                }
                whole.append(fragment);
                onToken.accept(fragment);
            }
        }
        return whole.toString();
    }

    /**
     * Asks the endpoint for its model list, to prove the key and the host work.
     *
     * <p>Deliberately not a chat call: this sends **no document content at all**, so it is
     * safe to press before you have decided whether you trust the endpoint with anything.
     * It answers the two questions that actually go wrong - can I reach it, and does it
     * accept my key - and nothing else.
     *
     * @return a short description of what happened, for the panel to show
     */
    public String testConnection(AiConfig.Endpoint endpoint) {
        if (!config.isAllowed(endpoint.baseUrl())) {
            return "Refused: " + endpoint.host() + " is not in allowedHosts in "
                    + config.getFile() + ".";
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(endpoint.baseUrl()) + "/models"))
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            if (!endpoint.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + endpoint.apiKey());
            }
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> "Connected to " + endpoint.host() + ". "
                        + countModels(response.body()) + " models available.";
                case 401, 403 -> endpoint.host() + " rejected the key. "
                        + (endpoint.apiKey().isBlank()
                            ? "None is set - put one in the environment or in "
                                    + config.getFile() + "."
                            : "Check the key is current.");
                case 404 -> endpoint.host() + " has no /models here. Check the base URL.";
                default -> endpoint.host() + " returned " + response.statusCode()
                        + ": " + summarise(response.body());
            };
        } catch (Exception e) {
            return "Could not reach " + endpoint.host() + ": " + e.getMessage();
        }
    }

    /**
     * The model names the endpoint offers, sorted, or empty if it cannot be asked.
     *
     * <p>Same call as {@link #testConnection}: a GET for the catalogue, carrying no
     * document content, so choosing a model never sends anything anywhere. A proxy in
     * front of several backends is the normal case here, and typing the name of a model
     * by hand to find out it is spelt differently is a poor way to discover that.
     */
    public List<String> listModels(AiConfig.Endpoint endpoint) {
        if (endpoint == null || endpoint.baseUrl().isBlank()
                || !config.isAllowed(endpoint.baseUrl())) {
            return List.of();
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(endpoint.baseUrl()) + "/models"))
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            if (!endpoint.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + endpoint.apiKey());
            }
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            // "id" fields, which is where the OpenAI shape puts a model's name. Hand-read
            // rather than parsed: this class carries no JSON dependency, and the answer is
            // a flat list of strings.
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            Matcher ids = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
            while (ids.find()) {
                names.add(ids.group(1));
            }
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of(); // The panel keeps whatever is configured and says nothing.
        }
    }

    /** Counts {@code "id":} occurrences, which is one per model in the OpenAI shape. */
    private static int countModels(String body) {
        int count = 0;
        int at = 0;
        while ((at = body.indexOf("\"id\"", at)) >= 0) {
            count++;
            at += 4;
        }
        return count;
    }

    /**
     * The text carried by one SSE line, or null if the line carries none.
     *
     * <p>Package-private so the shape can be asserted against captured lines without a
     * server: streaming is exactly the kind of code that is otherwise only ever tested by
     * running it against something live.
     */
    static String deltaOf(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null; // Blank separators and SSE comments carry nothing.
        }
        String payload = line.substring("data:".length()).strip();
        if (payload.isEmpty() || payload.equals("[DONE]")) {
            return null;
        }
        int delta = payload.indexOf("\"delta\"");
        if (delta < 0) {
            return null; // A usage or role-only chunk.
        }
        int content = payload.indexOf("\"content\"", delta);
        if (content < 0) {
            return null;
        }
        int quote = payload.indexOf('"', content + "\"content\"".length() + 1);
        return quote < 0 ? null : readJsonString(payload, quote);
    }

    /** Reads a JSON string starting at the opening quote, honouring escapes. */
    private static String readJsonString(String text, int openingQuote) {
        StringBuilder out = new StringBuilder();
        for (int i = openingQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= text.length()) {
                break;
            }
            char escaped = text.charAt(i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                // Covers \" and \\ and anything else, which stand for themselves.
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    static String requestBody(String model, List<Message> messages) {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":").append(quote(model))
                .append(",\"stream\":true,\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"role\":").append(quote(message.role())).append(",\"content\":");
            if (message.images().isEmpty()) {
                json.append(quote(message.content()));
            } else {
                // The content-parts form, which is what every vision-capable
                // OpenAI-compatible endpoint expects.
                json.append("[{\"type\":\"text\",\"text\":")
                        .append(quote(message.content())).append('}');
                for (String image : message.images()) {
                    json.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                            .append(quote("data:image/png;base64," + image)).append("}}");
                }
                json.append(']');
            }
            json.append('}');
        }
        return json.append("]}").toString();
    }

    /** JSON string literal, escaped. Document text arrives here, so this must be right. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < (text == null ? 0 : text.length()); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Any other control character has to go out in escaped hex form or
                    // the payload is not valid JSON, and Markdown does pick these up
                    // from pasted text. (Written without the backslash-u spelling on
                    // purpose: javac decodes those escapes even inside comments.)
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String summarise(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
