package com.mdviewer.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a popup look like the window it opened from.
 *
 * <p>A {@link Popup} is a separate window with a scene of its own, and a scene inherits
 * neither the stylesheets nor the style classes of the one that opened it. Without this a
 * picker opens in default JavaFX grey beside a themed toolbar.
 *
 * <p>The theme class is looked for along the anchor's own ancestry rather than taken from
 * the scene root. Those are the same node today - the controller's root pane is what the
 * scene was built around - but only by arrangement, and a popup that renders in the wrong
 * palette is a confusing thing to debug from the outside. Walking up from the anchor asks
 * the question that actually matters: what theme is the button I opened from sitting in?
 */
public final class PopupTheme {

    /** Every theme class the app can put on a container, so a popup can carry it too. */
    private static final List<String> THEME_CLASSES = List.of("dark-theme");

    private PopupTheme() {
    }

    public static void matchTo(Popup popup, Node anchor) {
        if (popup.getScene() != null) {
            matchTo(popup.getScene(), anchor);
        }
    }

    /**
     * Dresses {@code target} in the palette {@code anchor} is currently sitting in.
     *
     * <p>Used by dialogs as well as popups: a dialog is another window with a scene of its
     * own, and inherits no more from its owner than a popup does. A modal dialog cannot be
     * open while the theme is switched - the switch is behind it - so reading the theme
     * once, as it opens, is the whole of what it needs.
     */
    public static void matchTo(Scene target, Node anchor) {
        Scene scene = anchor == null ? null : anchor.getScene();
        if (scene == null || target == null) {
            return;
        }
        target.getStylesheets().setAll(scene.getStylesheets());

        List<String> classes = new ArrayList<>(scene.getRoot().getStyleClass());
        for (Node node = anchor; node != null; node = node.getParent()) {
            for (String styleClass : node.getStyleClass()) {
                if (THEME_CLASSES.contains(styleClass) && !classes.contains(styleClass)) {
                    classes.add(styleClass);
                }
            }
        }
        // "root" is what the palette is declared on - .root.dark-theme needs both, and a
        // scene root that has somehow lost it would take the window's colours down with it.
        if (!classes.contains("root")) {
            classes.add(0, "root");
        }
        target.getRoot().getStyleClass().setAll(classes);
    }
}
