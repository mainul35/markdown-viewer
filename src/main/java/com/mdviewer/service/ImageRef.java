package com.mdviewer.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An image reference in Markdown source, in either form it can take.
 *
 * <p>Plain Markdown has no syntax for alignment or size, so an image that has been
 * positioned or resized has to be written as HTML. Both forms round-trip through here, so
 * an image can be styled, restyled and returned to plain Markdown without accumulating
 * markup. The HTML form is a paragraph with an align attribute because that is what also
 * renders correctly on GitHub - a viewer-specific extension would only work here.
 */
public record ImageRef(String alt, String src, String width, String align) {

    private static final Pattern MARKDOWN = Pattern.compile("!\\[(.*?)]\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern HTML_IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_SRC = attr("src");
    private static final Pattern ATTR_ALT = attr("alt");
    private static final Pattern ATTR_WIDTH = attr("width");
    private static final Pattern ATTR_ALIGN = attr("align");

    private static Pattern attr(String name) {
        return Pattern.compile("\\b" + name + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    }

    /** @return the parsed reference, or null if the text is not an image reference */
    public static ImageRef parse(String text) {
        String trimmed = text.strip();

        Matcher md = MARKDOWN.matcher(trimmed);
        if (md.matches()) {
            return new ImageRef(md.group(1), md.group(2), null, null);
        }

        Matcher img = HTML_IMG.matcher(trimmed);
        if (img.find()) {
            String tag = img.group();
            String src = group(ATTR_SRC, tag);
            if (src == null) {
                return null;
            }
            String align = group(ATTR_ALIGN, trimmed); // May sit on the wrapping paragraph.
            return new ImageRef(orEmpty(group(ATTR_ALT, tag)), src, group(ATTR_WIDTH, tag), align);
        }
        return null;
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public ImageRef withWidth(String newWidth) {
        // Full width is the natural state, so it drops the attribute rather than pinning it.
        String width = "100%".equals(newWidth) ? null : newWidth;
        return new ImageRef(alt, src, width, align);
    }

    public ImageRef withAlign(String newAlign) {
        String align = "left".equals(newAlign) ? null : newAlign;
        return new ImageRef(alt, src, width, align);
    }

    /** Plain Markdown when nothing needs HTML, otherwise the smallest HTML that does. */
    public String toMarkup() {
        if (width == null && align == null) {
            return "![" + alt + "](" + src + ")";
        }
        StringBuilder tag = new StringBuilder("<img src=\"").append(src).append('"');
        if (alt != null && !alt.isEmpty()) {
            tag.append(" alt=\"").append(alt).append('"');
        }
        if (width != null) {
            tag.append(" width=\"").append(width).append('"');
        }
        tag.append('>');

        if (align == null) {
            return tag.toString();
        }
        return "<p align=\"" + align + "\">" + tag + "</p>";
    }
}
