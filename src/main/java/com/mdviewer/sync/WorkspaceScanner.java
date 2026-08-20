package com.mdviewer.sync;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a workspace folder into the list a sync plan is computed from.
 *
 * <p>What it takes is deliberately narrow. A workspace is a folder someone chose to open
 * in a Markdown reader, and it is very often a source repository - so scanning everything
 * under it would upload a {@code node_modules} tree, a {@code target} directory and a
 * {@code .git} history to a 2 GB quota, none of which anybody asked for and none of which
 * this application can even open.
 *
 * <p>Documents and the images they show, then. Everything else stays on the machine.
 */
public final class WorkspaceScanner {

    /** What a Markdown reader can actually display. */
    private static final Set<String> DOCUMENT_TYPES = Set.of("md", "markdown", "mdown", "mkd");

    /**
     * Images, because a document that loses its screenshots on the way to another machine
     * has not really arrived.
     */
    private static final Set<String> ASSET_TYPES = Set.of("png", "jpg", "jpeg", "gif", "svg", "webp");

    /**
     * Directories never walked into.
     *
     * <p>Not a performance measure. A {@code .git} directory holds every version of every
     * file that was ever committed, so syncing one would put a repository's whole history
     * into a document store as loose blobs - and {@code node_modules} would fill a free
     * tier from a single project.
     */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "dist", "out",
            ".next", ".idea", ".vscode", ".gradle", "__pycache__", ".venv", "venv");

    /** Above this, a file is reported rather than uploaded. */
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    /** What was found, and what was deliberately left behind. */
    public record Scan(List<FileState> files, List<String> skipped) {

        public long totalBytes() {
            return files.stream().mapToLong(FileState::size).sum();
        }
    }

    private WorkspaceScanner() {
    }

    public static Scan scan(Path root) throws IOException {
        List<FileState> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Path base = root.toRealPath();

        Files.walkFileTree(base, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (directory.equals(base)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = directory.getFileName().toString();
                if (SKIP_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT))
                        || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                /* A symlink is not followed, and neither is anything it points at. A link
                   in a workspace can address any file on the machine, and uploading
                   whatever it happens to point at is not a decision this should make on
                   someone's behalf. */
                if (Files.isSymbolicLink(file)) {
                    skipped.add(relative(base, file) + " (a link, not followed)");
                    return FileVisitResult.CONTINUE;
                }
                String extension = extensionOf(file.getFileName().toString());
                boolean wanted = DOCUMENT_TYPES.contains(extension) || ASSET_TYPES.contains(extension);
                if (!wanted) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    long size = Files.size(file);
                    if (size > MAX_FILE_BYTES) {
                        skipped.add(relative(base, file) + " (" + (size / 1024 / 1024) + " MB, too large)");
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(new FileState(relative(base, file), sha256(Files.readAllBytes(file)), size));
                } catch (IOException e) {
                    // Named rather than swallowed: a file that could not be read is a file
                    // that will not be synced, and finding that out later is worse.
                    skipped.add(relative(base, file) + " (" + e.getMessage() + ")");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                skipped.add(relative(base, file) + " (" + e.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort((a, b) -> a.path().compareTo(b.path()));
        return new Scan(List.copyOf(files), List.copyOf(skipped));
    }

    /**
     * Forward slashes, always.
     *
     * <p>The path is the identity of a document across machines. A file synced from Windows
     * as {@code notes\api.md} and looked for on Linux as {@code notes/api.md} is two
     * documents as far as reconciliation is concerned, and the second machine would
     * download a duplicate of everything it already had.
     */
    public static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** Whether a path is inside {@code root} once both are resolved. */
    public static boolean isInside(Path root, Path candidate) throws IOException {
        Path base = root.toRealPath();
        Path target = candidate.toAbsolutePath().normalize();
        return target.startsWith(base) && !Files.isSymbolicLink(candidate);
    }

    static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }
}
