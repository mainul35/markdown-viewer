package com.mdviewer.service;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown to the HTML shown in the preview pane.
 *
 * <p>Beyond CommonMark + GFM tables it handles three things the plain renderer cannot:
 * mermaid fences (handed to the bundled mermaid.js), PlantUML fences (rendered to SVG
 * out-of-band by {@link DiagramService}), and image paths (resolved against the document's
 * own directory, since the preview page has no base URL of its own).
 */
public final class MarkdownService {

    /** A PlantUML block awaiting background rendering; {@code id} is its placeholder element. */
    public record Diagram(String id, String source) {}

    /** Rendered HTML plus the diagrams whose SVG still has to be pushed in. */
    public record Result(String html, List<Diagram> diagrams) {}

    private static final Set<String> MERMAID_TAGS = Set.of("mermaid");
    private static final Set<String> PLANTUML_TAGS = Set.of("plantuml", "puml", "uml", "plantuml-svg");

    /** Matches src=/href= in raw HTML that CommonMark passes through untouched. */
    private static final Pattern RAW_IMG_SRC = Pattern.compile("(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])(.*?)\\2");
    private static final Pattern RAW_LINK_HREF = Pattern.compile("(<a\\b[^>]*?\\bhref\\s*=\\s*)([\"'])(.*?)\\2");

    /**
     * A complete unfenced diagram: an @start tag through its matching @end tag. PlantUML
     * owns these delimiters, so recognising them costs nothing and saves writing a fence
     * around syntax that already declares where it begins and ends.
     */
    private static final Pattern BARE_DIAGRAM =
            Pattern.compile("(?s)^@start(\\w+)\\b.*@end\\1\\s*$");

    /** First opening tag of a raw HTML block; deliberately does not match "&lt;/div&gt;". */
    private static final Pattern OPENING_TAG = Pattern.compile("<([A-Za-z][\\w-]*)");

    /**
     * Source spans are what make editing from the preview possible: every rendered element
     * carries the offsets of the Markdown it came from, so a selection in the preview can
     * be mapped back to the range in the editor that produced it.
     */
    private final Parser parser = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /**
     * @param markdown document source
     * @param baseDir  directory of the file being viewed, used to resolve relative image
     *                 paths; may be {@code null} for an unsaved document
     */
    public Result render(String markdown, Path baseDir) {
        List<Diagram> diagrams = new ArrayList<>();
        String source = markdown == null ? "" : markdown;
        Node document = parser.parse(source);
        int[] lineStarts = lineStarts(source);

        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(Arrays.asList(TablesExtension.create()))
                .nodeRendererFactory(context ->
                        new MdNodeRenderer(context, baseDir, diagrams, source, lineStarts))
                .attributeProviderFactory(context -> new SourceSpanAttributes(lineStarts, source.length()))
                .build();

        String html = renderer.render(document);
        html = rewriteRawAttribute(html, RAW_IMG_SRC, "<img", baseDir);
        html = rewriteRawAttribute(html, RAW_LINK_HREF, "<a", baseDir);
        return new Result(html, List.copyOf(diagrams));
    }

    // ----------------------------------------------------------- source spans

    /** Absolute offset of the first character of each line. */
    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    private static int offsetOf(int[] lineStarts, int lineIndex, int columnIndex) {
        if (lineIndex < 0 || lineIndex >= lineStarts.length) {
            return -1;
        }
        return lineStarts[lineIndex] + columnIndex;
    }

    /**
     * Stamps every rendered element with the Markdown offsets it was produced from.
     * CommonMark reports spans as line/column, so they are resolved against the line table
     * of the exact source string that was parsed.
     */
    private static final class SourceSpanAttributes implements AttributeProvider {

        private final int[] lineStarts;
        private final int sourceLength;

