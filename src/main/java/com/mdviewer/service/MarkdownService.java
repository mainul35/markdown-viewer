package com.mdviewer.service;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
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

    /** Matches src="..." / src='...' in raw HTML that CommonMark passes through untouched. */
    private static final Pattern RAW_IMG_SRC = Pattern.compile("(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])(.*?)\\2");

    private final Parser parser = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .build();

    /**
     * @param markdown document source
     * @param baseDir  directory of the file being viewed, used to resolve relative image
     *                 paths; may be {@code null} for an unsaved document
     */
    public Result render(String markdown, Path baseDir) {
        List<Diagram> diagrams = new ArrayList<>();
        Node document = parser.parse(markdown == null ? "" : markdown);

        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(Arrays.asList(TablesExtension.create()))
                .nodeRendererFactory(context -> new MdNodeRenderer(context, baseDir, diagrams))
                .build();

        String html = rewriteRawImageSources(renderer.render(document), baseDir);
        return new Result(html, List.copyOf(diagrams));
    }

    // ------------------------------------------------------------------ images

    /**
     * Turns a relative image path into an absolute {@code file:} URL. The preview is loaded
     * with {@code loadContent}, so the document has no base URL and relative paths would
     * otherwise resolve against {@code about:blank} and silently fail to load.
     */
    static String resolveImageUrl(String destination, Path baseDir) {
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
            // Strip any fragment/query a local path would not have, then decode %20 etc.
            String decoded = java.net.URLDecoder.decode(dest, java.nio.charset.StandardCharsets.UTF_8);
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

    private static String rewriteRawImageSources(String html, Path baseDir) {
        if (baseDir == null || !html.contains("<img")) {
            return html;
        }
        Matcher m = RAW_IMG_SRC.matcher(html);
        StringBuilder sb = new StringBuilder(html.length());
        while (m.find()) {
            String url = resolveImageUrl(m.group(3), baseDir);
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

        MdNodeRenderer(HtmlNodeRendererContext context, Path baseDir, List<Diagram> diagrams) {
            this.context = context;
            this.html = context.getWriter();
            this.baseDir = baseDir;
            this.diagrams = diagrams;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class, Image.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof FencedCodeBlock fence) {
                renderFence(fence);
            } else if (node instanceof Image image) {
                renderImage(image);
            }
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
                String id = "mdv-uml-" + (diagrams.size() + 1);
                diagrams.add(new Diagram(id, literal));
                html.line();
                html.tag("div", Map.of("class", "mdv-diagram mdv-diagram-pending", "id", id));
                html.text("Rendering diagram\u2026");
                html.tag("/div");
                html.line();
                return;
            }

            renderPlainCode(tag, literal);
        }

        /** Reproduces CommonMark's default fenced-code output, which we replaced above. */
        private void renderPlainCode(String tag, String literal) {
            Map<String, String> attrs = new LinkedHashMap<>();
            if (!tag.isEmpty()) {
                attrs.put("class", "language-" + tag);
            }
            html.line();
            html.tag("pre");
            html.tag("code", attrs);
            html.text(literal);
            html.tag("/code");
            html.tag("/pre");
            html.line();
        }

        private void renderImage(Image image) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("src", resolveImageUrl(image.getDestination(), baseDir));
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
