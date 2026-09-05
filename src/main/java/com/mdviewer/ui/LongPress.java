package com.mdviewer.ui;

import javafx.animation.PauseTransition;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.function.BooleanSupplier;

/**
 * Press and hold, for the right click a touchscreen has no button for.
 *
 * <p>Every context menu in this application is reached by right-clicking, and a finger has
 * no second button. On a platform that reports touch the toolkit synthesises the gesture;
 * here X11 reports the touchscreen as an ordinary pointer, so a long press is just a long
 * left-button hold and nothing happens. The menus are unreachable rather than missing.
 *
 * <p>So the gesture is measured directly: a press that stays still for long enough raises
 * the same {@link ContextMenuEvent} a right click would have raised. Everything downstream
 * - the editor's own menu, the preview's, the file tree's - is untouched, because it is the
 * same event arriving by another route.
 *
 * <p>The event is fired at whatever was under the finger rather than at the node this is
 * installed on. A file tree is one control containing many rows, and a menu that cannot
 * tell which row was held is barely a menu at all.
 */
public final class LongPress {

    /** Long enough not to fire while tapping, short enough not to feel broken. */
    private static final Duration HOLD = Duration.millis(500);

    /** A finger never holds perfectly still; past this it is a drag, not a press. */
    private static final double SLOP = 8;

    private LongPress() {
    }

    /**
     * @param node    where the gesture is watched
     * @param enabled asked at press time rather than at install time, so turning touch mode
     *                on takes effect without rebuilding anything
     */
    public static void install(Node node, BooleanSupplier enabled) {
        PauseTransition hold = new PauseTransition(HOLD);
        double[] origin = new double[4];      // sceneX, sceneY, screenX, screenY
        Node[] target = new Node[1];
        boolean[] fired = new boolean[1];

        hold.setOnFinished(e -> {
            /*
             * The finger has to still be down. Cancelling on release, on the pointer
             * leaving and on the bounds changing covers the cases that were known about,
             * and a menu opening on a plain tap says one of them was missed - the release
             * went to a popup that had grabbed the mouse, or somewhere else entirely.
             *
             * isPressed is JavaFX's own record of whether a button is down on this node.
             * It does not depend on this class seeing every event, which is exactly the
             * assumption that keeps turning out to be wrong.
             */
            if (target[0] == null || !node.isPressed()) {
                return;
            }
            fired[0] = true;
            Event.fireEvent(target[0], new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    origin[0], origin[1], origin[2], origin[3], false, null));
        });

        node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            fired[0] = false;
            if (!enabled.getAsBoolean() || e.isSecondaryButtonDown()) {
                return;
            }
            /*
             * A second tap held down means "select from here", the gesture every phone
             * uses: double tap and hold, then drag to take several lines. Starting a menu
             * timer on it would open a context menu over the selection being made.
             */
            if (e.getClickCount() >= 2) {
                hold.stop();
                return;
            }
            origin[0] = e.getSceneX();
            origin[1] = e.getSceneY();
            origin[2] = e.getScreenX();
            origin[3] = e.getScreenY();
            target[0] = e.getTarget() instanceof Node picked ? picked : node;
            hold.playFromStart();
        });

        node.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (Math.abs(e.getSceneX() - origin[0]) > SLOP
                    || Math.abs(e.getSceneY() - origin[1]) > SLOP) {
                hold.stop();
            }
        });

        /*
         * A press that became a menu must not also be a click. Without this a long press in
         * the file tree opens the menu and the file underneath it, and in the preview it
         * follows whatever link was being held.
         */
        node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            hold.stop();
            if (fired[0]) {
                e.consume();
            }
        });

        node.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (fired[0]) {
                fired[0] = false;
                e.consume();
            }
        });

        /*
         * The press can end without a release ever arriving here. Tapping into the editor
         * summons the on-screen keyboard, which resizes the window - and the control moves
         * out from under the finger, so the release lands somewhere else or nowhere. The
         * hold would then run to completion and open a context menu that nobody asked for,
         * on a plain tap.
         *
         * Losing the pointer is the same thing as ending the press, whatever the reason:
         * the finger is no longer where the gesture began, so the gesture is over.
         */
        node.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hold.stop());
        node.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> hold.stop());

        /* A window that moves or resizes under a press ends it for the same reason. */
        node.sceneProperty().addListener((observable, had, scene) -> hold.stop());
        node.layoutBoundsProperty().addListener((observable, was, now) -> hold.stop());
    }
}
