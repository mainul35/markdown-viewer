package com.mdviewer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown source transformations behind the formatting toolbar.
 *
 * <p>Every operation returns the smallest edit that achieves it rather than a rewritten
 * document, so the editor's undo history stays granular and the caret can be restored
 * precisely. All methods are pure, which is what makes the syntax rules testable without
 * a UI - they are the part most likely to be subtly wrong.
 */
public final class SourceEdits {

    /**
     * A replacement of {@code [start, end)} plus where the selection should end up
     * afterwards, in coordinates of the document as it will be once the edit is applied.
     */
    public record Edit(int start, int end, String replacement, int selectionStart, int selectionEnd) {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^(\\s*)(\\d+)\\.\\s+");
    private static final Pattern BULLET_ITEM = Pattern.compile("^(\\s*)([-*+])\\s+");
    private static final Pattern QUOTE_LINE = Pattern.compile("^(\\s*)>\\s?");

    private SourceEdits() {
    }

    // ----------------------------------------------------------------- inline

    /**
     * Wraps the range in {@code marker}, or unwraps it when the markers are already there.
     * The check looks just outside the range as well, so selecting the word inside
     * {@code **bold**} un-bolds it rather than producing {@code ****bold****}.
     */
    public static Edit toggleInline(String source, int start, int end, String marker) {
        int len = marker.length();
        String selected = source.substring(start, end);

        boolean wrappedOutside = start >= len && end + len <= source.length()
                && source.startsWith(marker, start - len)
                && source.startsWith(marker, end);
        if (wrappedOutside) {
            return new Edit(start - len, end + len, selected, start - len, start - len + selected.length());
        }

        boolean wrappedInside = selected.length() >= 2 * len
                && selected.startsWith(marker) && selected.endsWith(marker);
        if (wrappedInside) {
            String inner = selected.substring(len, selected.length() - len);
            return new Edit(start, end, inner, start, start + inner.length());
        }

        String replacement = marker + selected + marker;
        return new Edit(start, end, replacement, start + len, start + len + selected.length());
    }

    /** Wraps the range as a link, or inserts a placeholder when nothing is selected. */
    public static Edit link(String source, int start, int end, String url) {
        String text = source.substring(start, end);
        if (text.isEmpty()) {
            text = "link text";
        }
        String replacement = "[" + text + "](" + url + ")";
        return new Edit(start, end, replacement, start + 1, start + 1 + text.length());
    }

    // ------------------------------------------------------------------ block

    /** Applies a heading level to the block's first line, or removes it if already set. */
    public static Edit setHeading(String source, int offset, int level) {
        int lineStart = lineStart(source, offset);
        int lineEnd = lineEnd(source, offset);
        String line = source.substring(lineStart, lineEnd);

        Matcher m = HEADING.matcher(line);
        String prefix = "#".repeat(level) + " ";
        if (m.find()) {
            if (m.group(1).length() == level) {
                // Same level again: treat the button as a toggle back to body text.
                return new Edit(lineStart, lineStart + m.end(), "", lineStart, lineStart);
            }
            return new Edit(lineStart, lineStart + m.end(), prefix,
                    lineStart + prefix.length(), lineStart + prefix.length());
        }
        // Strip a list or quote marker first - a line cannot be both.
        int existing = markerLength(line);
        return new Edit(lineStart, lineStart + existing, prefix,
                lineStart + prefix.length(), lineStart + prefix.length());
    }

    /** Toggles "- " on every line of the range. */
    public static Edit toggleBullet(String source, int start, int end) {
        return toggleLines(source, start, end, Kind.BULLET);
    }

    /** Toggles "1. ", renumbering from 1 down the range. */
    public static Edit toggleOrdered(String source, int start, int end) {
        return toggleLines(source, start, end, Kind.ORDERED);
    }

    /** Toggles "> " on every line of the range. */
    public static Edit toggleQuote(String source, int start, int end) {
        return toggleLines(source, start, end, Kind.QUOTE);
    }

    private enum Kind { BULLET, ORDERED, QUOTE }

