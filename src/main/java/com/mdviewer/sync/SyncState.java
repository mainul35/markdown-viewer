package com.mdviewer.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this machine and the cloud last agreed about one workspace.
 *
 * <p>The third leg of every reconciliation: the revision last synced, and the hash held
 * for each path at that revision. Comparing local against remote can tell you they
 * differ; only this can tell you <em>who moved</em>, and therefore whether something is an
 * edit to upload, an edit to download, or a genuine conflict.
 *
 * <p>Stored under {@code ~/.mdviewer/sync/} rather than inside the workspace, and that is
 * not a filing preference. A state file inside the folder would be picked up by the very
 * scan it exists to inform - a bookkeeping file that syncs itself, changes on every sync,
 * and so guarantees there is always something to sync.
 */
public final class SyncState {

    private final Path file;
    private String workspaceId = "";
    private long revision = 0;
    private final Map<String, String> base = new LinkedHashMap<>();

    private SyncState(Path file) {
        this.file = file;
    }

    /**
     * The state for a workspace folder.
     *
     * <p>Named by a hash of the real path. Two folders can share a name - {@code docs} is
     * not unusual - and a name would collide where a path cannot.
     */
    public static SyncState forWorkspace(Path root) throws IOException {
        Path home = Path.of(System.getProperty("user.home", "."), ".mdviewer", "sync");
        Files.createDirectories(home);
        String key = WorkspaceScanner.sha256(
                root.toRealPath().toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        SyncState state = new SyncState(home.resolve(key + ".properties"));
        state.load();
        return state;
    }

    public String workspaceId() {
        return workspaceId;
    }

    public long revision() {
        return revision;
    }

    /** Path to the hash agreed at {@link #revision()}. Absent means "not there then". */
    public Map<String, String> base() {
        return Map.copyOf(base);
    }

    public boolean isEnrolled() {
        return !workspaceId.isBlank();
    }

    /**
     * Links this folder to a cloud workspace, and writes it down.
     *
     * <p>Saved immediately rather than as a side effect of the first successful sync. The
     * workspace exists on the server the moment it is created, so a link held only in
     * memory is lost if the sync that follows fails or the dialog is closed - leaving a
     * workspace in the cloud that this folder has no record of, and a second attempt
     * refused because the name is already taken.
     */
    public void enrol(String id) throws IOException {
        this.workspaceId = id;
        save();
    }

    /**
     * Records a completed sync.
     *
     * <p>Called only after the whole round succeeded. Recording a new base while files are
     * still half-written would tell the next sync that the two sides agree about content
     * one of them never received - and the difference would then look like a local edit,
     * which is how a download turns into an upload of the old version.
     */
    public void agreed(long newRevision, Map<String, String> agreedFiles) throws IOException {
        this.revision = newRevision;
        base.clear();
        base.putAll(agreedFiles);
        save();
    }

    public void forget() throws IOException {
        workspaceId = "";
        revision = 0;
        base.clear();
        Files.deleteIfExists(file);
    }

    // ------------------------------------------------------------------- disk

    /*
     * A properties file rather than JSON: this application already reads its AI settings
     * from one, there is no JSON library on the desktop classpath for this, and the content
     * is a workspace id, a number and a flat list of path-to-hash. Reaching for a
     * dependency to store three kinds of string would be the wrong trade.
     *
     * Paths are values, not keys, so a path containing '=' or ':' cannot break the file.
     */
    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String kind = line.substring(0, tab);
            String value = line.substring(tab + 1);
            switch (kind) {
                case "workspace" -> workspaceId = value;
                case "revision" -> revision = parse(value);
                case "file" -> {
                    int split = value.indexOf('\t');
                    if (split > 0) {
                        base.put(value.substring(split + 1), value.substring(0, split));
                    }
                }
                default -> { }
            }
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void save() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# MDViewer sync state. Deleting this file makes the next sync treat\n")
                .append("# every difference as new on both sides, which is safe but noisy.\n");
        out.append("workspace\t").append(workspaceId).append('\n');
        out.append("revision\t").append(revision).append('\n');
        base.forEach((path, hash) -> out.append("file\t").append(hash).append('\t').append(path).append('\n'));

        // Written beside and moved into place, so an interrupted write cannot leave a
        // half-truncated base - which would silently turn known files into new ones.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
