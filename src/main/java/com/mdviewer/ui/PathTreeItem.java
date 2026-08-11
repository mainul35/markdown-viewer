package com.mdviewer.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A file-tree node that reads its directory only when it is first asked for children.
 *
 * <p>Workspaces routinely contain directories like {@code target/} or {@code node_modules/}
 * with tens of thousands of entries; walking those eagerly would stall the UI thread on
 * every workspace that gets opened.
 */
public final class PathTreeItem extends TreeItem<Path> {

    /** Directories first, then case-insensitive by name - the listing order. */
    private static final Comparator<TreeItem<Path>> ORDER = Comparator
            .comparing((TreeItem<Path> t) -> t instanceof PathTreeItem p && p.isDirectory() ? 0 : 1)
            .thenComparing(t -> t.getValue().getFileName().toString(),
                    String.CASE_INSENSITIVE_ORDER);

    private final boolean directory;
    private boolean childrenLoaded;
    /** True for a child {@link #ensureChild} surfaced past the listing filter. */
    private boolean surfaced;

    public PathTreeItem(Path path) {
        super(path);
        this.directory = Files.isDirectory(path);
    }

    public boolean isDirectory() {
        return directory;
    }

    /** True once this directory's listing has been read; false means nothing is cached yet. */
    boolean isLoaded() {
        return childrenLoaded;
    }

    @Override
    public boolean isLeaf() {
        // Deliberately not the default implementation: that one inspects getChildren(),
        // which would load every directory just to decide whether to draw a disclosure arrow.
        return !directory;
    }

    @Override
    public ObservableList<TreeItem<Path>> getChildren() {
        if (!childrenLoaded) {
            childrenLoaded = true; // Set first: loadChildren() must not re-enter this branch.
            super.getChildren().setAll(readDirectory());
        }
        return super.getChildren();
    }

    /**
     * Returns the child item for {@code childPath}, inserting one if the listing filter
     * left it out.
     *
     * <p>Needed to reveal a document reached by following a link: the target may sit under
     * a folder the explorer hides, such as {@code .claude/rules/}. A file the user has
     * actually opened is not the noise the filter exists to remove, so it gets surfaced -
     * but only that path, not the whole folder.
     *
     * @return the child item, or null if {@code childPath} does not exist on disk
     */
    public PathTreeItem ensureChild(Path childPath) {
        for (TreeItem<Path> child : getChildren()) {
            if (child.getValue().equals(childPath)) {
                return (PathTreeItem) child;
            }
        }
        if (!Files.exists(childPath)) {
            return null;
        }
        PathTreeItem item = new PathTreeItem(childPath);
        // Marked so a later sync does not quietly drop it again: the listing filter would
        // not have produced this child, and re-reading the directory must not undo the
        // reveal that put it here.
        item.surfaced = true;
        int index = 0;
        while (index < super.getChildren().size()
                && compare(super.getChildren().get(index).getValue(), childPath) < 0) {
            index++;
        }
        super.getChildren().add(index, item);
        return item;
    }

    /** Directories first, then case-insensitive by name - the listing order. */
    private static int compare(Path a, Path b) {
        int byType = Boolean.compare(!Files.isDirectory(a), !Files.isDirectory(b));
        return byType != 0 ? byType
                : String.CASE_INSENSITIVE_ORDER.compare(
                        a.getFileName().toString(), b.getFileName().toString());
    }

    /** Drops cached children so the next access re-reads the directory. */
    public void invalidate() {
        childrenLoaded = false;
        super.getChildren().clear();
    }

    // ------------------------------------------------------------ disk sync

    /**
     * Records the directories whose listings are currently cached, deepest last.
     *
     * <p>Only loaded directories: an unexpanded folder has nothing cached that could be
     * stale, and reading it here would defeat the lazy loading this class exists for.
     */
    void collectLoadedDirectories(List<Path> out) {
        if (!directory || !childrenLoaded) {
            return;
        }
        out.add(getValue());
        for (TreeItem<Path> child : super.getChildren()) {
            if (child instanceof PathTreeItem item) {
                item.collectLoadedDirectories(out);
            }
        }
    }

    /**
     * Merges a listing read elsewhere into the cached children, in place.
     *
     * <p>Deliberately not {@link #invalidate}: dropping and re-reading collapses every
     * folder below this one and loses the user's place in the tree. A periodic sync that
     * did that would be worse than no sync at all. Items that survive keep their identity,
     * so their expansion state and their own loaded children survive with them.
     *
     * @param listings directory contents by path, as produced by {@link #readEntries}
     * @return true if anything was added or removed anywhere in this subtree
     */
    boolean applyListings(Map<Path, List<Path>> listings) {
        if (!directory || !childrenLoaded) {
            return false;
        }
        boolean changed = false;
        List<Path> entries = listings.get(getValue());
        if (entries != null) {
            Map<Path, PathTreeItem> existing = new LinkedHashMap<>();
            for (TreeItem<Path> child : super.getChildren()) {
                if (child instanceof PathTreeItem item) {
                    existing.put(item.getValue(), item);
                }
            }

            List<TreeItem<Path>> merged = new ArrayList<>(entries.size());
            for (Path entry : entries) {
                PathTreeItem item = existing.remove(entry);
                if (item == null) {
                    item = new PathTreeItem(entry);
                    changed = true;
                }
                merged.add(item);
            }
            // Whatever the listing did not account for was either surfaced past the filter
            // by a reveal - keep it while it exists - or is genuinely gone from disk.
            for (PathTreeItem leftover : existing.values()) {
                if (leftover.surfaced && Files.exists(leftover.getValue())) {
                    merged.add(leftover);
                } else {
                    changed = true;
                }
            }
            if (changed) {
                merged.sort(ORDER);
                super.getChildren().setAll(merged);
            }
        }

        for (TreeItem<Path> child : super.getChildren()) {
            if (child instanceof PathTreeItem item && item.applyListings(listings)) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Reads one directory through the explorer's filter, sorted as the tree shows it.
     *
     * <p>Static and free of any node state so a background thread can call it: the scan
     * behind it can touch thousands of entries through
     * {@link MarkdownFiles#containsMarkdown}, which is far too much to do on the FX thread
     * on a timer.
     */
    public static List<Path> readEntries(Path directory) {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (MarkdownFiles.isHidden(entry)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    if (MarkdownFiles.containsMarkdown(entry)) {
                        entries.add(entry);
                    }
                } else if (MarkdownFiles.isMarkdown(entry)) {
                    entries.add(entry);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of(); // Unreadable directory contributes nothing.
        }
        entries.sort(Comparator
                .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private List<TreeItem<Path>> readDirectory() {
        if (!directory) {
            return List.of();
        }
        List<Path> entries = readEntries(getValue());
        List<TreeItem<Path>> items = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            items.add(new PathTreeItem(entry));
        }
        return items;
    }
}
