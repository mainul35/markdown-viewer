package com.mdviewer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The workspaces opened before, remembered across runs in {@code ~/.mdviewer}.
 *
 * <p>Stored as one absolute path per line, most recent first. Deliberately not JSON: the
 * app bundles no JSON library, and a list of paths does not justify hand-rolling a parser
 * or taking a dependency. A line-per-path file is also something a person can read and
 * edit with any text editor, which is the right property for a preferences file.
 *
 * <p>Every operation is best-effort. A history file that cannot be read or written is a
 * missing convenience, never a reason to fail an open or to stop the app starting.
 */
public final class WorkspaceHistory {

    /** Enough to cover "the folders I am working in"; beyond that a list stops being a shortcut. */
    public static final int MAX_ENTRIES = 12;

    private static final String DIRECTORY = ".mdviewer";
    private static final String FILE = "workspaces.txt";

    private final Path file;

    /** Uses {@code ~/.mdviewer/workspaces.txt}. */
    public WorkspaceHistory() {
        this(Path.of(System.getProperty("user.home", "."), DIRECTORY, FILE));
    }

    /** @param file explicit location, for tests that must not touch the real home directory */
    public WorkspaceHistory(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    /**
     * The remembered workspaces, most recent first.
     *
     * <p>Folders that have since been deleted or renamed are dropped on the way out rather
     * than offered and then failed: a menu entry that cannot work is worse than no entry.
     * They stay in the file until the next write, so a folder on a disconnected drive is
     * not forgotten just because it was unavailable once.
     */
    public List<Path> list() {
        List<Path> paths = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Path path = Path.of(trimmed);
                if (Files.isDirectory(path)) {
                    paths.add(path);
                }
            } catch (RuntimeException e) {
                // A malformed line is skipped rather than failing the whole list.
            }
        }
        return paths;
    }

    /** Moves {@code root} to the front, adding it if it was not there. */
    public void record(Path root) {
        if (root == null) {
            return;
        }
        Path normalized = root.toAbsolutePath().normalize();
        // LinkedHashSet keeps insertion order and drops the older duplicate, which is
        // exactly "move to front" without a separate remove step.
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(normalized.toString());
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        write(entries.stream().limit(MAX_ENTRIES).toList());
    }

    public void remove(Path root) {
        if (root == null) {
            return;
        }
        String target = root.toAbsolutePath().normalize().toString();
        List<String> kept = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.equals(target)) {
                kept.add(trimmed);
            }
        }
        write(kept);
    }

    public void clear() {
        write(List.of());
    }

    private List<String> readLines() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void write(List<String> lines) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: could not write " + file + " - " + e);
        }
    }
}
