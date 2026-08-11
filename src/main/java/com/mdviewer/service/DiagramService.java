package com.mdviewer.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Renders PlantUML sources to inline SVG, off the JavaFX application thread.
 *
 * <p>Everything needed is bundled in the PlantUML jar, so rendering works with no
 * network connection and no Graphviz installation:
 * <ul>
 *   <li>{@code !pragma layout smetana} selects PlantUML's pure-Java layout engine,
 *       which replaces the external {@code dot} binary.</li>
 *   <li>C4-PlantUML {@code !include} URLs pointing at raw.githubusercontent.com are
 *       rewritten to the {@code <C4/...>} standard library shipped inside the jar.</li>
 *   <li>The SANDBOX security profile blocks all remote and local file includes, so a
 *       markdown document can never make the renderer read the filesystem or phone home.</li>
 * </ul>
 */
public final class DiagramService {

    static {
        // Must be set before any PlantUML class initializes.
        System.setProperty("PLANTUML_SECURITY_PROFILE", "SANDBOX");
        // Deliberately not forcing java.awt.headless: PlantUML renders fine without it,
        // and headless mode disables Desktop, which is what moves deleted files to the
        // recycle bin instead of destroying them.
    }

    /** {@code !include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/<ref>/C4_Xxx.puml} */
    private static final Pattern C4_REMOTE_INCLUDE = Pattern.compile(
            "(?m)^[ \\t]*!include(?:url)?[ \\t]+https?://raw\\.githubusercontent\\.com/"
                    + "plantuml-stdlib/C4-PlantUML/[^/\\s]+/(\\w+)\\.puml[ \\t]*$");

    private static final Pattern HAS_LAYOUT_PRAGMA = Pattern.compile("(?m)^[ \\t]*!pragma[ \\t]+layout\\b");

    private static final Pattern START_TAG = Pattern.compile("(?m)^[ \\t]*@start\\w+.*$");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdviewer-plantuml");
        t.setDaemon(true);
        return t;
    });

    /** Returns an already-rendered SVG for this source, or {@code null} if it must be rendered. */
    public String cached(String source) {
        return cache.get(source);
    }

    /** Renders on a background thread; the returned future always completes with displayable HTML. */
    public CompletableFuture<String> renderAsync(String source) {
        String hit = cache.get(source);
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        return CompletableFuture.supplyAsync(() -> {
            String svg = render(source);
            cache.put(source, svg);
            return svg;
        }, executor);
    }

    private String render(String source) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new net.sourceforge.plantuml.SourceStringReader(prepare(source)).outputImage(
                    out,
                    new net.sourceforge.plantuml.FileFormatOption(net.sourceforge.plantuml.FileFormat.SVG));
            String svg = out.toString(StandardCharsets.UTF_8);
            if (svg.isBlank()) {
                return errorHtml("PlantUML produced no output for this diagram.");
            }
            return svg;
        } catch (Throwable t) {
            // A malformed diagram must never take down the preview.
            return errorHtml(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Applies the offline-rendering rewrites described in the class docs. */
    static String prepare(String source) {
        String src = source.strip();
        src = C4_REMOTE_INCLUDE.matcher(src).replaceAll("!include <C4/$1>");

        if (!src.startsWith("@start")) {
            src = "@startuml\n" + src + "\n@enduml";
        }
        if (!HAS_LAYOUT_PRAGMA.matcher(src).find()) {
            // Insert directly after the @startXxx line so it applies to the whole diagram.
            var m = START_TAG.matcher(src);
            if (m.find()) {
                src = src.substring(0, m.end()) + "\n!pragma layout smetana" + src.substring(m.end());
            }
        }
        return src;
    }

    private static String errorHtml(String message) {
        return "<div class=\"mdv-diagram-error\"><strong>Diagram failed to render</strong><br>"
                + escape(message) + "</div>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
