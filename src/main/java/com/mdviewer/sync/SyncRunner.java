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
    public void link(String workspaceId) throws IOException {
        state.enrol(workspaceId);
    }

    /** Creates a new cloud workspace under {@code name} and links this folder to it. */
    public String createAndLink(String name) throws IOException {
        String id = cloud.createWorkspace(name);
        state.enrol(id);
        return id;
    }

    /** What one deliberate push sent, and what it left alone. */
    public record Push(long revision, int sent, int alreadyThere, List<String> paths) {

        public int total() {
            return sent + alreadyThere;
        }
    }

    /**
     * Sends one file, or everything under one folder, and touches nothing else.
     *
     * <p>Asked for from the file tree, where somebody points at a document and says put that
     * in the cloud. It is not a sync: nothing is downloaded, nothing is deleted, and no
     * conflict is settled. It adds or updates the paths named and leaves the rest of the
     * workspace exactly as it was.
     *
     * <p><strong>Which is harder than it sounds, because a commit is read as the whole
     * truth.</strong> The server soft-deletes every path missing from the list it is given,
     * so committing "the two files I chose" would delete every other document in the
     * workspace. The list sent here is therefore the workspace as the server currently
     * describes it, with the chosen files laid over the top - a plan against an empty base
     * is what asks for that description, since "what would I download if I had nothing" is
     * exactly the list of what is up there.
     *
     * @param wanted paths relative to the workspace root, in the scanner's own form
     */
    public Push push(java.util.Set<String> wanted) throws IOException {
        if (!state.isEnrolled()) {
            throw new NotEnrolledException();
        }

        progress.accept("Reading the workspace...");
        WorkspaceScanner.Scan scan = WorkspaceScanner.scan(root);
        List<FileState> chosen = scan.files().stream()
                .filter(file -> wanted.contains(file.path()))
                .toList();

        if (chosen.isEmpty()) {
            throw new IOException("there is nothing here that can be synced");
        }

        /*
         * What the server holds right now. Asked for as a plan with an empty base and no
         * local files, which is not a trick: every remote document comes back as a download,
         * and that list is what has to survive the commit.
         */
        progress.accept("Asking " + cloud.host() + " what it already has...");
        CloudClient.Plan remote = cloud.plan(state.workspaceId(), 0, Map.of(), List.of());

        Map<String, FileState> intended = whatTheServerHolds(remote);
        Map<String, String> remoteHashes = new LinkedHashMap<>();
        intended.forEach((path, file) -> remoteHashes.put(path, file.hash()));

        int sent = 0;
        int alreadyThere = 0;
        for (FileState file : chosen) {
            /*
             * Content-addressed storage makes the skip safe: the same hash at the same path
             * is the same bytes, so there is nothing to send. It is worth checking because
             * "sync this folder" on a folder that is already synced is a common thing to do,
             * and it should cost one request rather than fifty uploads.
             */
            if (file.hash().equals(remoteHashes.get(file.path()))) {
                alreadyThere++;
            } else {
                progress.accept("Sending " + file.path());
                cloud.putBlob(file.hash(), Files.readAllBytes(root.resolve(file.path())));
                sent++;
            }
            intended.put(file.path(), file);
        }

        progress.accept("Committing...");
        long revision = cloud.commit(state.workspaceId(), remote.revision(),
                List.copyOf(intended.values()));

        /*
         * Only the chosen paths join the agreed base, never the whole intended list.
         *
         * The base is what this machine and the server have agreed about, and the next plan
         * reads a path in the base that is missing from disk as "deleted here since we
         * agreed". Recording agreement about documents this machine has never seen would
         * therefore make the following full sync offer to delete every one of them - a
         * partial push quietly arming a full delete.
         */
        state.agreed(revision, agreedAfterPush(state.base(), chosen));

        return new Push(revision, sent, alreadyThere,
                chosen.stream().map(FileState::path).toList());
    }

    /**
     * The workspace as the server currently describes it.
     *
     * <p>Read from a plan asked with an empty base, where every remote document comes back
     * as a download - "what would I take if I had nothing" being exactly the list of what is
     * up there.
     *
     * <p>This is the half of a partial push that has to be right. A commit is read as the
     * whole truth and every path missing from it is soft-deleted, so a push that sent only
     * the chosen files would delete every other document in the workspace.
     */
    static Map<String, FileState> whatTheServerHolds(CloudClient.Plan remote) {
        Map<String, FileState> holding = new LinkedHashMap<>();
        for (CloudClient.Change change : remote.changes()) {
            if ("DOWNLOAD".equals(change.action())
                    && change.remoteHash() != null && !change.remoteHash().isBlank()) {
                holding.put(change.path(),
                        new FileState(change.path(), change.remoteHash(), change.bytes()));
            }
        }
        return holding;
    }

    /**
     * What this machine and the server have agreed about, after a push.
     *
     * <p>The chosen paths join what was already agreed, and nothing else does - in
     * particular not the rest of the commit list.
     *
     * <p>The base is read by the next plan as "what we both had last time", so a path in it
     * that is missing from disk means "deleted here since we agreed". Recording agreement
     * about documents this machine has never seen would therefore make the following full
     * sync offer to delete every one of them: a partial push quietly arming a full delete.
     */
    static Map<String, String> agreedAfterPush(Map<String, String> base, List<FileState> chosen) {
        Map<String, String> agreed = new LinkedHashMap<>(base);
        for (FileState file : chosen) {
            agreed.put(file.path(), file.hash());
        }
        return agreed;
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
            Path beside = conflictPathFor(safeResolve(change.path()));
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
    Path conflictPathFor(Path original) throws IOException {
        /*
         * Checked here as well as at the call site. Composing two correct steps is only
         * correct while both are still there, and this one is a single line away from being
         * dropped by someone tidying up - so the rule lives with the method that would break.
         */
        if (!original.startsWith(root.toRealPath())) {
            throw new IOException("refusing a conflict copy outside the workspace: " + original);
        }
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        String stamp = STAMP.format(Instant.now());
        /*
         * Built from a path that safeResolve has already vouched for, and beside it rather
         * than resolved afresh from the server's string. Resolving the raw path here was
         * the one place a reply could name somewhere outside the workspace and be believed -
         * every other use of a server-supplied path goes through that check.
         */
        Path candidate = original.resolveSibling(stem + ".conflict-" + stamp + extension);
        // Two conflicts on the same document in the same minute must not overwrite each
        // other - which would make the conflict handler itself lose a version.
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = original.resolveSibling(stem + ".conflict-" + stamp + "-" + n++ + extension);
        }
        return candidate;
    }

    /**
     * A workspace path resolved to a real one, refusing anything that climbs out.
     *
     * <p>The path came from a server response. Writing {@code ../../.ssh/authorized_keys}
     * because a reply said so is not a thing this will do, however unlikely the reply.
     */
    /** Package-private so the containment rule can be asserted directly; it is the one
     * guard between a server-supplied string and a write outside the workspace. */
    Path safeResolve(String path) throws IOException {
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
