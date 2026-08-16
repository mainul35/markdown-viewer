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
        Scene scene = anchor.getScene();
        if (scene == null || popup.getScene() == null) {
            return;
        }
        popup.getScene().getStylesheets().setAll(scene.getStylesheets());

        List<String> classes = new ArrayList<>(scene.getRoot().getStyleClass());
        for (Node node = anchor; node != null; node = node.getParent()) {
            for (String styleClass : node.getStyleClass()) {
                if (THEME_CLASSES.contains(styleClass) && !classes.contains(styleClass)) {
                    classes.add(styleClass);
                }
            }
        }
        // "root" is what the palette is declared on - .root.dark-theme needs both, and a
        // scene root that has somehow lost it would take the popup's colours down with it.
        if (!classes.contains("root")) {
            classes.add(0, "root");
        }
        popup.getScene().getRoot().getStyleClass().setAll(classes);
    }
}
