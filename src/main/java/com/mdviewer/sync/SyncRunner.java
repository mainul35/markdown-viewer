package com.mdviewer.sync;

import com.mdviewer.service.Trash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Carries out one sync round for one workspace.
 *
 * <p>Two calls, deliberately: {@link #plan()} works out what would happen and returns it,
 * and {@link #apply} does it. Nothing moves between them without someone saying so. A
 * sync that starts writing the moment it is asked what it would do is a sync nobody can
 * safely press.
 */
public final class SyncRunner {

    private final Path root;
    private final CloudClient cloud;
    private final SyncState state;
    private final Consumer<String> progress;

    private WorkspaceScanner.Scan lastScan;

    public SyncRunner(Path root, CloudClient cloud, SyncState state, Consumer<String> progress) {
        this.root = root;
        this.cloud = cloud;
        this.state = state;
        this.progress = progress == null ? message -> { } : progress;
    }

    /** What was found locally and what the server says about it. */
    public record Proposal(WorkspaceScanner.Scan scan, CloudClient.Plan plan) {

        public List<CloudClient.Change> of(String action) {
            return plan.changes().stream().filter(c -> c.action().equals(action)).toList();
        }

        public List<CloudClient.Change> conflicts() {
            return of("CONFLICT");
        }

        public boolean isUpToDate() {
            return plan.changes().stream().allMatch(c -> c.action().equals("CONVERGED"));
        }
    }

    /** What the sync actually did, for reporting afterwards. */
    public record Outcome(long revision, int uploaded, int downloaded, int deletedLocally,
                          int deletedRemotely, List<String> conflictFiles) { }

    /**
     * Plans a sync for a folder that is already linked to a cloud workspace.
     *
     * <p>Enrolment is not done here, and that is the point. Deciding which cloud workspace
     * a folder belongs to is the one question this code cannot answer: a folder called
     * {@code docs} might be the same one from another machine, or an unrelated project that
     * happens to share a name, and joining the wrong one merges two sets of documents that
     * were never meant to meet. The reader answers it once, in the dialog, and it is
     * remembered.
     *
     * @throws NotEnrolledException if the folder has not been linked yet
     */
    public Proposal plan() throws IOException {
        if (!state.isEnrolled()) {
            throw new NotEnrolledException();
        }
        progress.accept("Reading the workspace...");
        lastScan = WorkspaceScanner.scan(root);

        progress.accept("Asking " + cloud.host() + " what would change...");
        CloudClient.Plan plan = cloud.plan(state.workspaceId(), state.revision(),
                state.base(), lastScan.files());
        return new Proposal(lastScan, plan);
    }

    /**
     * Links this folder to a cloud workspace and remembers it.
     *
     * <p>Linking to one that already has documents is a merge, and an intended one: the
     * next plan offers everything in it as a download and everything here as an upload, so
     * the two sets join. Nothing is overwritten - anything that differs on both sides comes
     * through as a conflict with both versions kept.
     */
    public void link(String workspaceId) {
        state.enrol(workspaceId);
    }

    /** Creates a new cloud workspace under {@code name} and links this folder to it. */
    public String createAndLink(String name) throws IOException {
        String id = cloud.createWorkspace(name);
        state.enrol(id);
        return id;
    }

    /** The folder's own name, offered as the default when creating a workspace. */
    public String suggestedName() {
        return root.getFileName().toString();
    }

    public static class NotEnrolledException extends IOException {
        public NotEnrolledException() {
            super("this folder is not linked to a cloud workspace yet");
        }
    }

    /**
     * Applies a proposal.
     *
     * <p>Ordered so that a failure part-way through leaves the workspace readable rather
     * than half-rewritten. Uploads first, because they cannot damage anything local;
     * downloads next; local deletions last, since they are the only step that removes
     * something someone might still want.
     *
     * <p>The new base is recorded only after all of it succeeded. Writing it earlier would
     * tell the next sync that both sides agree about content one of them never received,
     * and that difference would then read as a local edit - which is how a download turns
     * into an upload of the version you were trying to replace.
     */
    public Outcome apply(Proposal proposal) throws IOException {
        List<String> conflicts = new ArrayList<>();
        int uploaded = 0;
        int downloaded = 0;
        int deletedLocally = 0;

        /*
         * What the workspace will contain when this is over - which is what the commit has
         * to say, because the server reads that list as the whole truth and treats every
         * path missing from it as deleted.
         *
         * Built from the plan rather than from the local scan alone. A machine syncing for
         * the first time has nothing on disk yet, so committing what it currently holds
         * would tell the server the workspace is empty and soft-delete every document in
         * it. That is exactly what happened the first time this ran, and it is the reason
         * this list is assembled here rather than being "the files I can see".
         */
        Map<String, FileState> intended = new LinkedHashMap<>();
        for (FileState file : proposal.scan().files()) {
            intended.put(file.path(), file);
        }
        for (CloudClient.Change change : proposal.of("DELETE_REMOTE")) {
            intended.remove(change.path());
        }
        // A file the cloud deleted and we have not touched goes locally too, so it must
        // not be sent back up - that would resurrect it on every machine in turn.
        for (CloudClient.Change change : proposal.of("DELETE_LOCAL")) {
            intended.remove(change.path());
        }
        // About to arrive, so it belongs in the list. Its content is already on the server
        // by definition - that is where it is coming from.
        for (CloudClient.Change change : proposal.of("DOWNLOAD")) {
            if (change.remoteHash() != null && !change.remoteHash().isBlank()) {
                intended.put(change.path(),
                        new FileState(change.path(), change.remoteHash(), change.bytes()));
            }
        }

        /*
         * Conflicts. These only ever add a file: the local copy stays exactly where it is
         * and the cloud version lands beside it. Nothing is merged, ever - two edited
         * paragraphs interleaved read like prose and say what neither author wrote.
         *
         * The local version is what the conflicted path commits as, which is not the
         * conflict being settled in local's favour: the cloud version is written to disk
         * under its own name and committed too, so after this both are in the workspace
         * and both are in the cloud.
         */
        List<String> conflictHashesToUpload = new ArrayList<>();
        for (CloudClient.Change change : proposal.conflicts()) {
            if (change.remoteHash() == null || change.remoteHash().isBlank()) {
                // A delete here against an edit there, or the reverse. The edit survives
                // by doing nothing at all: the next plan offers it again.
                conflicts.add(change.path() + " (" + change.reason() + ")");
                intended.remove(change.path());
                continue;
            }
            Path beside = conflictPathFor(change.path());
            progress.accept("Keeping both versions of " + change.path());
            byte[] remote = cloud.getBlob(change.remoteHash());
            write(beside, remote);
            String besidePath = WorkspaceScanner.relative(root.toRealPath(), beside);
            conflicts.add(besidePath);
            intended.put(besidePath,
                    new FileState(besidePath, change.remoteHash(), remote.length));
            // The local version at the conflicted path has never been sent - the server
            // only listed uploads for changes it called UPLOAD - so committing it without
            // this would be refused for content that was never stored.
            if (change.localHash() != null && !change.localHash().isBlank()) {
                conflictHashesToUpload.add(change.localHash());
            }
        }

        List<String> toUpload = new ArrayList<>(proposal.plan().blobsToUpload());
        for (String hash : conflictHashesToUpload) {
            if (!toUpload.contains(hash)) {
                toUpload.add(hash);
            }
        }
        for (String hash : toUpload) {
            FileState file = findByHash(proposal.scan(), hash);
            if (file == null) {
                continue;
            }
            progress.accept("Sending " + file.path());
            cloud.putBlob(hash, Files.readAllBytes(root.resolve(file.path())));
            uploaded++;
        }

        // Downloads land before the commit, so a failure to write leaves the cloud saying
        // what it said before rather than claiming this machine has content it does not.
        for (CloudClient.Change change : proposal.of("DOWNLOAD")) {
            if (change.remoteHash() == null || change.remoteHash().isBlank()) {
                continue;
            }
            progress.accept("Receiving " + change.path());
            write(safeResolve(change.path()), cloud.getBlob(change.remoteHash()));
            downloaded++;
        }

        progress.accept("Committing...");
        long revision = cloud.commit(state.workspaceId(), proposal.plan().revision(),
                List.copyOf(intended.values()));

        for (CloudClient.Change change : proposal.of("DELETE_LOCAL")) {
            Path target = safeResolve(change.path());
            if (Files.exists(target)) {
                progress.accept("Removing " + change.path());
                // To the recycle bin, not deleted. The planner has already established
                // this file is unchanged since the last sync, so removing it is correct -
                // but "correct" and "unrecoverable" should not be the same operation on
                // someone's documents.
                if (!Trash.moveToTrash(target)) {
                    Files.delete(target);
                }
                deletedLocally++;
            }
        }

        // The agreed base is exactly the committed list: after this, both sides hold that
        // content at that revision, which is the whole meaning of a base.
        Map<String, String> agreed = new LinkedHashMap<>();
        intended.forEach((path, file) -> agreed.put(path, file.hash()));
        state.agreed(revision, agreed);

        return new Outcome(revision, uploaded, downloaded, deletedLocally,
                proposal.of("DELETE_REMOTE").size(), conflicts);
    }

    // ---------------------------------------------------------------- helpers

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    /**
     * Where the cloud's version of a conflicted document goes.
     *
     * <p>Beside the original, named so it sorts next to it and says what it is. A reader
     * opens both, keeps what they want and deletes the other - which is a two-minute job
     * with the documents in front of them and impossible after a merge.
     */
    Path conflictPathFor(String path) {
        int dot = path.lastIndexOf('.');
        String stem = dot < 0 ? path : path.substring(0, dot);
        String extension = dot < 0 ? "" : path.substring(dot);
        String stamp = STAMP.format(Instant.now());
        Path candidate = root.resolve(stem + ".conflict-" + stamp + extension);
        // Two conflicts on the same document in the same minute must not overwrite each
        // other - which would make the conflict handler itself lose a version.
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = root.resolve(stem + ".conflict-" + stamp + "-" + n++ + extension);
        }
        return candidate;
    }

    /**
     * A workspace path resolved to a real one, refusing anything that climbs out.
     *
     * <p>The path came from a server response. Writing {@code ../../.ssh/authorized_keys}
     * because a reply said so is not a thing this will do, however unlikely the reply.
     */
    private Path safeResolve(String path) throws IOException {
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root.toRealPath())) {
            throw new IOException("refusing a path that leaves the workspace: " + path);
        }
        return target;
    }

    private void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        // Written beside and moved into place: an interrupted download must not leave a
        // truncated document where a whole one was.
        Path temporary = target.resolveSibling(target.getFileName() + ".mdv-part");
        Files.write(temporary, content);
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static FileState findByHash(WorkspaceScanner.Scan scan, String hash) {
        return scan.files().stream().filter(f -> f.hash().equalsIgnoreCase(hash))
                .findFirst().orElse(null);
    }
}
