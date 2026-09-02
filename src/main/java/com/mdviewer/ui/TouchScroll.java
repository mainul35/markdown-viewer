package com.mdviewer.ui;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Drag to scroll, for screens with no wheel and no room for a scrollbar.
 *
 * <p>On a touchscreen the obvious gesture is to push the page with a finger. That works
 * elsewhere because the system reports a touch and the toolkit turns it into a scroll
 * gesture. On Linux it frequently does not: X11 reports the touchscreen as an ordinary
 * pointer, so JavaFX sees a mouse. Tapping works, because a tap looks exactly like a click.
 * Dragging selects text, because a drag looks exactly like a drag - and nothing scrolls,
 * because no scroll gesture was ever generated.
 *
 * <p>Nothing here can tell a finger from a mouse. A pointer event synthesised by X11 carries
 * no mark saying what caused it, so the question has no answer at the point where the
 * decision has to be made. Hence a mode, off for a mouse and on for a touchscreen, rather
 * than a detection that would be wrong half the time.
 *
 * <h2>The gestures, while the mode is on</h2>
 * <ul>
 *   <li><b>Press and drag</b> scrolls. The content follows the finger.</li>
 *   <li><b>Double tap</b> opens whatever the double tap already opened - the block editor
 *       in the preview, a word selection in the raw editor.</li>
 *   <li><b>Press and drag while editing</b> selects text, because at that point the reader
 *       is working inside a block rather than moving past it.</li>
 * </ul>
 *
 * <p>A single tap anywhere leaves that editing state and the drag goes back to scrolling.
 * Taps and double taps are never intercepted - only the drag changes meaning, and only once
 * it has travelled far enough to be a deliberate one.
 */
public final class TouchScroll {

    /** How far a drag must travel before it counts as a scroll rather than a stray wobble. */
    private static final double SLOP = 6;

    /**
     * Whether the preview currently has a block or table cell open for editing.
     *
     * <p>Asks the page's own bookkeeping rather than keeping a second copy of it here: the
     * editor can also be closed by Escape, by clicking away, or by a re-render, and a
     * duplicate flag would drift out of step with every one of those.
     */
    private static final String EDITING =
            "!!(window.__mdEditingBlock || window.__mdEditingCell)";

    private final Path file;
    private boolean enabled;

    public TouchScroll() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "ui.properties"));
    }

    public TouchScroll(Path file) {
        this.file = file;
        this.enabled = load();
    }

    /**
     * Whether drag-to-scroll is on.
     *
     * <p>Defaults to what the toolkit reports about touch support, so a tablet starts in the
     * mode it needs and a desktop keeps ordinary selection. That report is frequently wrong
     * on Linux - which is the whole reason this class exists - so it is only a starting
     * point, and the menu item settles it.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    private boolean load() {
        Properties properties = read();
        String stored = properties.getProperty("touchScroll");
        if (stored != null) {
            return Boolean.parseBoolean(stored);
        }
        try {
            return Platform.isSupported(ConditionalFeature.INPUT_TOUCH);
        } catch (RuntimeException noToolkitYet) {
            return false;
        }
    }

    private Properties read() {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException cannotRead) {
                /* A settings file that will not load is not worth failing a launch over. */
            }
        }
        return properties;
    }

    private void save() {
        Properties properties = read();
        properties.setProperty("touchScroll", Boolean.toString(enabled));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "MDViewer interface settings");
            }
        } catch (IOException cannotWrite) {
            /* The mode still applies for this run; it just will not be remembered. */
        }
    }

    /**
     * Drag scrolls the raw editor, unless a double tap has just asked to select.
     *
     * <p>Installed as a filter so it runs before the text area's own drag handling. Once a
     * drag is clearly a scroll the event is consumed, otherwise the caret would race across
     * the document underneath the moving text.
     */
    public void install(TextArea editor) {
        Gesture gesture = new Gesture();

        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            /* A double tap asks to work with the text; a single one goes back to reading. */
            if (e.getClickCount() >= 2) {
                gesture.selecting = true;
            } else if (e.getClickCount() == 1) {
                gesture.selecting = false;
            }
            gesture.begin(e.getSceneY(), editor.getScrollTop());
        });

        editor.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!gesture.shouldScroll(enabled, e.getSceneY())) {
                return;
            }
            editor.setScrollTop(Math.max(0, gesture.target(e.getSceneY())));
            e.consume();
        });

        editor.addEventFilter(MouseEvent.MOUSE_RELEASED, gesture::endedScrolling);
    }

    /**
     * The same gesture for the rendered preview, deferring to its block editor.
     *
     * <p>The page is moved through the helpers the preview already defines, so this scrolls
     * by the same route as everything else and the "has the reader scrolled" bookkeeping
     * stays true.
     */
    public void install(WebView preview) {
        Gesture gesture = new Gesture();

        preview.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            /* Asked once per gesture rather than per drag event: a script call on every
               mouse move would put a synchronous round trip into the middle of a scroll. */
            gesture.selecting = editing(preview);
            gesture.begin(e.getSceneY(), pageOffset(preview));
        });

        preview.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!gesture.shouldScroll(enabled, e.getSceneY())) {
                return;
            }
            scrollTo(preview, Math.max(0, gesture.target(e.getSceneY())));
            e.consume();
        });

        preview.addEventFilter(MouseEvent.MOUSE_RELEASED, gesture::endedScrolling);
    }

    /** One press-drag-release, and what it turned out to mean. */
    private static final class Gesture {
        private double pressedAt;
        private double offsetAtPress;
        private boolean scrolling;
        boolean selecting;

        void begin(double sceneY, double offset) {
            pressedAt = sceneY;
            offsetAtPress = offset;
            scrolling = false;
        }

        boolean shouldScroll(boolean enabled, double sceneY) {
            if (!enabled || selecting) {
                return false;
            }
            if (!scrolling && Math.abs(sceneY - pressedAt) < SLOP) {
                return false;
            }
            scrolling = true;
            return true;
        }

        /** Content follows the finger: drag down and earlier text comes into view. */
        double target(double sceneY) {
            return offsetAtPress - (sceneY - pressedAt);
        }

        /*
         * A drag that scrolled must not also land as a click, or letting go would drop the
         * caret wherever the finger stopped - or, in the preview, follow whichever link
         * happened to be under it.
         */
        void endedScrolling(MouseEvent e) {
            if (scrolling) {
                scrolling = false;
                e.consume();
            }
        }
    }

    private static boolean editing(WebView preview) {
        try {
            return Boolean.TRUE.equals(preview.getEngine().executeScript(EDITING));
        } catch (RuntimeException pageNotReady) {
            return false;
        }
    }

    private static double pageOffset(WebView preview) {
        try {
            Object y = preview.getEngine().executeScript(
                    "window.__mdScrollY ? window.__mdScrollY() : 0");
            return y instanceof Number number ? number.doubleValue() : 0;
        } catch (RuntimeException pageNotReady) {
            return 0;
        }
    }

    private static void scrollTo(WebView preview, double y) {
        try {
            preview.getEngine().executeScript(
                    "window.__mdScrollTo ? window.__mdScrollTo(" + y + ") : window.scrollTo(0,"
                    + y + ")");
        } catch (RuntimeException pageNotReady) {
            /* Nothing rendered yet - there is nothing to scroll. */
        }
    }
}
