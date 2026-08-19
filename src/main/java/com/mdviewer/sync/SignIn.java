package com.mdviewer.sync;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Signing in to the cloud, from a desktop application.
 *
 * <p>Authorization code with PKCE and a loopback redirect, which is what RFC 8252 asks a
 * native application to do. The reader signs in to their own authorization server in their
 * own browser - MDViewer never sees the password, and there is no embedded web view where
 * they would have to take on trust that it is not looking.
 *
 * <p>The listener binds the loopback address explicitly rather than every interface, so it
 * is not reachable from the network while it is open. It is opened for one sign-in, waits
 * a few minutes at most, and is closed in a finally block whatever happens.
 *
 * <p>Not final. This and {@link TokenStore} are the two collaborators {@link CloudSession}
 * is defined in terms of, and the behaviour worth pinning down - that a rejected session
 * signs the reader out and a failed disk write does not - can only be asserted by standing
 * in for them. Neither is extended in the application itself.
 */
public class SignIn {

    /**
     * The port the redirect comes back to.
     *
     * <p>Fixed rather than chosen at random, because the authorization server stores the
     * redirect URI it will accept and cannot be told a new one per attempt. RFC 8252 lets a
     * server accept any port on the loopback address for exactly this reason; vsd-auth-server
     * matches exactly, so both sides name the same number.
     */
    public static final int PORT = 8137;

    public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";

    /** What the cloud API's rules are written in terms of, plus who the reader is. */
    public static final String SCOPES =
            "openid profile email workspace.read workspace.write settings.sync";

    private static final Duration PATIENCE = Duration.ofMinutes(3);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String issuer;
    private final String clientId;

