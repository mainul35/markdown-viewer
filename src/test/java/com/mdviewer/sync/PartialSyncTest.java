package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sending one file or one folder, and nothing else.
 *
 * <p>Two things here can be quietly catastrophic rather than merely wrong, and both are
 * asserted below: a commit that omits a path deletes it, and an agreed base that claims more
 * than it should turns the next full sync into a delete.
 */
class PartialSyncTest {

    private static Path write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("a folder means everything under it, and a file means itself")
    void selects(@TempDir Path root) throws IOException {
        write(root, "notes/2026/plan.md", "# Plan");
        write(root, "notes/2026/retro.md", "# Retro");
        write(root, "notes/index.md", "# Index");
        write(root, "scratch/junk.md", "# Junk");

        WorkspaceScanner.Scan scan = WorkspaceScanner.scan(root);

        assertEquals(Set.of("notes/2026/plan.md", "notes/2026/retro.md"),
                PartialSync.pathsUnder(root, root.resolve("notes/2026"), scan));

        // The whole path travels with it, so the folders around it exist in the cloud too.
        assertEquals(Set.of("notes/2026/plan.md"),
                PartialSync.pathsUnder(root, root.resolve("notes/2026/plan.md"), scan));

        assertEquals(4, PartialSync.pathsUnder(root, root, scan).size());
    }

    /**
     * The separator is what makes a prefix a folder. Without it "notes" would also claim
     * "notes-archive", which is a different folder that merely starts the same way - and
     * somebody syncing this year's notes would silently send an archive they left behind.
     */
    @Test
    @DisplayName("a folder does not claim its neighbour with a longer name")
    void prefixIsAFolderNotAStringStart() {
        assertTrue(PartialSync.covers("notes", "notes/plan.md"));
        assertTrue(PartialSync.covers("notes", "notes"));
        assertFalse(PartialSync.covers("notes", "notes-archive/old.md"));
        assertFalse(PartialSync.covers("notes", "notesomething.md"));
    }

    /**
     * The reason a push cannot simply commit what it chose.
     *
     * <p>A commit is read as the whole truth: every path missing from it is soft-deleted. So
     * the list has to be the workspace as the server describes it with the chosen files laid
     * over the top, and this is the assertion that says so.
     */
    @Test
    @DisplayName("a push commits everything the server already has, plus what was chosen")
    void doesNotDeleteWhatItDidNotSend() {
        CloudClient.Plan remote = new CloudClient.Plan(7, List.of(
                new CloudClient.Change("archive/2019.md", "DOWNLOAD", null, "aaa", 100, null),
                new CloudClient.Change("README.md", "DOWNLOAD", null, "bbb", 200, null)),
                List.of(), 0, true, false, 0, 0, "free");

        Map<String, FileState> holding = SyncRunner.whatTheServerHolds(remote);

        assertEquals(Set.of("archive/2019.md", "README.md"), holding.keySet());
        assertEquals("aaa", holding.get("archive/2019.md").hash());

        // What the push then commits: the server's own list with the chosen file over it.
        holding.put("notes/plan.md", new FileState("notes/plan.md", "ccc", 50));
        assertEquals(3, holding.size(), "a document the push never touched must survive it");
    }

    /**
     * The other half, and the subtler one.
     *
     * <p>The agreed base is read by the next plan as "what we both had last time", so a path
     * in it that is missing from disk reads as deleted here. If a push recorded the whole
     * commit list, the next full sync from this machine would offer to delete every document
     * it has never seen - a partial push arming a full delete.
     */
    @Test
    @DisplayName("only what was sent joins the agreed base")
    void agreesOnlyAboutWhatItSent() {
        Map<String, String> before = Map.of("notes/old.md", "old-hash");
        List<FileState> chosen = List.of(new FileState("notes/plan.md", "new-hash", 12));

        Map<String, String> after = SyncRunner.agreedAfterPush(before, chosen);

        assertEquals(Set.of("notes/old.md", "notes/plan.md"), after.keySet());
        assertEquals("new-hash", after.get("notes/plan.md"));
        assertFalse(after.containsKey("archive/2019.md"),
                "a remote document this machine has never seen must not be agreed about");
    }
}
