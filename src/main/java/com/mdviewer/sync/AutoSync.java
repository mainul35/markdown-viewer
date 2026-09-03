package com.mdviewer.sync;

import java.util.List;

/**
 * Whether a sync can be carried out without asking.
 *
 * <p>Sync is deliberately two steps - work out what would happen, then do it - because a
 * sync that writes the moment it is asked what it would do is one nobody can safely press.
 * Doing it automatically removes the person from between those steps, so the question
 * becomes: which plans are safe to apply with nobody watching?
 *
 * <p>Adding a file and receiving a file are safe. Nothing is lost either way: an upload
 * leaves the local copy alone, and a download only writes a path that has no local version.
 *
 * <p>Removing a file is not, in either direction, and neither is a conflict. Those are the
 * cases where being wrong costs somebody their work rather than their time, and they are
 * exactly what the review step exists for. When a plan contains one, this holds the whole
 * plan and says so - it does not apply the safe half and leave the rest, because a
 * half-applied plan is a state nobody chose.
 *
 * <p>The deletion rule earns its keep in an ordinary case rather than an exotic one: a
 * workspace on a drive that is not mounted scans as empty, and every document in it looks
 * deleted. Applied automatically that would empty the cloud copy too, on a schedule, while
 * nobody was looking.
 */
public final class AutoSync {

    /** What to do with a plan, and why - the reason is shown to the reader. */
    public record Decision(boolean apply, String reason) {

        static Decision apply(String reason) {
            return new Decision(true, reason);
        }

        static Decision hold(String reason) {
            return new Decision(false, reason);
        }
    }

    private AutoSync() {
    }

    public static Decision decide(SyncRunner.Proposal proposal) {
        if (proposal.isUpToDate()) {
            return Decision.hold("");
        }

        /*
         * Quota first. A plan that does not fit will be refused by the server, and finding
         * that out every few minutes in the background is noise nobody can act on from
         * there - the dialog says how many bytes short you are.
         */
        if (!proposal.plan().fitsInQuota()) {
            return Decision.hold("There is not enough room in the cloud for this sync. "
                    + "Open Cloud Sync to see what it needs.");
        }

        int conflicts = proposal.conflicts().size();
        if (conflicts > 0) {
            return Decision.hold(conflicts + (conflicts == 1 ? " document was" : " documents were")
                    + " changed here and in the cloud. Open Cloud Sync to keep both versions.");
        }

        int deletions = proposal.of("DELETE_LOCAL").size() + proposal.of("DELETE_REMOTE").size();
        if (deletions > 0) {
            return Decision.hold(deletions + (deletions == 1 ? " document is" : " documents are")
                    + " to be removed. Open Cloud Sync to confirm.");
        }

        List<CloudClient.Change> uploads = proposal.of("UPLOAD");
        List<CloudClient.Change> downloads = proposal.of("DOWNLOAD");
        if (uploads.isEmpty() && downloads.isEmpty()) {
            /*
             * Changes that are none of the above. Held rather than applied, because a plan
             * this does not recognise is a plan it cannot say is safe - and a new action
             * added to the server should not start happening here by default.
             */
            return Decision.hold("");
        }

        return Decision.apply(describe(uploads.size(), downloads.size()));
    }

    private static String describe(int uploaded, int downloaded) {
        if (uploaded > 0 && downloaded > 0) {
            return "Synced " + uploaded + " up, " + downloaded + " down.";
        }
        if (uploaded > 0) {
            return "Sent " + uploaded + (uploaded == 1 ? " document." : " documents.");
        }
        return "Received " + downloaded + (downloaded == 1 ? " document." : " documents.");
    }
}
