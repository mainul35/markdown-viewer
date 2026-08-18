package com.mdviewer.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proof that the application redeeming an authorization code is the one that asked for it.
 *
 * <p>A desktop application cannot keep a client secret - it ships to the reader's machine,
 * so anything it knows, they know. PKCE replaces the secret with a value invented for one
 * sign-in: the challenge goes out with the request, the verifier comes back with the code,
 * and the authorization server checks they match. Someone who intercepts the code has a
 * code they cannot spend.
 *
 * <p>S256, never {@code plain}. The plain method sends the verifier itself as the
 * challenge, which protects against nothing and exists only for clients that cannot hash.
 */
public record Pkce(String verifier, String challenge) {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 bytes, which is the top of the range RFC 7636 allows and costs nothing. */
    public static Pkce create() {
        String verifier = random(32);
        return new Pkce(verifier, challengeFor(verifier));
    }

    /** Also used for {@code state}, which guards the callback rather than the code. */
    public static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String challengeFor(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }
}
