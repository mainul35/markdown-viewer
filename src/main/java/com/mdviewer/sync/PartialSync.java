package com.mdviewer.sync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Working out what "sync this" means when somebody points at one thing in the tree.
 *
 * <p>Separate from the dialog and from the controller because it is the part with a right
 * answer: which of the workspace's files a click on a folder covers, and what a click on a
 * single file has to carry with it.
 */
public final class PartialSync {

    private PartialSync() {
    }

    /**
     * The paths a click on {@code target} means, relative to the workspace root.
     *
     * <p>A file means that file, at its full path - {@code notes/2026/plan.md} arrives in the
     * cloud under {@code notes/2026/plan.md}, so the folders around it exist there too. That
     * is not incidental: a document's path is part of what it is, and flattening it into the
     * root would put two files called {@code index.md} on top of each other.
     *
     * <p>A folder means everything the scanner finds beneath it. Deciding that here, from the
     * scan, rather than by walking the tree again means a folder sync covers exactly what a
     * full sync would have covered - the same ignores, the same size limits, the same rules
     * about what is a document and what is not.
     */
    public static Set<String> pathsUnder(Path root, Path target, WorkspaceScanner.Scan scan)
            throws IOException {
        String prefix = WorkspaceScanner.relative(root.toRealPath(), target.toRealPath());
        Set<String> wanted = new LinkedHashSet<>();

        for (FileState file : scan.files()) {
            if (covers(prefix, file.path())) {
                wanted.add(file.path());
            }
        }
        return wanted;
    }

    /**
     * Whether a path is the target or sits underneath it.
     *
     * <p>The trailing separator matters. Without it, a folder called {@code notes} would also
     * claim {@code notes-archive/old.md}, which is a different folder that merely starts with
     * the same letters.
     */
    static boolean covers(String prefix, String path) {
        if (prefix.isEmpty()) {
            return true;   // The workspace root itself: everything.
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