    /**
     * A line-prefix operation is "add to all" unless every line already has it, in which
     * case it removes - the behaviour people expect from a toggle over a mixed selection.
     */
    private static Edit toggleLines(String source, int start, int end, Kind kind) {
        int from = lineStart(source, start);
        int to = lineEnd(source, Math.max(end - 1, start));
        List<String> lines = new ArrayList<>(List.of(source.substring(from, to).split("\n", -1)));

        boolean allMarked = true;
        for (String line : lines) {
            if (!line.isBlank() && !hasMarker(line, kind)) {
                allMarked = false;
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        int number = 1;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines.get(i);
            if (line.isBlank()) {
                out.append(line);
                continue;
            }
            if (allMarked) {
                // Removing: take off one level of this marker only, so un-quoting a
                // nested quote steps out one level rather than flattening it.
                out.append(stripMarker(line, kind));
            } else {
                // Applying: bullet, number and quote are mutually exclusive line styles,
                // so switching between them converts rather than nests. Same rule as
                // setHeading, which also clears an existing marker first.
                String bare = stripAllMarkers(line);
                out.append(switch (kind) {
                    case BULLET -> "- " + bare;
                    case ORDERED -> (number++) + ". " + bare;
                    case QUOTE -> "> " + bare;
                });
            }
        }
        String replacement = out.toString();
        return new Edit(from, to, replacement, from, from + replacement.length());
    }

    private static boolean hasMarker(String line, Kind kind) {
        return switch (kind) {
            case BULLET -> BULLET_ITEM.matcher(line).find();
            case ORDERED -> ORDERED_ITEM.matcher(line).find();
            case QUOTE -> QUOTE_LINE.matcher(line).find();
        };
    }

    private static String stripMarker(String line, Kind kind) {
        Matcher m = switch (kind) {
            case BULLET -> BULLET_ITEM.matcher(line);
            case ORDERED -> ORDERED_ITEM.matcher(line);
            case QUOTE -> QUOTE_LINE.matcher(line);
        };
        return m.find() ? line.substring(m.end()) : line;
    }

    /** Removes every stacked list/quote marker, so "- &gt; text" becomes "text". */
    private static String stripAllMarkers(String line) {
        String result = line;
        for (int i = 0; i < 4; i++) { // Bounded: markers cannot stack meaningfully deeper.
            int length = markerLength(result);
            if (length == 0) {
                return result;
            }
            result = result.substring(length);
        }
        return result;
    }

    /** Length of any list or quote marker at the start of the line. */
    private static int markerLength(String line) {
        for (Pattern p : List.of(BULLET_ITEM, ORDERED_ITEM, QUOTE_LINE)) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return m.end();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------- alignment

    private static final Pattern ALIGN_OPEN =
            Pattern.compile("^<div\\s+align=\"(left|center|right)\"\\s*>$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIGN_CLOSE =
            Pattern.compile("^</div>$", Pattern.CASE_INSENSITIVE);

    /** A complete wrapper, opening tag through closing tag, as one range. */
    private static final Pattern WRAPPED_BLOCK = Pattern.compile(
            "(?s)^<div\\s+align=\"(left|center|right)\"\\s*>\\s*(.*?)\\s*</div>$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Aligns a block of text, or removes the alignment when it already has that one.
     *
     * <p>Markdown has no alignment syntax, so this wraps the block in a div. The blank
     * lines inside the wrapper matter: they end the HTML block, so CommonMark keeps
     * parsing the content as Markdown. Without them the text between the tags would be
     * passed through verbatim and any bold or links inside it would stop rendering.
     */
    public static Edit alignBlock(String source, int start, int end, String align) {
        int from = lineStart(source, start);
        int to = lineEnd(source, Math.max(end - 1, start));

        // The range may already be a whole wrapper rather than the text inside one - that
        // is exactly what the selection left behind by a previous alignment looks like.
        // Without this the wrapper would be treated as ordinary content and wrapped again,
        // stacking a new div on every click.
        Matcher self = WRAPPED_BLOCK.matcher(source.substring(from, to));
        if (self.matches()) {
            String inner = self.group(2).strip();
            return replaceWrapper(from, to, inner, self.group(1), align);
        }

        Wrapper existing = findWrapper(source, from, to);
        if (existing != null) {
            String inner = source.substring(existing.innerStart(), existing.innerEnd()).strip();
            return replaceWrapper(existing.start(), existing.end(), inner, existing.align(), align);
        }

        String inner = source.substring(from, to).strip();
        return wrapEdit(from, to, inner, align);
    }

    /** Same alignment again unwraps; a different one swaps the attribute. */
    private static Edit replaceWrapper(int start, int end, String inner,
                                       String currentAlign, String align) {
        if (currentAlign.equalsIgnoreCase(align)) {
            return new Edit(start, end, inner, start, start + inner.length());
        }
        return wrapEdit(start, end, inner, align);
    }

    /**
     * Leaves the selection on the content rather than on the whole wrapper, so pressing
     * another alignment button acts on the text again instead of on the div around it.
     */
    private static Edit wrapEdit(int start, int end, String inner, String align) {
        String opening = "<div align=\"" + align + "\">\n\n";
        String replacement = opening + inner + "\n\n</div>";
        int innerStart = start + opening.length();
        return new Edit(start, end, replacement, innerStart, innerStart + inner.length());
    }

    private record Wrapper(int start, int end, int innerStart, int innerEnd, String align) {}

    /** Looks for an alignment div immediately around the given line range. */
    private static Wrapper findWrapper(String source, int from, int to) {
        int openEnd = -1;
        int openStart = -1;
        String align = null;

        int cursor = from;
        while (cursor > 0) {
            int lineFrom = lineStart(source, cursor - 1);
            String line = source.substring(lineFrom, lineEnd(source, lineFrom)).strip();
            if (line.isEmpty()) {
                cursor = lineFrom;
                continue;
            }
            Matcher m = ALIGN_OPEN.matcher(line);
            if (m.matches()) {
                align = m.group(1);
                openStart = lineFrom;
                openEnd = lineEnd(source, lineFrom);
            }
            break;
        }
        if (align == null) {
            return null;
        }

        cursor = to;
        while (cursor < source.length()) {
            int lineFrom = Math.min(cursor + 1, source.length());
            lineFrom = lineStart(source, lineFrom);
            int lineTo = lineEnd(source, lineFrom);
            String line = source.substring(lineFrom, lineTo).strip();
            if (line.isEmpty()) {
                if (lineTo >= source.length()) {
                    break;
                }
                cursor = lineTo;
                continue;
            }
            if (ALIGN_CLOSE.matcher(line).matches()) {
                return new Wrapper(openStart, lineTo, openEnd, lineFrom, align);
            }
            break;
        }
        return null;
    }

    // ------------------------------------------------------------ code blocks

    /** An opening or closing fence, capturing its indent, its run of markers and its info. */
    private static final Pattern FENCE_LINE =
            Pattern.compile("^(\\s*)(`{3,}|~{3,})[ \\t]*(\\S*)[ \\t]*$");

    /**
     * Wraps the lines covering {@code [start, end)} in a fenced code block, or unwraps the
     * block they are already inside.
     *
     * <p>Whole lines, always. A fence is a block construct: opening one mid-line produces
     * backticks in the middle of a paragraph rather than a code block, which is what the
     * inline form is for. The toolbar chooses between the two by whether the selection
     * crosses a line boundary.
     *
     * @param language info string for the opening fence, or "" for none
     */
    public static Edit toggleFencedCode(String source, int start, int end, String language) {
        int from = lineStart(source, start);
        int to = lineEnd(source, Math.max(start, end));

        int[] enclosing = enclosingFence(source, from);
        if (enclosing != null) {
            // Already fenced: drop the two fence lines and give back the body.
            int openStart = enclosing[0];
            int openEnd = enclosing[1];
            int closeStart = enclosing[2];
            int closeEnd = enclosing[3];
            String body = source.substring(openEnd, closeStart);
            if (body.startsWith("\n")) {
                body = body.substring(1);
            }
            if (body.endsWith("\n")) {
                body = body.substring(0, body.length() - 1);
            }
            return new Edit(openStart, closeEnd, body, openStart, openStart + body.length());
        }

        String body = source.substring(from, to);
        // A fence has to be able to hold the body: a block containing ``` needs four.
        String marker = "`".repeat(Math.max(3, longestBacktickRun(body) + 1));
        String info = language == null ? "" : language.trim();
        String replacement = marker + info + "\n" + body + "\n" + marker;
        int bodyStart = from + marker.length() + info.length() + 1;
        return new Edit(from, to, replacement, bodyStart, bodyStart + body.length());
    }

    /**
     * Replaces the info string of the fence at {@code [start, end)}.
     *
     * <p>Only the opening fence line is touched, so the block's content and its closing
     * fence are byte-identical afterwards - this must never be able to corrupt code.
     *
     * @param language new info string, or "" to remove it
     */
    public static Edit setFenceLanguage(String source, int start, int end, String language) {
        int from = Math.max(0, Math.min(start, source.length()));
        int lineTo = lineEnd(source, from);
        Matcher fence = FENCE_LINE.matcher(source.substring(from, lineTo));
        if (!fence.matches()) {
            return null; // Not a fenced block; an indented one has no info string to set.
        }
        String info = language == null ? "" : language.trim();
        String replacement = fence.group(1) + fence.group(2) + info;
        int caret = from + replacement.length();
        return new Edit(from, lineTo, replacement, caret, caret);
    }

    /**
     * The fence pair enclosing the line at {@code offset}, as
     * {@code {openStart, openEnd, closeStart, closeEnd}}, or null if it is not inside one.
     */
    private static int[] enclosingFence(String source, int offset) {
        int at = 0;
        while (at <= source.length()) {
            int lineTo = lineEnd(source, at);
            Matcher open = FENCE_LINE.matcher(source.substring(at, lineTo));
            if (open.matches()) {
                String marker = open.group(2);
                int bodyFrom = lineTo;
                int scan = lineTo + 1;
                while (scan <= source.length()) {
                    int closeTo = lineEnd(source, scan);
                    Matcher close = FENCE_LINE.matcher(source.substring(scan, closeTo));
                    // A closing fence uses the same character and is at least as long,
                    // and carries no info string.
                    if (close.matches() && close.group(3).isEmpty()
                            && close.group(2).charAt(0) == marker.charAt(0)
                            && close.group(2).length() >= marker.length()) {
                        if (offset >= at && offset <= closeTo) {
                            return new int[] {at, bodyFrom, scan, closeTo};
                        }
                        at = closeTo + 1;
                        break;
                    }
                    if (closeTo >= source.length()) {
                        return null; // Unterminated fence; leave it alone.
                    }
                    scan = closeTo + 1;
                }
                continue;
            }
            if (lineTo >= source.length()) {
                return null;
            }
            at = lineTo + 1;
        }
        return null;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return longest;
    }

    // ----------------------------------------------------------------- tables

    /** Widest a generated column is padded to; "Header 10" is nine characters. */
    private static final int MIN_COLUMN_WIDTH = 3;

    /**
     * Inserts a GFM table of {@code rows} x {@code columns} as its own block.
     *
     * <p>{@code rows} counts the header, so 3 x 4 is a header plus two body rows. That is
     * what the grid picker shows and what every other editor's picker means by it.
     *
     * <p>The table is always placed after the caret's line rather than at the caret. A
     * table spliced into the middle of a paragraph is not a table - GFM needs it to start
     * at a line boundary - so inserting where the caret happens to sit would silently
     * produce a paragraph full of pipes.
     *
     * <p>Blank lines are added on either side only where one is not already there. Without
     * the trailing one the following paragraph is read as another table row.
     *
     * @return an edit whose selection covers the first header cell, so typing replaces it
     */
    public static Edit insertTable(String source, int caret, int rows, int columns) {
        int rowCount = Math.max(2, rows);
        int columnCount = Math.max(1, columns);

        int from = lineStart(source, caret);
        int to = lineEnd(source, caret);
        // On a blank line, use it; otherwise start after the line the caret is on.
        int at = source.substring(from, to).isBlank() ? from : to;

        String before = source.substring(0, at);
        String after = source.substring(at);

        String prefix;
        if (before.isEmpty() || before.endsWith("\n\n")) {
            prefix = "";
        } else if (before.endsWith("\n")) {
            prefix = "\n";
        } else {
            prefix = "\n\n";
        }
        // The table itself ends with a newline, so one more is all a blank line needs.
        String suffix = after.isEmpty() || after.startsWith("\n") ? "" : "\n";

        String[] headers = new String[columnCount];
        int[] widths = new int[columnCount];
        for (int c = 0; c < columnCount; c++) {
            headers[c] = "Header " + (c + 1);
            widths[c] = Math.max(headers[c].length(), MIN_COLUMN_WIDTH);
        }

        StringBuilder table = new StringBuilder();
        for (int c = 0; c < columnCount; c++) {
            table.append("| ").append(pad(headers[c], widths[c])).append(' ');
        }
        table.append("|\n");
        for (int c = 0; c < columnCount; c++) {
            table.append("| ").append("-".repeat(widths[c])).append(' ');
        }
        table.append("|\n");
        for (int r = 1; r < rowCount; r++) {
            for (int c = 0; c < columnCount; c++) {
                table.append("| ").append(" ".repeat(widths[c])).append(' ');
            }
            table.append("|\n");
        }

        // Past the leading "| " of the first cell; selecting the placeholder means the
        // first thing typed replaces it rather than appending to it.
        int selectionStart = at + prefix.length() + 2;
        return new Edit(at, at, prefix + table + suffix,
                selectionStart, selectionStart + headers[0].length());
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    // ------------------------------------------------------------------ lines

    public static int lineStart(String source, int offset) {
        int at = Math.min(Math.max(offset, 0), source.length());
        int nl = source.lastIndexOf('\n', at - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    public static int lineEnd(String source, int offset) {
        int at = Math.min(Math.max(offset, 0), source.length());
        int nl = source.indexOf('\n', at);
        return nl < 0 ? source.length() : nl;
    }
}
