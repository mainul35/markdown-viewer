package com.mdviewer.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/** Decides what belongs in a markdown workspace: which files, and which folders. */
public final class MarkdownFiles {

    /** Extensions the editor can open; anything else is not shown in the explorer. */
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown", "txt");

    /**
     * Budget for {@link #containsMarkdown}. A folder is only listed when it holds markdown
     * somewhere inside, which means answering that question for every sibling directory on
     * expand. Build and dependency folders ({@code target/}, {@code node_modules/}) can hold
     * tens of thousands of entries, so the search stops early rather than freezing the UI;
     * a folder that large with no markdown in the first {@value #MAX_SCAN_ENTRIES} entries
     * is treated as having none.
     */
    private static final int MAX_SCAN_ENTRIES = 2000;
    private static final int MAX_SCAN_DEPTH = 6;

    private MarkdownFiles() {
    }

    public static boolean isMarkdown(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0
                && MARKDOWN_EXTENSIONS.contains(text.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Dot-files and dot-directories are noise in a document workspace. */
    public static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /** True if {@code dir} holds a markdown file at any depth within the scan budget. */
    public static boolean containsMarkdown(Path dir) {
        Deque<Entry> pending = new ArrayDeque<>();
        pending.push(new Entry(dir, 0));
        int visited = 0;

        while (!pending.isEmpty() && visited < MAX_SCAN_ENTRIES) {
            Entry current = pending.pop();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current.path())) {
                for (Path child : stream) {
                    if (++visited > MAX_SCAN_ENTRIES) {
                        return false;
                    }
                    if (isHidden(child)) {
                        continue;
                    }
                    if (Files.isDirectory(child)) {
                        if (current.depth() + 1 < MAX_SCAN_DEPTH) {
                            pending.push(new Entry(child, current.depth() + 1));
                        }
                    } else if (isMarkdown(child)) {
                        return true;
                    }
                }
            } catch (IOException | SecurityException e) {
                // Unreadable directory contributes nothing.
            }
        }
        return false;
    }

    private record Entry(Path path, int depth) {}
}
