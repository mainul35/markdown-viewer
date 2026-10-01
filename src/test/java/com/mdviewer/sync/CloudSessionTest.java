package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staying signed in, and knowing when you are not.
 *
 * <p>The distinction this has to keep straight is between "the authorization server will
 * not honour this session" and "this machine could not write a file". They arrive as the
 * same Java exception and mean opposite things: one should sign the reader out, and the
 * other must not.
 */
class CloudSessionTest {

    /** A sign-in that answers refresh requests without a network or a browser. */
    private static class StubSignIn extends SignIn {
        private final boolean honour;
        int refreshes;

        StubSignIn(boolean honour) {
            super("http://unused.invalid", "test-client");
            this.honour = honour;
        }

        @Override
        public Tokens refresh(String refreshToken) throws IOException {
            refreshes++;
            if (!honour) {
                throw new IOException("the authorization server refused the token request "
                        + "(400): invalid_grant");
            }
            return new Tokens("access-" + refreshes, "refresh-" + refreshes,
                    Instant.now().plusSeconds(300), "workspace.read");
        }
    }

    /**
     * A server that rotates refresh tokens as vsd-auth-server does: each one is honoured once,
     * then refused. Slow enough that two threads arriving together are both inside it.
     */
    private static class RotatingSignIn extends SignIn {
        private String current;
        private int issued;

        RotatingSignIn(String first) {
            super("http://unused.invalid", "test-client");
            this.current = first;
        }

        @Override
        public Tokens refresh(String refreshToken) throws IOException {
            String expected;
            synchronized (this) {
                expected = current;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (!refreshToken.equals(expected) || !refreshToken.equals(current)) {
                    throw new IOException("the authorization server refused the token request (400): invalid_grant");
                }
                issued++;
                current = "refresh-" + issued;
                return new Tokens("access-" + issued, current, Instant.now().plusSeconds(300), "workspace.read");
            }
        }

        synchronized String current() {
            return current;
        }
    }

    /**
     * Auto-sync and a diagram upload, on their own threads, both meeting an expired token.
     * With rotation, two refreshes with the same token are one success and one refusal, and
     * the refusal signed the reader out.
     */
    @Test
    @DisplayName("two threads refreshing at once both get a token, and the reader stays signed in")
    void concurrentRefreshesStaySignedIn(@TempDir Path directory) throws Exception {
        TokenStore store = storeAt(directory.resolve("credentials"));
        store.write("stored-refresh");
        RotatingSignIn server = new RotatingSignIn("stored-refresh");
        CloudSession session = sessionWith(server, store);

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<String>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                answers.add(threads.submit(() -> {
                    go.await();
                    return session.renew();
                }));
            }
            go.countDown();
            for (var answer : answers) {
                assertTrue(answer.get(10, java.util.concurrent.TimeUnit.SECONDS).startsWith("access-"),
                        "each caller gets a token, neither is told to sign in");
            }
        } finally {
            threads.shutdownNow();
        }
        assertEquals(server.current(), store.read(), "the stored refresh token is the one the server will honour next");
        assertTrue(session.hasStoredSignIn());
    }

    private TokenStore storeAt(Path file) throws Exception {
        var constructor = TokenStore.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(file);
    }

    private CloudSession sessionWith(SignIn signIn, TokenStore store) throws Exception {
        var constructor = CloudSession.class.getDeclaredConstructor(SignIn.class, TokenStore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(signIn, store);
    }

    @Test
    @DisplayName("a stored sign-in produces a token without a browser")
    void refreshesFromStorage(@TempDir Path directory) throws Exception {
        TokenStore store = storeAt(directory.resolve("credentials"));
        store.write("stored-refresh");
        CloudSession session = sessionWith(new StubSignIn(true), store);

        assertEquals("access-1", session.token());
        // The second call is inside the token's life, so it must not ask again.
        assertEquals("access-1", session.token());
    }

    @Test
    @DisplayName("nothing stored means the reader is asked to sign in")
    void nothingStored(@TempDir Path directory) throws Exception {
        CloudSession session = sessionWith(new StubSignIn(true),
                storeAt(directory.resolve("credentials")));

        assertThrows(CloudSession.NotSignedIn.class, session::token);
    }

    @Test
    @DisplayName("a refused refresh token is forgotten, not kept to fail again")
    void refusedRefreshIsForgotten(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("credentials");
        TokenStore store = storeAt(file);
        store.write("expired-refresh");
        CloudSession session = sessionWith(new StubSignIn(false), store);

        assertThrows(CloudSession.NotSignedIn.class, session::token);
        assertEquals("", store.read());
    }

    /**
     * The one this exists for. Writing the new token to disk used to sit inside the same
     * try as the refresh itself, so a full disk or a read-only directory was handled as a
     * rejected session: the reader was signed out and a working refresh token deleted, over
     * something that had nothing to do with their credentials.
     */
    @Test
    @DisplayName("a storage failure does not sign the reader out")
    void storageFailureDoesNotSignOut(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("credentials");
        TokenStore working = storeAt(file);
        working.write("stored-refresh");

        // A store that reads what is there and refuses to write, as a full disk would.
        TokenStore unwritable = new TokenStore(file) {
            @Override
            public void write(String refreshToken) throws IOException {
                throw new IOException("No space left on device");
            }
        };
        CloudSession session = sessionWith(new StubSignIn(true), unwritable);

        assertEquals("access-1", session.token(), "the session should have survived");
        assertTrue(Files.exists(file), "a working refresh token should not have been deleted");
        assertEquals("stored-refresh", working.read());
    }
}
