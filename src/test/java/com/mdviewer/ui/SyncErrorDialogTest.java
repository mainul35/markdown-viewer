package com.mdviewer.ui;

import com.mdviewer.sync.CloudClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code shown when a sync fails.
 *
 * <p>The code is the part somebody can act on, and the part they can quote when asking for
 * help. A 409 means plan again; a 413 says how many bytes short you are; an
 * <em>insufficient_scope</em> means a permission was never granted. All three used to arrive
 * as the same shrug in the status line.
 */
class SyncErrorDialogTest {

    @Test
    @DisplayName("a refusal shows the server's own code and status")
    void refusal() {
        Throwable failure = new CloudClient.SyncException(413, "quota_exceeded",
                "Commit failed (413): 2.4 MB short");
        assertEquals("quota_exceeded (HTTP 413)", SyncErrorDialog.codeOf(failure));
    }

    /**
     * Some refusals carry a status and no code at all. An empty field reads as a missing
     * value the reader is meant to interpret; saying "none" says the server did not name one.
     */
    @Test
    @DisplayName("a refusal without a code says so rather than showing a gap")
    void refusalWithoutACode() {
        assertEquals("none (HTTP 502)",
                SyncErrorDialog.codeOf(new CloudClient.SyncException(502, "", "Bad gateway")));
    }

    /**
     * Not reaching the server is the most common failure on a laptop, and it has no code
     * because nothing answered. "unreachable" is a truer answer than the class name of
     * whatever the network stack happened to throw.
     */
    @Test
    @DisplayName("a server that never answered is unreachable, not an exception name")
    void unreachable() {
        assertTrue(SyncErrorDialog.codeOf(new UnknownHostException("cloud.example")).
                startsWith("unreachable"));
        assertTrue(SyncErrorDialog.codeOf(new java.net.ConnectException("refused"))
                .startsWith("unreachable"));
    }

    @Test
    @DisplayName("anything else is named by what it was")
    void anythingElse() {
        assertEquals("IOException", SyncErrorDialog.codeOf(new IOException("disk gave up")));
    }
}
