package com.mdviewer.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An image reference in Markdown source, in any of the forms it can take.
 *
 * <p>Plain Markdown has no syntax for alignment, size or captions, so an image carrying
 * any of those has to be written as HTML. All forms round-trip through here, so an image
 * can be styled, restyled and returned to plain Markdown without accumulating markup. The
 * HTML forms are a paragraph or figure with an align attribute, which is what also renders
 * on GitHub - a viewer-specific extension would only work here.
 *
 * @param alt     alternative text, also the fallback caption
 * @param src     path or URL, always unbracketed
 * @param width   CSS width such as "50%", or null for natural size
 * @param align   "center" or "right", or null for the default left
 * @param caption figure caption, or null when the image has none
 */
public record ImageRef(String alt, String src, String width, String align, String caption) {

    private static final Pattern MARKDOWN = Pattern.compile("!\\[(.*?)]\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern HTML_IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGCAPTION = Pattern.compile(
            "<figcaption[^>]*>(.*?)</figcaption>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR_SRC = attr("src");
    private static final Pattern ATTR_ALT = attr("alt");
    private static final Pattern ATTR_WIDTH = attr("width");
    private static final Pattern ATTR_ALIGN = attr("align");

    private static Pattern attr(String name) {
        return Pattern.compile("\\b" + name + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    }

    /** Convenience for a plain image with no styling. */
    public ImageRef(String alt, String src) {
        this(alt, src, null, null, null);
    }

    /** @return the parsed reference, or null if the text is not an image reference */
    public static ImageRef parse(String text) {
        String trimmed = text.strip();

        Matcher md = MARKDOWN.matcher(trimmed);
        if (md.matches()) {
            return new ImageRef(md.group(1), unbracket(md.group(2)), null, null, null);
        }

        Matcher img = HTML_IMG.matcher(trimmed);
        if (img.find()) {
            String tag = img.group();
            String src = group(ATTR_SRC, tag);
            if (src == null) {
                return null;
            }
            // align may sit on the wrapping paragraph or figure, not on the img itself.
            String align = group(ATTR_ALIGN, trimmed);
            String caption = group(FIGCAPTION, trimmed);
            return new ImageRef(orEmpty(group(ATTR_ALT, tag)), src,
                    group(ATTR_WIDTH, tag), align, caption == null ? null : caption.strip());
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

    private static String unbracket(String destination) {
        String d = destination.strip();
        return d.startsWith("<") && d.endsWith(">") ? d.substring(1, d.length() - 1) : d;
    }

    /**
     * A Markdown link destination containing spaces or parentheses must be wrapped in
     * angle brackets, otherwise the parser does not see an image at all and the whole
     * reference renders as literal text. Screenshot filenames routinely contain spaces.
     */
    public static String markdownDestination(String src) {
        boolean needsBrackets = src.chars().anyMatch(Character::isWhitespace)
                || src.indexOf('(') >= 0 || src.indexOf(')') >= 0;
        return needsBrackets ? "<" + src + ">" : src;
    }

    public ImageRef withWidth(String newWidth) {
        // Full width is the natural state, so it drops the attribute rather than pinning it.
        return new ImageRef(alt, src, "100%".equals(newWidth) ? null : newWidth, align, caption);
    }

    public ImageRef withAlign(String newAlign) {
        return new ImageRef(alt, src, width, "left".equals(newAlign) ? null : newAlign, caption);
    }

    public ImageRef withCaption(String newCaption) {
        String trimmed = newCaption == null || newCaption.isBlank() ? null : newCaption.strip();
        return new ImageRef(alt, src, width, align, trimmed);
    }

    public ImageRef withSrc(String newSrc) {
        return new ImageRef(alt, newSrc, width, align, caption);
    }

    /** Plain Markdown when nothing needs HTML, otherwise the smallest HTML that does. */
    public String toMarkup() {
        if (width == null && align == null && caption == null) {
            return "![" + alt + "](" + markdownDestination(src) + ")";
        }

        StringBuilder tag = new StringBuilder("<img src=\"").append(src).append('"');
        if (alt != null && !alt.isEmpty()) {
            tag.append(" alt=\"").append(alt).append('"');
        }
        if (width != null) {
            tag.append(" width=\"").append(width).append('"');
        }
        tag.append('>');

        if (caption != null) {
            // A caption needs a structure to attach to, which is what figure is for.
            return "<figure" + alignAttribute() + ">" + tag
                    + "<figcaption>" + caption + "</figcaption></figure>";
        }
        if (align == null) {
            return tag.toString();
        }
        return "<p" + alignAttribute() + ">" + tag + "</p>";
    }

    private String alignAttribute() {
        return align == null ? "" : " align=\"" + align + "\"";
    }
}
