package com.mdviewer.ui;

import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Puts the application's look on a window that is not the main one.
 *
 * <p>A dialog is its own window with its own scene, and neither the stylesheet nor the
 * display-size class reaches it by being on the main window. Both have to be put there.
 * Without the stylesheet a dialog is plain modena; without the class it is the right theme
 * at desktop sizes, which on a tablet is a dialog of small type and small buttons sitting
 * in front of an application that has neither.
 *
 * <p>Applied as windows appear rather than at each call site. Dialogs are built in a dozen
 * places here - a file chooser, an alert, the provider settings, the key prompt - and one
 * of them will always be the one nobody remembered.
 */
public final class WindowStyling {

    private WindowStyling() {
    }

    /**
     * @param window     the window to dress; nothing happens if it has no scene yet
     * @param stylesheet the application stylesheet, added if it is not already there
     * @param sizeClass  the display-size class to put on the root, or null for the baseline
     */
    public static void apply(Window window, String stylesheet, String sizeClass) {
        if (window == null || window.getScene() == null) {
            return;
        }
        Scene scene = window.getScene();
        if (stylesheet != null && !scene.getStylesheets().contains(stylesheet)) {
            scene.getStylesheets().add(stylesheet);
        }
        if (scene.getRoot() == null) {
            return;
        }
        /* Remove every size before adding one: a window that outlives a change of size
           would otherwise end up wearing both. */
        scene.getRoot().getStyleClass().removeAll(DisplaySize.allStyleClasses());
        if (sizeClass != null && !scene.getRoot().getStyleClass().contains(sizeClass)) {
            scene.getRoot().getStyleClass().add(sizeClass);
        }
    }
}
