package com.mdviewer.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and rewrites a GFM table in its Markdown form.
 *
 * <p>Exists so a cell can be edited in the preview without the editor ever being touched
 * directly. Everything here is a pure function over the table's own text, which is what
 * makes it testable without a UI - and a table rewrite is the one edit in this app that
 * can silently destroy a whole block if it is even slightly wrong.
 *
 * <p>The row index counts the header as row 0, so body rows start at 1. That matches what
 * the rendered table shows and what the renderer stamps onto each cell.
 */
public final class TableSource {

    private TableSource() {
    }

    /** A parsed table: its cells, its alignment row, and the indent each line carried. */
    public record Table(List<List<String>> rows, List<String> alignments, String indent) {

        public int rowCount() {
            return rows.size();
        }

        public int columnCount() {
            return rows.isEmpty() ? 0 : rows.get(0).size();
        }
    }

    /**
     * Parses the table occupying {@code text}, or null if it is not one.
     *
     * <p>Requires the delimiter row: without it this is not a table, and treating some
     * other pipe-containing text as one would rewrite it into something it never was.
     */
    public static Table parse(String text) {
        if (text == null) {
            return null;
        }
        List<String> lines = text.lines().toList();
        if (lines.size() < 2) {
            return null;
        }
        String indent = lines.get(0).substring(0, lines.get(0).length()
                - lines.get(0).stripLeading().length());

        List<String> alignments = splitCells(lines.get(1));
        if (alignments.isEmpty()) {
            return null;
        }
        for (String alignment : alignments) {
            if (!alignment.strip().matches(":?-{1,}:?")) {
                return null; // Not a delimiter row, so not a table.
            }
        }

        List<List<String>> rows = new ArrayList<>();
        rows.add(splitCells(lines.get(0)));
        for (int i = 2; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                break; // A blank line ends the table.
            }
            rows.add(splitCells(lines.get(i)));
        }
        if (rows.get(0).isEmpty()) {
            return null;
        }
        return new Table(rows, alignments, indent);
    }

    /**
     * The Markdown of one cell, exactly as written, or null if that cell does not exist.
     *
     * <p>The Markdown, not the rendered text. A cell holding {@code `sk_test_...`} shows
     * as styled code in the preview; handing the reader that rendered form to edit and
     * writing it back would silently drop the backticks. What is offered for editing has
     * to be what is actually there.
     */
    public static String cell(String text, int row, int column) {
        Table table = parse(text);
        if (table == null || row < 0 || row >= table.rowCount()) {
            return null;
        }
        List<String> cells = table.rows().get(row);
        return column < 0 || column >= cells.size() ? null : cells.get(column);
    }

    /**
     * Returns the table with one cell replaced, re-serialised with aligned pipes.
     *
     * @param value new cell Markdown; newlines and bare pipes are made safe
     * @return the new table text, or null if the table or cell does not exist
     */
    public static String withCell(String text, int row, int column, String value) {
        Table table = parse(text);
        if (table == null || row < 0 || row >= table.rowCount()) {
            return null;
        }
        List<String> cells = new ArrayList<>(table.rows().get(row));
        if (column < 0 || column >= cells.size()) {
            return null;
        }
        cells.set(column, sanitise(value));
        List<List<String>> rows = new ArrayList<>(table.rows());
        rows.set(row, cells);
        return render(new Table(rows, table.alignments(), table.indent()));
    }

    /**
     * Serialises a table with every column padded to its widest cell.
     *
     * <p>The re-alignment is deliberate rather than incidental: a table edited through the
     * preview is also a table whose source has just been tidied, and ragged pipes are the
     * reason these are hard to read in the editor at all.
     */
    public static String render(Table table) {
        int columns = table.columnCount();
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            // At least three, so the delimiter row is still a legal "---".
            widths[c] = 3;
            for (List<String> row : table.rows()) {
                if (c < row.size()) {
                    widths[c] = Math.max(widths[c], row.get(c).length());
                }
            }
        }

        StringBuilder out = new StringBuilder();
        appendRow(out, table.indent(), table.rows().get(0), widths);
        appendDelimiter(out, table.indent(), table.alignments(), widths);
        for (int r = 1; r < table.rowCount(); r++) {
            appendRow(out, table.indent(), table.rows().get(r), widths);
        }
        // No trailing newline: the caller splices this back over the range it came from.
        return out.substring(0, out.length() - 1);
    }

    private static void appendRow(StringBuilder out, String indent, List<String> cells,
                                  int[] widths) {
        out.append(indent);
        for (int c = 0; c < widths.length; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            out.append("| ").append(cell)
                    .append(" ".repeat(Math.max(0, widths[c] - cell.length()))).append(' ');
        }
        out.append("|\n");
    }

    private static void appendDelimiter(StringBuilder out, String indent,
                                        List<String> alignments, int[] widths) {
        out.append(indent);
        for (int c = 0; c < widths.length; c++) {
            String alignment = c < alignments.size() ? alignments.get(c).strip() : "---";
            boolean left = alignment.startsWith(":");
            boolean right = alignment.endsWith(":");
            int dashes = widths[c] - (left ? 1 : 0) - (right ? 1 : 0);
            out.append("| ")
                    .append(left ? ":" : "")
                    .append("-".repeat(Math.max(1, dashes)))
                    .append(right ? ":" : "")
                    .append(' ');
        }
        out.append("|\n");
    }

    /**
     * Makes a value safe to sit inside a table row.
     *
     * <p>A bare pipe would open a new column and a newline would end the table, so an
     * edit containing either has to be neutralised rather than written through - the
     * alternative is a paste quietly destroying the table it was pasted into.
     */
    private static String sanitise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c == '|' && (i == 0 || value.charAt(i - 1) != '\\')) {
                out.append("\\|");
            } else {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /**
     * Splits a table row on its unescaped pipes, dropping the optional leading and
     * trailing ones.
     */
    private static List<String> splitCells(String line) {
        String text = line.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().strip());

        // A row may be written with or without the outer pipes; both give the same cells.
        if (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }
}
