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

        Wrapper existing = findWrapper(source, from, to);
        if (existing != null) {
            String inner = source.substring(existing.innerStart(), existing.innerEnd()).strip();
            if (existing.align().equalsIgnoreCase(align)) {
                // Same alignment again: unwrap back to plain Markdown.
                return new Edit(existing.start(), existing.end(), inner,
                        existing.start(), existing.start() + inner.length());
            }
            String replacement = wrap(inner, align);
            return new Edit(existing.start(), existing.end(), replacement,
                    existing.start(), existing.start() + replacement.length());
        }

        String inner = source.substring(from, to).strip();
        String replacement = wrap(inner, align);
        return new Edit(from, to, replacement, from, from + replacement.length());
    }

    private static String wrap(String inner, String align) {
        return "<div align=\"" + align + "\">\n\n" + inner + "\n\n</div>";
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
