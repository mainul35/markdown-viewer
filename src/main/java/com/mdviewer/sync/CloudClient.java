package com.mdviewer.sync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cloud sync API, from the desktop.
 *
 * <p>Hand-rolled over {@link HttpClient} and a small JSON writer/reader, matching how the
 * assistant already talks to model providers - the desktop build has no JSON mapper and
 * this speaks to exactly one API whose shapes are known.
 *
 * <p>Every call names the host it is talking to in the exception it throws. This is the
 * only part of the desktop application that sends documents anywhere, and "which machine
 * received my architecture notes" is never a question someone should have to go and look
 * up.
 */
public final class CloudClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String base;
    private final Authorization authorization;
    private final DeviceIdentity device = new DeviceIdentity();

    /**
     * Where the bearer token comes from, and how to get a fresh one.
     *
     * <p>An interface rather than a token, because a token is a value that goes stale
     * during a long sync and this has to be able to ask for another halfway through
     * uploading forty documents.
     */
    public interface Authorization {

        /** A token good for the request about to be made. */
        String token() throws IOException;

        /** A new token after one was refused, or a failure saying the reader must sign in. */
        String renew() throws IOException;
    }

    /** What the server says about a path, mirrored from its plan response. */
    public record Change(String path, String action, String localHash, String remoteHash,
                         long bytes, String reason) { }

    public record Plan(long revision, List<Change> changes, List<String> blobsToUpload,
                       long bytesToUpload, boolean fitsInQuota, boolean needsAttention,
                       long usedBytes, long limitBytes, String tier) { }

    public CloudClient(String base, Authorization authorization) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.authorization = authorization;
    }

    public String host() {
        return URI.create(base).getHost();
    }

    // ---------------------------------------------------------------- requests

    /** One of the account's cloud workspaces, as a link target. */
    public record WorkspaceSummary(String id, String name, long revision, int fileCount) {

        @Override
        public String toString() {
            return name + "  (" + fileCount + (fileCount == 1 ? " document" : " documents") + ")";
        }
    }

    /**
     * Everything this account already has in the cloud.
     *
     * <p>Offered as the alternative to creating a new one. Joining an existing workspace is
     * a perfectly ordinary thing to want - the same folder on a second machine, or two
     * folders being deliberately consolidated - and it is only dangerous when it happens by
     * accident.
     */
    public List<WorkspaceSummary> workspaces() throws IOException {
        String response = send("GET", "/api/v1/workspaces", null, null);
        List<WorkspaceSummary> out = new ArrayList<>();
        for (String object : objects(stripArray(response))) {
            out.add(new WorkspaceSummary(string(object, "id"), string(object, "name"),
                    number(object, "revision"), (int) number(object, "fileCount")));
        }
        return out;
    }

    /**
     * Creates a workspace.
     *
     * @throws SyncException with {@code code} {@code workspace_name_taken} when the account
     *         already has one by that name. The server refuses rather than joining, because
     *         a name cannot tell "my folder on another machine" from "a different folder
     *         that happens to be called docs".
     */
    public String createWorkspace(String name) throws IOException {
        String body = "{\"name\":" + quote(name) + "}";
        String response = send("POST", "/api/v1/workspaces", body, "application/json");
        return string(response, "id");
    }

    public Plan plan(String workspaceId, long baseRevision, Map<String, String> agreedBase,
                     List<FileState> local) throws IOException {
        StringBuilder body = new StringBuilder("{\"baseRevision\":").append(baseRevision)
                .append(",\"base\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : agreedBase.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        body.append("},\"files\":").append(filesJson(local)).append('}');

        String response = send("POST", "/api/v1/workspaces/" + workspaceId + "/sync/plan",
                body.toString(), "application/json");

        List<Change> changes = new ArrayList<>();
        for (String object : objects(section(response, "changes"))) {
            changes.add(new Change(string(object, "path"), string(object, "action"),
                    string(object, "localHash"), string(object, "remoteHash"),
                    number(object, "bytes"), string(object, "reason")));
        }
        return new Plan(number(response, "revision"), changes,
                strings(section(response, "blobsToUpload")),
                number(response, "bytesToUpload"),
                bool(response, "fitsInQuota"), bool(response, "needsAttention"),
                number(response, "usedBytes"), number(response, "limitBytes"),
                string(response, "tier"));
    }

    public long commit(String workspaceId, long baseRevision, List<FileState> intended)
            throws IOException {
        String body = "{\"baseRevision\":" + baseRevision + ",\"files\":" + filesJson(intended) + "}";
        String response = send("POST", "/api/v1/workspaces/" + workspaceId + "/sync/commit",
                body, "application/json");
        return number(response, "revision");
    }

    public void putBlob(String sha256, byte[] content) throws IOException {
        HttpRequest request = base("/api/v1/blobs/" + sha256)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<byte[]> response = exchange(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw failure("upload " + sha256.substring(0, 8),
                    response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    public byte[] getBlob(String sha256) throws IOException {
        HttpRequest request = base("/api/v1/blobs/" + sha256).GET().build();
        HttpResponse<byte[]> response = exchange(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw failure("download " + sha256.substring(0, 8), response.statusCode(), "");
        }
        // Verified on arrival. The name is the checksum, so checking costs one hash and
        // catches a truncated transfer before it is written over a document.
        String actual = WorkspaceScanner.sha256(response.body());
        if (!actual.equalsIgnoreCase(sha256)) {
            throw new IOException("content from " + host() + " does not match its checksum - "
                    + "expected " + sha256 + ", got " + actual + ". Nothing was written.");
        }
        return response.body();
    }

    public String getSettings() throws IOException {
        return string(send("GET", "/api/v1/settings", null, null), "content");
    }

    public void putSettings(String json) throws IOException {
        send("PUT", "/api/v1/settings", json, "application/json");
    }

    // ------------------------------------------------------------------ plumbing

    private HttpRequest.Builder base(String path) throws IOException {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + authorization.token())
                /*
                 * Which machine this is, so the account can withdraw this one and leave the
                 * others alone. Not a credential - it names the machine, the token
                 * authenticates it - and the label is sent so the list is readable by
                 * whoever has to decide.
                 */
                .header("X-MDViewer-Device", device.id())
                .header("X-MDViewer-Device-Label", device.label());
    }

    private String send(String method, String path, String body, String contentType)
            throws IOException {
        HttpRequest.Builder builder = base(path);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = exchange(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw failure(method + " " + path, response.statusCode(), response.body());
        }
        return response.body();
    }

    /**
     * One request, and a second one if the first was refused.
     *
     * <p>A token can stop being accepted before it expires - the session ended elsewhere,
     * a key rotated - and a sync that is halfway through uploading is the worst moment to
     * give up over something a single request can fix. Exactly one retry: if a freshly
     * issued token is refused too, the problem is not staleness and trying again would only
     * turn one failure into a loop.
     *
     * <p>{@code renew} raises {@code NotSignedIn} when there is nothing left to try, which
     * is what tells the caller to offer a sign-in rather than report a fault.
     */
    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        HttpResponse<T> response = once(request, handler);
        if (response.statusCode() != 401) {
            return response;
        }
        String renewed = authorization.renew();
        return once(HttpRequest.newBuilder(request,
                        (name, value) -> !name.equalsIgnoreCase("Authorization"))
                .header("Authorization", "Bearer " + renewed)
                .build(), handler);
    }

    private <T> HttpResponse<T> once(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the sync was interrupted", e);
        } catch (IOException e) {
            throw new IOException("could not reach " + host() + " - " + e.getMessage(), e);
        }
    }

    /**
     * The server's own words, kept.
     *
     * <p>Its refusals say what to do next - a 409 means plan again, a 413 says how many
     * bytes short you are - and flattening those into "request failed" throws away the
     * only part the reader can act on.
     */
    private IOException failure(String what, int status, String body) {
        String message = string(body, "message");
        return new SyncException(status, string(body, "error"),
                what + " failed (" + status + ")"
                + (message.isBlank() ? "" : ": " + message));
    }

    /** Carries the status so a caller can tell "plan again" from "you are out of room". */
    public static class SyncException extends IOException {
        public final int status;
        public final String code;

        public SyncException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static String filesJson(List<FileState> files) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            FileState file = files.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"path\":").append(quote(file.path()))
                    .append(",\"sha256\":").append(quote(file.hash()))
                    .append(",\"size\":").append(file.size()).append('}');
        }
        return out.append(']').toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
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

    /* A reader for exactly the shapes this API returns, not a JSON parser. Enough to find
       a named string, number or boolean, and to split an array of flat objects. */

    static String string(String json, String field) {
        int at = indexOfField(json, field);
        if (at < 0) {
            return "";
        }
        int quote = json.indexOf('"', at);
        int colon = json.indexOf(':', at);
        if (colon < 0) {
            return "";
        }
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = valueStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return quote < 0 ? "" : out.toString();
    }

    static long number(String json, String field) {
        int at = indexOfField(json, field);
        if (at < 0) {
            return 0;
        }
        int colon = json.indexOf(':', at);
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            i++;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean bool(String json, String field) {
        int at = indexOfField(json, field);
        if (at < 0) {
            return false;
        }
        int colon = json.indexOf(':', at);
        return json.startsWith("true", skipSpace(json, colon + 1));
    }

    private static int skipSpace(String json, int from) {
        int i = from;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int indexOfField(String json, String field) {
        return json == null ? -1 : json.indexOf("\"" + field + "\"");
    }

    /** The body of a response that is itself an array, rather than an object with one in it. */
    static String stripArray(String json) {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The bracketed body of a named array. */
    static String section(String json, String field) {
        int at = indexOfField(json, field);
        if (at < 0) {
            return "";
        }
        int open = json.indexOf('[', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(open + 1, i);
                }
            }
        }
        return "";
    }

    /** Each flat object in an array body. */
    static List<String> objects(String arrayBody) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(arrayBody.substring(start, i + 1));
                }
            }
        }
        return out;
    }

    /** Each string in an array body. */
    static List<String> strings(String arrayBody) {
        List<String> out = new ArrayList<>();
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '"') {
                if (inString) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                inString = !inString;
            } else if (inString) {
                current.append(c);
            }
        }
        return out;
    }
}
