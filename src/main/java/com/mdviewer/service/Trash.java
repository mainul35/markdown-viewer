package com.mdviewer.service;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes a file or folder, preferring the desktop's recycle bin.
 *
 * <p>Deleting from a document explorer is one click away from deleting the wrong thing,
 * and a viewer has no undo for the file system. Where the platform offers a recycle bin
 * the deletion stays recoverable; only when it does not is the file actually removed.
 */
public final class Trash {

    private Trash() {
    }

    /** @return true if the target is gone, either to the recycle bin or permanently */
    public static boolean moveToTrash(Path target) {
        if (target == null || !Files.exists(target)) {
            return false;
        }
        if (trashSupported()) {
            try {
                if (Desktop.getDesktop().moveToTrash(target.toFile())) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Fall through to a real delete.
            }
        }
        return deletePermanently(target);
    }

    /** True when this platform can recycle rather than destroy. */
    public static boolean trashSupported() {
        try {
            return !Boolean.getBoolean("java.awt.headless")
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean deletePermanently(Path target) {
        try {
            if (!Files.isDirectory(target)) {
                Files.delete(target);
                return true;
            }
            try (Stream<Path> walk = Files.walk(target)) {
                // Deepest first, because a directory has to be empty before it goes.
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