        SourceSpanAttributes(int[] lineStarts, int sourceLength) {
            this.lineStarts = lineStarts;
            this.sourceLength = sourceLength;
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                return;
            }
            SourceSpan first = spans.get(0);
            SourceSpan last = spans.get(spans.size() - 1);
            int start = offsetOf(lineStarts, first.getLineIndex(), first.getColumnIndex());
            int end = offsetOf(lineStarts, last.getLineIndex(), last.getColumnIndex() + last.getLength());
            if (start < 0 || end < start) {
                return;
            }
            attributes.put("data-md-start", String.valueOf(start));
            attributes.put("data-md-end", String.valueOf(Math.min(end, sourceLength)));
        }
    }

    // ------------------------------------------------------------------ images

    /**
     * Turns a relative image path into an absolute {@code file:} URL. The preview is loaded
     * with {@code loadContent}, so the document has no base URL and relative paths would
     * otherwise resolve against {@code about:blank} and silently fail to load.
     */
    static String resolveUrl(String destination, Path baseDir) {
        if (destination == null || destination.isBlank()) {
            return destination;
        }
        String dest = destination.trim();
        if (isAbsoluteReference(dest)) {
            return dest;
        }
        if (baseDir == null) {
            return dest;
        }
        try {
            // Decode %20 and friends. "+" is protected first because URLDecoder treats it
            // as a space, which would corrupt any filename that legitimately contains one.
            String decoded = java.net.URLDecoder.decode(
                    dest.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            Path resolved = baseDir.resolve(decoded).normalize();
            return resolved.toUri().toString();
        } catch (IllegalArgumentException e) {
            // Includes InvalidPathException - an unusable path just stays as written.
            return dest;
        }
    }

    private static boolean isAbsoluteReference(String dest) {
        if (dest.startsWith("#") || dest.startsWith("//")) {
            return true;
        }
        try {
            URI uri = new URI(dest);
            // A Windows path like C:\pics\a.png parses with scheme "c" - treat single
            // letter schemes as drive letters, not protocols.
            return uri.isAbsolute() && uri.getScheme().length() > 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static String rewriteRawAttribute(String html, Pattern pattern, String marker, Path baseDir) {
        if (baseDir == null || !html.contains(marker)) {
            return html;
        }
        Matcher m = pattern.matcher(html);
        StringBuilder sb = new StringBuilder(html.length());
        while (m.find()) {
            String url = resolveUrl(m.group(3), baseDir);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + url + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- renderer

    private static final class MdNodeRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;
        private final Path baseDir;
        private final List<Diagram> diagrams;
        private final String source;
        private final int[] lineStarts;

        MdNodeRenderer(HtmlNodeRendererContext context, Path baseDir, List<Diagram> diagrams,
                       String source, int[] lineStarts) {
            this.context = context;
            this.html = context.getWriter();
            this.baseDir = baseDir;
            this.diagrams = diagrams;
            this.source = source;
            this.lineStarts = lineStarts;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class, Image.class, Link.class, HtmlBlock.class,
                    Paragraph.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof FencedCodeBlock fence) {
                renderFence(fence);
            } else if (node instanceof Image image) {
                renderImage(image);
            } else if (node instanceof Link link) {
                renderLink(link);
            } else if (node instanceof HtmlBlock block) {
                renderHtmlBlock(block);
            } else if (node instanceof Paragraph paragraph) {
                renderParagraph(paragraph);
            }
        }

        /**
         * A paragraph is a diagram when it is one: {@code @startuml} and {@code @enduml}
         * are PlantUML's own delimiters and mean nothing else, so requiring a fence around
         * them as well is a rule the syntax does not need. Anything else renders as an
         * ordinary paragraph.
         */
        private void renderParagraph(Paragraph paragraph) {
            String diagram = bareDiagram(paragraph);
            if (diagram != null) {
                html.line();
                emitDiagramPlaceholder(diagram);
                html.line();
                return;
            }

            // Reproduces CommonMark's own paragraph output, including the rule that a
            // paragraph inside a tight list gets no <p> wrapper.
            if (isInTightList(paragraph)) {
                renderChildren(paragraph);
                return;
            }
            html.line();
            html.tag("p", context.extendAttributes(paragraph, "p", new LinkedHashMap<>()));
            renderChildren(paragraph);
            html.tag("/p");
            html.line();
        }

        /** @return the diagram source if this paragraph is a complete bare diagram */
        private String bareDiagram(Paragraph paragraph) {
            String text = sourceOf(paragraph);
            if (text == null || !text.startsWith("@start")) {
                return null;
            }
            return BARE_DIAGRAM.matcher(text).matches() ? text : null;
        }

        private String sourceOf(Node node) {
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                return null;
            }
            SourceSpan first = spans.get(0);
            SourceSpan last = spans.get(spans.size() - 1);
            int start = offsetOf(lineStarts, first.getLineIndex(), first.getColumnIndex());
            int end = offsetOf(lineStarts, last.getLineIndex(),
                    last.getColumnIndex() + last.getLength());
            if (start < 0 || end < start || end > source.length()) {
                return null;
            }
            return source.substring(start, end).strip();
        }

        private static boolean isInTightList(Paragraph paragraph) {
            Node parent = paragraph.getParent();
            Node grandparent = parent == null ? null : parent.getParent();
            return grandparent instanceof ListBlock list && list.isTight();
        }

        private void renderChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; ) {
                Node next = child.getNext();
                context.render(child);
                child = next;
            }
        }

        private void emitDiagramPlaceholder(String diagramSource) {
            String id = "mdv-uml-" + (diagrams.size() + 1);
            diagrams.add(new Diagram(id, diagramSource));
            html.tag("div", Map.of("class", "mdv-diagram mdv-diagram-pending", "id", id));
            html.text("Rendering diagram…");
            html.tag("/div");
        }

        /**
         * Raw HTML passes through verbatim, so it carries none of the source offsets the
         * rest of the document has - and without an offset, an image that has been
         * positioned or resized (and is therefore now HTML) could never be adjusted again.
         *
         * <p>The offsets are injected into the block's own opening tag rather than added
         * by wrapping it. An HTML block is frequently one half of a pair, such as a bare
         * {@code <div align="center">} whose {@code </div>} is a separate block further
         * down; wrapping would close that div immediately and the content it is meant to
         * align would end up outside it.
         */
        private void renderHtmlBlock(HtmlBlock block) {
            Map<String, String> attrs = context.extendAttributes(
                    block, "div", new LinkedHashMap<>());
            html.line();
            html.raw(injectAttributes(block.getLiteral(), attrs));
            html.line();
        }

        /** Adds attributes to the first opening tag; a closing-tag-only block is left alone. */
        private static String injectAttributes(String literal, Map<String, String> attrs) {
            if (attrs.isEmpty()) {
                return literal;
            }
            Matcher m = OPENING_TAG.matcher(literal);
            if (!m.find()) {
                return literal;
            }
            StringBuilder extra = new StringBuilder();
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                extra.append(' ').append(entry.getKey()).append("=\"")
                        .append(entry.getValue().replace("\"", "&quot;")).append('"');
            }
            return new StringBuilder(literal).insert(m.end(), extra).toString();
        }

        private void renderFence(FencedCodeBlock fence) {
            String tag = language(fence.getInfo());
            String literal = fence.getLiteral() == null ? "" : fence.getLiteral();

            if (MERMAID_TAGS.contains(tag)) {
                // mermaid.js reads textContent, so normal HTML escaping is what it wants.
                html.line();
                html.tag("pre", Map.of("class", "mermaid"));
                html.text(literal);
                html.tag("/pre");
                html.line();
                return;
            }

            if (PLANTUML_TAGS.contains(tag)) {
                html.line();
                emitDiagramPlaceholder(literal);
                html.line();
                return;
            }

            renderPlainCode(tag, literal);
        }

        /**
         * A fenced block becomes a labelled plate: the fence's own language tag is carried
         * into the markup as a caption. The information is already in the document, it just
         * never reached the reader.
         */
        private void renderPlainCode(String tag, String literal) {
            Map<String, String> attrs = new LinkedHashMap<>();
            if (!tag.isEmpty()) {
                attrs.put("class", "language-" + tag);
            }
            html.line();
            html.tag("div", Map.of("class", "mdv-code"));
            if (!tag.isEmpty()) {
                html.tag("span", Map.of("class", "mdv-code-lang"));
                html.text(tag);
                html.tag("/span");
            }
            html.tag("pre");
            html.tag("code", attrs);
            html.text(literal);
            html.tag("/code");
            html.tag("/pre");
            html.tag("/div");
            html.line();
        }

        /**
         * Same resolution as images, so a link to a sibling document arrives at the
         * controller as an absolute file: URL it can open rather than a relative path
         * that would resolve against about:blank and go nowhere.
         */
        private void renderLink(Link link) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("href", resolveUrl(link.getDestination(), baseDir));
            if (link.getTitle() != null) {
                attrs.put("title", link.getTitle());
            }
            html.tag("a", context.extendAttributes(link, "a", attrs));
            for (Node child = link.getFirstChild(); child != null; ) {
                Node next = child.getNext();
                context.render(child);
                child = next;
            }
            html.tag("/a");
        }

        private void renderImage(Image image) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("src", resolveUrl(image.getDestination(), baseDir));
            attrs.put("alt", altText(image));
            if (image.getTitle() != null) {
                attrs.put("title", image.getTitle());
            }
            html.tag("img", context.extendAttributes(image, "img", attrs), true);
        }

        private static String altText(Node node) {
            StringBuilder sb = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text text) {
                    sb.append(text.getLiteral());
                }

                @Override
                public void visit(Code code) {
                    sb.append(code.getLiteral());
                }
            });
            return sb.toString();
        }

        private static String language(String info) {
            if (info == null || info.isBlank()) {
                return "";
            }
            String first = info.strip().split("\\s+")[0];
            return first.toLowerCase(Locale.ROOT);
        }
    }
}
