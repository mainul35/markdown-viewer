package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a sync is willing to write.
 *
 * <p>Every path here arrived in a server response. That is not a statement about trusting
 * the server - it is that a reply is data from the network, and a client which writes
 * wherever a reply names has handed the filesystem to whatever is on the other end of the
 * connection, or to anything that can impersonate it.
 */
class SyncRunnerPathsTest {

    private SyncRunner runnerFor(Path root) {
        // No client and no state: these are the path rules, which touch neither.
        return new SyncRunner(root, null, null, null);
    }

    @Test
    @DisplayName("an ordinary path resolves inside the workspace")
    void ordinaryPath(@TempDir Path root) throws IOException {
        Path resolved = runnerFor(root).safeResolve("notes/api.md");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertEquals("api.md", resolved.getFileName().toString());
    }

    @Test
    @DisplayName("a path that climbs out is refused")
    void traversalIsRefused(@TempDir Path root) {
        SyncRunner runner = runnerFor(root);
        for (String path : new String[]{
                "../escaped.md",
                "../../escaped.md",
                "notes/../../escaped.md",
                "../.ssh/authorized_keys"}) {
            IOException refused = assertThrows(IOException.class, () -> runner.safeResolve(path),
                    "should have refused " + path);
            assertTrue(refused.getMessage().contains("leaves the workspace"),
                    "the refusal should say why: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a path that only looks like it climbs out is allowed")
    void innocentDotsAreFine(@TempDir Path root) throws IOException {
        // ".." inside a name is not a traversal, and refusing it would be a bug of its own.
        assertTrue(runnerFor(root).safeResolve("notes/..hidden.md").startsWith(root.toRealPath()));
        assertTrue(runnerFor(root).safeResolve("a/b/../c.md").startsWith(root.toRealPath()));
    }

    /**
     * The conflict copy used to be resolved from the raw server path rather than the checked
     * one, which made it the single place a reply could name somewhere outside the workspace
     * and be believed.
     */
    @Test
    @DisplayName("the conflict copy lands beside the document, inside the workspace")
    void conflictCopyStaysInside(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("notes"));
        SyncRunner runner = runnerFor(root);

        Path beside = runner.conflictPathFor(runner.safeResolve("notes/api.md"));

        assertTrue(beside.startsWith(root.toRealPath()), "left the workspace: " + beside);
        assertEquals(root.toRealPath().resolve("notes"), beside.getParent());
        assertTrue(beside.getFileName().toString().startsWith("api.conflict-"));
        assertTrue(beside.getFileName().toString().endsWith(".md"));
    }

    @Test
    @DisplayName("a second conflict in the same minute does not overwrite the first")
    void twoConflictsInTheSameMinute(@TempDir Path root) throws IOException {
        SyncRunner runner = runnerFor(root);
        Path first = runner.conflictPathFor(runner.safeResolve("api.md"));
        Files.writeString(first, "the first one");

        Path second = runner.conflictPathFor(runner.safeResolve("api.md"));

        assertTrue(!first.equals(second), "the second copy would have overwritten the first");
        assertEquals("the first one", Files.readString(first));
    }

    /**
     * The guard is asserted on the method itself, not only on the call site that composes it
     * with safeResolve - so removing that call is a failing test rather than a silent hole.
     */
    @Test
    @DisplayName("a conflict copy outside the workspace is refused on its own")
    void conflictCopyRefusesAnOutsidePath(@TempDir Path root, @TempDir Path elsewhere) {
        SyncRunner runner = runnerFor(root);
        IOException refused = assertThrows(IOException.class,
                () -> runner.conflictPathFor(elsewhere.resolve("api.md")));
        assertTrue(refused.getMessage().contains("outside the workspace"),
                "the refusal should say why: " + refused.getMessage());
    }

    @Test
    @DisplayName("a document with no extension still gets a readable conflict name")
    void noExtension(@TempDir Path root) throws IOException {
        SyncRunner runner = runnerFor(root);
        Path beside = runner.conflictPathFor(runner.safeResolve("LICENSE"));
        assertTrue(beside.getFileName().toString().startsWith("LICENSE.conflict-"));
    }
}
