package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sync may do with nobody watching.
 *
 * <p>Every test here is really one question asked of a different plan: would being wrong
 * about this cost somebody their time, or their work? The first is allowed to happen
 * automatically and the second is not.
 */
class AutoSyncTest {

    private static CloudClient.Change change(String action, String path) {
        return new CloudClient.Change(path, action, "localhash", "remotehash", 100, "because");
    }

    private static SyncRunner.Proposal plan(boolean fitsInQuota, CloudClient.Change... changes) {
        return new SyncRunner.Proposal(
                new WorkspaceScanner.Scan(List.of(), List.of()),
                new CloudClient.Plan(7, List.of(changes), List.of(), 100,
                        fitsInQuota, false, 0, 1000, "FREE"));
    }

    private static SyncRunner.Proposal plan(CloudClient.Change... changes) {
        return plan(true, changes);
    }

    @Test
    @DisplayName("sending and receiving documents happens on its own")
    void additionsAreSafe() {
        AutoSync.Decision decision = AutoSync.decide(plan(
                change("UPLOAD", "notes/new.md"),
                change("DOWNLOAD", "notes/from-the-laptop.md")));

        assertTrue(decision.apply(), decision.reason());
        assertTrue(decision.reason().contains("1 up"), decision.reason());
        assertTrue(decision.reason().contains("1 down"), decision.reason());
    }

    @Test
    @DisplayName("nothing to do is not something to announce")
    void convergedIsSilent() {
        AutoSync.Decision decision = AutoSync.decide(plan(change("CONVERGED", "notes/same.md")));

        assertFalse(decision.apply());
        assertTrue(decision.reason().isEmpty(), "a quiet sync should say nothing");
    }

    /**
     * A document edited in two places. Applying this without asking would put a second file
     * beside the reader's while they were working, and they would find out by noticing it.
     */
    @Test
    @DisplayName("a conflict waits for a person")
    void conflictsHold() {
        AutoSync.Decision decision = AutoSync.decide(plan(
                change("UPLOAD", "notes/safe.md"),
                change("CONFLICT", "notes/both-sides.md")));

        assertFalse(decision.apply());
        assertTrue(decision.reason().contains("changed here and in the cloud"), decision.reason());
    }

    /**
     * The case this rule is really for: a workspace on a drive that is not mounted scans as
     * empty, so every document in it looks deleted. Applied on a schedule that would empty
     * the cloud copy while nobody was looking.
     */
    @Test
    @DisplayName("a deletion waits for a person, whichever way it goes")
    void deletionsHold() {
        assertFalse(AutoSync.decide(plan(change("DELETE_LOCAL", "notes/gone-there.md"))).apply());
        assertFalse(AutoSync.decide(plan(change("DELETE_REMOTE", "notes/gone-here.md"))).apply());

        AutoSync.Decision decision = AutoSync.decide(plan(change("DELETE_REMOTE", "notes/x.md")));
        assertTrue(decision.reason().contains("removed"), decision.reason());
    }

    @Test
    @DisplayName("a whole plan is held, not the awkward half of it")
    void safeChangesWaitWithTheUnsafeOnes() {
        AutoSync.Decision decision = AutoSync.decide(plan(
                change("UPLOAD", "notes/a.md"),
                change("DOWNLOAD", "notes/b.md"),
                change("DELETE_LOCAL", "notes/c.md")));

        assertFalse(decision.apply(),
                "applying the safe half leaves the workspace in a state nobody chose");
    }

    @Test
    @DisplayName("no room in the cloud is not something to retry every few minutes")
    void quotaHolds() {
        AutoSync.Decision decision = AutoSync.decide(plan(false, change("UPLOAD", "notes/big.md")));

        assertFalse(decision.apply());
        assertTrue(decision.reason().contains("not enough room"), decision.reason());
    }

    @Test
    @DisplayName("an action this does not recognise is not assumed to be safe")
    void unknownActionsHold() {
        assertFalse(AutoSync.decide(plan(change("SOMETHING_NEW", "notes/x.md"))).apply(),
                "a new action on the server should not start happening here by default");
    }

    @Test
    @DisplayName("one document reads as one document")
    void countsReadNaturally() {
        assertTrue(AutoSync.decide(plan(change("UPLOAD", "notes/a.md")))
                .reason().contains("Sent 1 document."));
        assertTrue(AutoSync.decide(plan(change("DOWNLOAD", "a.md"), change("DOWNLOAD", "b.md")))
                .reason().contains("Received 2 documents."));
    }
}
