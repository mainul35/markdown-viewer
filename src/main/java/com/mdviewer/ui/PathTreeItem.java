package com.mdviewer.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A file-tree node that reads its directory only when it is first asked for children.
 *
 * <p>Workspaces routinely contain directories like {@code target/} or {@code node_modules/}
 * with tens of thousands of entries; walking those eagerly would stall the UI thread on
 * every workspace that gets opened.
 */
public final class PathTreeItem extends TreeItem<Path> {

    private final boolean directory;
    private boolean childrenLoaded;

    public PathTreeItem(Path path) {
        super(path);
        this.directory = Files.isDirectory(path);
    }

    public boolean isDirectory() {
        return directory;
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

    /** Drops cached children so the next access re-reads the directory. */
    public void invalidate() {
        childrenLoaded = false;
        super.getChildren().clear();
    }

    private List<TreeItem<Path>> readDirectory() {
        if (!directory) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(getValue())) {
            for (Path entry : stream) {
                if (MarkdownFiles.isHidden(entry)) {
                    continue;
                }
                // The explorer is a markdown workspace, not a file manager: list markdown
                // files, and only those folders with markdown somewhere inside them.
                if (Files.isDirectory(entry)) {
                    if (MarkdownFiles.containsMarkdown(entry)) {
                        entries.add(entry);
                    }
                } else if (MarkdownFiles.isMarkdown(entry)) {
                    entries.add(entry);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of(); // Unreadable directory renders as empty rather than failing.
        }

        entries.sort(Comparator
                .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        List<TreeItem<Path>> items = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            items.add(new PathTreeItem(entry));
        }
        return items;
    }
}
