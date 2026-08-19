package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this machine remembers about a workspace between runs.
 *
 * <p>The base is the third leg of every reconciliation: local and remote can tell you two
 * sides differ, and only this can tell you which one moved. Losing it is not a crash - it
 * is a sync that starts making confident wrong decisions.
 */
class SyncStateTest {

    /** A state file in a temporary directory, since the real one lives under ~/.mdviewer. */
    private SyncState stateIn(Path directory) throws Exception {
        var constructor = SyncState.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        SyncState state = constructor.newInstance(directory.resolve("workspace.properties"));
        var load = SyncState.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(state);
        return state;
    }

    /**
     * Enrolment used to live in memory until the first successful sync wrote it out. The
     * workspace exists on the server from the moment it is created, so a sync that failed
     * in between left one in the cloud that this folder had no record of - and creating it
     * again was refused because the name was taken.
     */
    @Test
    @DisplayName("a link survives a crash before the first sync")
    void enrolIsWrittenImmediately(@TempDir Path directory) throws Exception {
        stateIn(directory).enrol("workspace-abc");

        SyncState afterRestart = stateIn(directory);
        assertTrue(afterRestart.isEnrolled());
        assertEquals("workspace-abc", afterRestart.workspaceId());
    }

    @Test
    @DisplayName("a fresh folder is not enrolled")
    void freshFolder(@TempDir Path directory) throws Exception {
        assertFalse(stateIn(directory).isEnrolled());
        assertEquals(0, stateIn(directory).revision());
    }

    @Test
    @DisplayName("the agreed base comes back exactly as it went in")
    void baseRoundTrips(@TempDir Path directory) throws Exception {
        SyncState state = stateIn(directory);
        state.enrol("w1");
        state.agreed(9, Map.of(
                "notes/api.md", "aaa",
                "images/screen shot.png", "bbb"));

        SyncState afterRestart = stateIn(directory);
        assertEquals(9, afterRestart.revision());
        assertEquals("aaa", afterRestart.base().get("notes/api.md"));
        assertEquals("bbb", afterRestart.base().get("images/screen shot.png"));
    }

    /**
     * Paths are values in this file rather than keys, so the characters that would break a
     * properties file are ordinary text here. Worth asserting: these are real filenames.
     */
    @Test
    @DisplayName("awkward filenames survive the round trip")
    void awkwardPaths(@TempDir Path directory) throws Exception {
        SyncState state = stateIn(directory);
        state.enrol("w1");
        state.agreed(1, Map.of(
                "notes/a=b.md", "h1",
                "notes/c:d.md", "h2",
                "notes/[draft].md", "h3",
                "notes/# heading.md", "h4"));

        Map<String, String> base = stateIn(directory).base();
        assertEquals("h1", base.get("notes/a=b.md"));
        assertEquals("h2", base.get("notes/c:d.md"));
        assertEquals("h3", base.get("notes/[draft].md"));
        assertEquals("h4", base.get("notes/# heading.md"));
    }

    @Test
    @DisplayName("forgetting a workspace removes the file, not just the fields")
    void forget(@TempDir Path directory) throws Exception {
        SyncState state = stateIn(directory);
        state.enrol("w1");
        state.agreed(3, Map.of("a.md", "aaa"));
        assertTrue(Files.exists(directory.resolve("workspace.properties")));

        state.forget();

        assertFalse(Files.exists(directory.resolve("workspace.properties")));
        assertFalse(stateIn(directory).isEnrolled());
    }

    @Test
    @DisplayName("a damaged state file is treated as no state, not as a crash")
    void damagedFile(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("workspace.properties"),
                "this is not a state file\nrevision\tnot-a-number\n");

        SyncState state = stateIn(directory);

        assertFalse(state.isEnrolled());
        assertEquals(0, state.revision());
    }

    @Test
    @DisplayName("the base is a copy, so a caller cannot edit what was agreed")
    void baseIsACopy(@TempDir Path directory) throws Exception {
        SyncState state = stateIn(directory);
        state.enrol("w1");
        state.agreed(1, Map.of("a.md", "aaa"));

        try {
            state.base().put("b.md", "bbb");
        } catch (UnsupportedOperationException expected) {
            // Either refusing or copying is fine; silently accepting the edit is not.
        }
        assertFalse(state.base().containsKey("b.md"));
    }
}
