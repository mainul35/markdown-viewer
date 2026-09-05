package com.mdviewer.ui;

import java.nio.file.Path;

/**
 * How much room the interface should give itself, chosen rather than guessed.
 *
 * <p>This started as a measurement: below a certain window width, tighten everything. It
 * did not work, and the reason is worth keeping. A 1200px portrait tablet is wider than
 * most thresholds a desktop would call narrow, so the rule said "plenty of room" about a
 * screen where the toolbar was unusable. Width alone does not describe a display - the
 * same 1200px is roomy at arm's length on a monitor and cramped in two hands.
 *
 * <p>Density is the missing term, and nothing available here reports it honestly: JavaFX
 * gives a scale factor the desktop already applied, and the kernel gives physical size for
 * some panels and not others. Rather than infer a number badly from two unreliable ones,
 * the person holding the device says which it is. They can see it.
 */
public enum DisplaySize {

    /** Held in the hands. Larger text, and every target at 44px - the floor for a finger. */
    TABLET("tablet", "Tablet", "display-tablet"),

    /** A desktop or laptop at a normal viewing distance. The baseline; adds nothing. */
    REGULAR("regular", "Regular Display", null),

    /** Larger again: a dense panel, or eyes that would rather not squint. 52px targets. */
    LARGE("large", "Extra Large Display", "display-large");

    private static final String KEY = "displaySize";

    private final String stored;
    private final String label;
    private final String styleClass;

    DisplaySize(String stored, String label, String styleClass) {
        this.stored = stored;
        this.label = label;
        this.styleClass = styleClass;
    }

    /** What appears in the menu. */
    public String label() {
        return label;
    }

    /**
     * The class added to the scene root, or null for the baseline.
     *
     * <p>Regular deliberately has none. Styling the default would mean every rule in the
     * stylesheet needing a counterpart, and a theme that forgets one silently stops
     * applying at whichever size nobody tested.
     */
    public String styleClass() {
        return styleClass;
    }

    /** Every class this enum can add, so the wrong ones can be cleared before adding one. */
    public static String[] allStyleClasses() {
        return new String[] {TABLET.styleClass, LARGE.styleClass};
    }

    public static DisplaySize load(UiSettings settings) {
        String stored = settings.get(KEY);
        if (stored != null) {
            for (DisplaySize size : values()) {
                if (size.stored.equalsIgnoreCase(stored)) {
                    return size;
                }
            }
        }
        return defaultFor(Path.of("/proc/bus/input/devices"));
    }

    public void save(UiSettings settings) {
        settings.put(KEY, stored);
    }

    /**
     * The starting point before anybody chooses: a touchscreen means a tablet.
     *
     * <p>A guess, and only ever the first one. It is right often enough to save most people
     * a trip to the menu, and being wrong costs two clicks - which is the trade that makes
     * a default worth having and an automatic mode not.
     */
    static DisplaySize defaultFor(Path devices) {
        return TouchScroll.hasTouchscreen(devices) ? TABLET : REGULAR;
    }
}