    public SignIn(String issuer, String clientId) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.clientId = clientId;
    }

    /** Where the authorization server says its endpoints are. */
    public record Endpoints(String authorization, String token) { }

    /**
     * Asks the server where its endpoints are instead of assuming.
     *
     * <p>One request at sign-in, against paths being hard-coded and then quietly wrong the
     * day the authorization server moves one.
     */
    public Endpoints endpoints() throws IOException {
        String document = get(issuer + "/.well-known/openid-configuration");
        String authorization = CloudClient.string(document, "authorization_endpoint");
        String token = CloudClient.string(document, "token_endpoint");
        if (authorization.isBlank() || token.isBlank()) {
            throw new IOException(issuer + " did not describe itself as an OpenID provider. "
                    + "Check that the address is the authorization server and not the API.");
        }
        return new Endpoints(authorization, token);
    }

    /**
     * The whole flow: open the browser, wait for the redirect, exchange the code.
     *
     * @param browser how to open a URI - the caller passes JavaFX's host services, which
     *                knows how to do this on each platform
     */
    public Tokens authorize(Consumer<URI> browser) throws IOException {
        Endpoints endpoints = endpoints();
        Pkce pkce = Pkce.create();
        String state = Pkce.random(16);

        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
        HttpServer server = listen(callback);
        try {
            browser.accept(URI.create(endpoints.authorization()
                    + "?response_type=code"
                    + "&client_id=" + encode(clientId)
                    + "&redirect_uri=" + encode(REDIRECT)
                    + "&scope=" + encode(SCOPES)
                    + "&state=" + encode(state)
                    + "&code_challenge=" + encode(pkce.challenge())
                    + "&code_challenge_method=S256"));

            Map<String, String> answer = await(callback);

            if (answer.containsKey("error")) {
                String detail = answer.getOrDefault("error_description", "");
                throw new IOException("the authorization server refused the sign-in: "
                        + answer.get("error") + (detail.isBlank() ? "" : " - " + detail));
            }
            /*
             * The state check is what makes the redirect trustworthy. Without it this would
             * accept a code from anyone who could get the browser to visit the callback,
             * which is the whole reason the parameter exists.
             */
            if (!state.equals(answer.get("state"))) {
                throw new IOException("the sign-in came back with the wrong state and was "
                        + "discarded. Nothing was signed in. Try again.");
            }
            String code = answer.get("code");
            if (code == null || code.isBlank()) {
                throw new IOException("the sign-in came back without a code.");
            }

            return exchange(endpoints.token(),
                    "grant_type=authorization_code"
                    + "&code=" + encode(code)
                    + "&redirect_uri=" + encode(REDIRECT)
                    + "&client_id=" + encode(clientId)
                    + "&code_verifier=" + encode(pkce.verifier()));
        } finally {
            server.stop(0);
        }
    }

    /** A new access token from the stored refresh token, with no browser involved. */
    public Tokens refresh(String refreshToken) throws IOException {
        return exchange(endpoints().token(),
                "grant_type=refresh_token"
                + "&refresh_token=" + encode(refreshToken)
                + "&client_id=" + encode(clientId));
    }

    // ------------------------------------------------------------------ plumbing

    private HttpServer listen(CompletableFuture<Map<String, String>> callback) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
        server.createContext("/callback", exchange -> {
            Map<String, String> parameters = query(exchange.getRequestURI().getRawQuery());
            boolean ok = parameters.containsKey("code") && !parameters.containsKey("error");

            byte[] page = page(ok).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            callback.complete(parameters);
        });
        server.start();
        return server;
    }

    private Map<String, String> await(CompletableFuture<Map<String, String>> callback)
            throws IOException {
        try {
            return callback.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("the sign-in was not completed within "
                    + PATIENCE.toMinutes() + " minutes, so MDViewer stopped waiting. "
                    + "Nothing was signed in.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the sign-in was interrupted.");
        } catch (ExecutionException e) {
            throw new IOException("the sign-in failed - " + e.getCause().getMessage(), e);
        }
    }

    private Tokens exchange(String tokenEndpoint, String form) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            /*
             * The server's own words. "invalid_grant" on a refresh means the session is
             * simply over and the reader should sign in again; flattening that into "token
             * request failed" hides the one thing they can act on.
             */
            String code = CloudClient.string(response.body(), "error");
            String detail = CloudClient.string(response.body(), "error_description");
            throw new IOException("the authorization server refused the token request ("
                    + response.statusCode() + ")"
                    + (code.isBlank() ? "" : ": " + code)
                    + (detail.isBlank() ? "" : " - " + detail));
        }
        Tokens tokens = Tokens.from(response.body());
        if (tokens.accessToken() == null || tokens.accessToken().isBlank()) {
            throw new IOException("the authorization server returned no access token.");
        }
        return tokens;
    }

    private String get(String url) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(url + " answered " + response.statusCode() + ".");
        }
        return response.body();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the sign-in was interrupted.");
        } catch (IOException e) {
            throw new IOException("could not reach " + request.uri().getHost()
                    + " - " + e.getMessage(), e);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> parameters = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parameters.put(
                        java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    /**
     * What the browser shows when the redirect lands.
     *
     * <p>Its one job is to say the work has moved back to the application, because the
     * reader is looking at a browser tab and the thing they were doing is now finished
     * somewhere else. Self-contained: this page is served by a listener that is about to
     * close, so it can ask for nothing.
     */
    private static String page(boolean ok) {
        String title = ok ? "Signed in" : "Sign-in was not completed";
        String message = ok
                ? "You can close this tab and go back to MDViewer."
                : "Nothing was signed in. Go back to MDViewer and try again.";
        return "<!doctype html><html lang=en><meta charset=utf-8>"
                + "<title>MDViewer</title>"
                + "<style>"
                + "body{font:16px/1.6 system-ui,sans-serif;display:grid;place-items:center;"
                + "min-height:100vh;margin:0;background:#faf9f7;color:#1b1a17}"
                + "main{text-align:center;max-width:28rem;padding:2rem}"
                + "h1{font-size:1.3rem;margin:0 0 .6rem}"
                + "p{margin:0;color:#5c5850}"
                + "@media(prefers-color-scheme:dark){body{background:#17161a;color:#eceae4}"
                + "p{color:#9b968c}}"
                + "</style>"
                + "<main><h1>" + title + "</h1><p>" + message + "</p></main>";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
