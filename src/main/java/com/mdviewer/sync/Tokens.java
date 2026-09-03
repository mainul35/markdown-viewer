package com.mdviewer.sync;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * What came back from the authorization server.
 *
 * <p>The access token is what the cloud API reads; the refresh token is what survives a
 * restart. Nothing here verifies anything - verification is the server's job, and a client
 * that checked its own token would only be checking a value it was handed.
 */
public record Tokens(String accessToken, String refreshToken, Instant expiresAt, String scope) {

    /**
     * Thirty seconds early, so a token does not expire between the check and the request
     * arriving. The alternative is a 401 on a token that was valid when it was chosen,
     * which is a race that shows up as an intermittent failure and is tedious to find.
     */
    public boolean expired() {
        return expiresAt == null || Instant.now().plusSeconds(30).isAfter(expiresAt);
    }

    public boolean canRefresh() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /**
     * Whether the grants the cloud API needs actually arrived.
     *
     * <p>Worth asking at sign-in rather than at the first sync. A scope that was never
     * linked to the application produces a flow that authenticates perfectly and then takes
     * 403s from an unrelated-looking place; naming the missing scope here turns that into
     * one sentence.
     *
     * <p>An empty {@code scope} in the response means the server did not say, which is not
     * the same as refusing - so nothing is reported missing rather than inventing a
     * complaint about a grant that may well be present.
     */
    public String missingScopes(String... required) {
        if (scope == null || scope.isBlank()) {
            return "";
        }
        String granted = " " + scope.trim() + " ";
        StringBuilder missing = new StringBuilder();
        for (String needed : required) {
            if (!granted.contains(" " + needed + " ")) {
                missing.append(missing.isEmpty() ? "" : ", ").append(needed);
            }
        }
        return missing.toString();
    }

    static Tokens from(String json) {
        long lifetime = CloudClient.number(json, "expires_in");
        return new Tokens(
                CloudClient.string(json, "access_token"),
                CloudClient.string(json, "refresh_token"),
                lifetime > 0 ? Instant.now().plusSeconds(lifetime) : null,
                CloudClient.string(json, "scope"));
    }

    /**
     * A name to show beside "signed in as", read out of the token without checking it.
     *
     * <p>Display only, and deliberately so: this is the client reading a value it was
     * given, which tells the reader which account they picked and is not evidence of
     * anything. Every decision that matters is made by the server against a signature this
     * never looks at.
     */
    public String accountHint() {
        try {
            int first = accessToken.indexOf('.');
            int second = accessToken.indexOf('.', first + 1);
            if (first < 0 || second < 0) {
                return "";
            }
            String payload = new String(
                    Base64.getUrlDecoder().decode(accessToken.substring(first + 1, second)),
                    StandardCharsets.UTF_8);
            String email = CloudClient.string(payload, "email");
            return email.isBlank() ? CloudClient.string(payload, "sub") : email;
        } catch (RuntimeException e) {
            // A token this cannot read is still a token the server may well accept.
            return "";
        }
    }
}
