package com.mdviewer.sync;

import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;

/**
 * Who this machine is signed in as, for as long as it is.
 *
 * <p>Holds the access token in memory and nothing else. The refresh token goes to
 * {@link TokenStore}, which is honest about what that does and does not protect; the
 * access token is worth minutes and is deliberately not written down at all.
 *
 * <p>One instance, shared by everything that talks to the cloud, so a refresh done for one
 * request is a refresh the next one benefits from rather than a second round trip.
 */
public final class CloudSession implements CloudClient.Authorization {

    private final SignIn signIn;
    private final TokenStore store;

    private Tokens tokens;

    public CloudSession(String issuer, String clientId) {
        this(new SignIn(issuer, clientId), new TokenStore());
    }

    CloudSession(SignIn signIn, TokenStore store) {
        this.signIn = signIn;
        this.store = store;
    }

    /**
     * Whether a sign-in is worth attempting without the browser.
     *
     * <p>True when there is a stored refresh token. It may turn out to be expired or
     * revoked, which is not knowable without asking - so this answers "is there something
     * to try", not "are you signed in", and the difference is why nothing here promises the
     * latter.
     */
    public boolean hasStoredSignIn() {
        return tokens != null || !store.read().isBlank();
    }

    /** The account name to show, or empty when nothing is signed in yet. */
    public String account() {
        return tokens == null ? "" : tokens.accountHint();
    }

    /**
     * Signs in, opening the reader's browser.
     *
     * @return which of the scopes the cloud API needs did not come back, empty when all did
     */
    public String signIn(Consumer<URI> browser) throws IOException {
        remember(signIn.authorize(browser));
        return tokens.missingScopes("workspace.read", "workspace.write", "settings.sync");
    }

    public void signOut() throws IOException {
        tokens = null;
        store.clear();
    }

    // ------------------------------------------------------- CloudClient.Authorization

    /**
     * A token good for the request about to be made.
     *
     * <p>Refreshes when the one in hand is about to expire, and picks up a stored refresh
     * token on the first call after a restart - which is what makes the application signed
     * in when it opens rather than after the reader notices it is not.
     */
    @Override
    public String token() throws IOException {
        if (tokens != null && !tokens.expired()) {
            return tokens.accessToken();
        }
        return renew();
    }

    /**
     * A new token, whether or not the current one looked valid.
     *
     * <p>Called after a 401. A token can stop being accepted before it expires - the
     * session was ended elsewhere, the key rotated - and asking again costs one request
     * where guessing costs the reader their sync.
     */
    @Override
    public String renew() throws IOException {
        String refreshToken = tokens != null && tokens.canRefresh()
                ? tokens.refreshToken()
                : store.read();

        if (refreshToken.isBlank()) {
            throw new NotSignedIn();
        }
        Tokens fresh;
        try {
            fresh = signIn.refresh(refreshToken);
        } catch (IOException e) {
            /*
             * A refresh token the server will not honour is the ordinary end of a session,
             * not a fault. Clearing it means the next launch offers a sign-in rather than
             * failing the same way again with a stored value that cannot work.
             *
             * Only a refusal from the authorization server reaches here. Writing the new
             * token to disk used to be inside this try, so a full disk or a read-only
             * ~/.mdviewer was treated as a rejected session: the reader was signed out and
             * a perfectly good refresh token deleted, over something that had nothing to do
             * with their credentials.
             */
            tokens = null;
            store.clear();
            throw new NotSignedIn();
        }

        this.tokens = fresh;
        if (fresh.canRefresh()) {
            try {
                store.write(fresh.refreshToken());
            } catch (IOException e) {
                /*
                 * The session is alive and stays alive - it just will not survive a restart.
                 * Said once, on the stream, rather than raised: interrupting a sync to
                 * report that a future launch may need signing in again would be the wrong
                 * moment and the wrong severity.
                 */
                System.err.println("MDViewer: signed in, but this machine could not store "
                        + "the sign-in for next time - " + e.getMessage());
            }
        }
        return tokens.accessToken();
    }

    /**
     * Keeps a fresh sign-in.
     *
     * <p>Used by the interactive sign-in, where the reader is watching and a failure to
     * store is worth telling them about. The refresh path deliberately does not use this:
     * there, a storage failure must not be mistaken for a rejected session.
     */
    private void remember(Tokens fresh) throws IOException {
        this.tokens = fresh;
        if (fresh.canRefresh()) {
            store.write(fresh.refreshToken());
        }
    }

    /**
     * There is no usable sign-in, and the reader has to do something about it.
     *
     * <p>Its own type because callers act on it differently from every other failure: this
     * is the one that means "offer the sign-in", where the rest mean "tell them what broke".
     */
    public static class NotSignedIn extends IOException {
        public NotSignedIn() {
            super("You are not signed in to the cloud. "
                    + "Use Settings > Sign In to Cloud, then try again.");
        }
    }
}
