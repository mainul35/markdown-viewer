package com.mdviewer.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;

import java.util.function.BooleanSupplier;

/**
 * Press and hold, for the right click a touchscreen has no button for.
 *
 * <p>Every context menu in this application is reached by right-clicking, and a finger has
 * no second button. On a platform that reports touch the toolkit synthesises the gesture;
 * here X11 reports the touchscreen as an ordinary pointer, so a long press is just a long
 * left-button hold and nothing happens. The menus are unreachable rather than missing.
 *
 * <h2>Measured on release, not on a timer</h2>
 *
 * <p>The obvious way to do this is a timer started by the press and cancelled by the
 * release. It was written that way and it kept firing on plain taps, because the release
 * does not always arrive: the window resizes under the finger when the keyboard appears, a
 * menu popup takes the mouse grab and keeps the release for itself, the pointer leaves. Each
 * one was found separately and cancelled separately, and each fix revealed another - the
 * last of them leaving the node's own {@code pressed} flag stuck true, so <em>every</em>
 * subsequent tap opened a menu.
 *
 * <p>The fault was never the missing cancellation. It was a design that fires from a timer
 * and therefore has to enumerate every way a press can end - an open-ended list, on a
 * platform that is already lying about what the finger is doing.
 *
 * <p>So nothing fires on a timer. The press records when and where it started; the release
 * decides what it was. A gesture that never ends never produces anything, and the next press
 * overwrites the state whatever condition it was left in. There is no stuck state to get
 * into, because there is no state that outlives a press.
 *
 * <p>The cost is that the menu appears when the finger lifts rather than while it is still
 * down. That is a real difference from a phone, and it is worth it: a menu that appears
 * slightly late is a smaller fault than a menu that appears on every tap.
 */
public final class LongPress {

    /** Long enough not to fire on a tap, short enough not to feel like a stuck app. */
    private static final long HOLD_MILLIS = 500;

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
        /* startedAt, sceneX, sceneY, screenX, screenY - or startedAt 0 for "not armed". */
        double[] press = new double[5];
        Node[] target = new Node[1];
        boolean[] fired = new boolean[1];

        node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            fired[0] = false;
            press[0] = 0;
            if (!enabled.getAsBoolean() || e.isSecondaryButtonDown()) {
                return;
            }
            /*
             * A second tap held down means "select from here", the gesture every phone
             * uses: double tap and hold, then drag to take several lines. A menu on top of
             * that would be in the way of the selection being made.
             */
            if (e.getClickCount() >= 2) {
                return;
            }
            press[0] = System.currentTimeMillis();
            press[1] = e.getSceneX();
            press[2] = e.getSceneY();
            press[3] = e.getScreenX();
            press[4] = e.getScreenY();
            target[0] = e.getTarget() instanceof Node picked ? picked : node;
        });

        node.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (press[0] != 0
                    && (Math.abs(e.getSceneX() - press[1]) > SLOP
                        || Math.abs(e.getSceneY() - press[2]) > SLOP)) {
                press[0] = 0;      // a drag, not a press
            }
        });

        node.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            double startedAt = press[0];
            press[0] = 0;
            if (startedAt == 0 || target[0] == null) {
                return;
            }
            if (System.currentTimeMillis() - (long) startedAt < HOLD_MILLIS) {
                return;            // a tap
            }
            fired[0] = true;
            Event.fireEvent(target[0], new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    press[1], press[2], press[3], press[4], false, null));
            /*
             * A press that became a menu must not also be a click. Without this a long
             * press in the file tree opens the menu and the file underneath it, and in the
             * preview it follows whatever link was being held.
             */
            e.consume();
        });

        node.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (fired[0]) {
                fired[0] = false;
                e.consume();
            }
        });
    }
}
