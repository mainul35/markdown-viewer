package com.mdviewer.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Asks the desktop's on-screen keyboard to appear when there is somewhere to type.
 *
 * <p>Every other application on a tablet does this without any code, because the toolkit
 * takes part in the input-method protocol - {@code zwp_text_input} on Wayland, XIM under
 * X11 - and the compositor shows the keyboard when a text field takes focus. JavaFX
 * implements neither. It has no Wayland backend at all, and its GTK/X11 backend never
 * advertises an input context, so the compositor is never told a text field exists and the
 * keyboard is never asked for. Nothing in the application can be adjusted to fix that; the
 * conversation it would happen in does not take place.
 *
 * <p>So the request is made out of band, by running a command. That is a worse mechanism
 * than the protocol in every way except the one that matters here: it works.
 *
 * <h2>Why a command rather than D-Bus</h2>
 *
 * <p>A D-Bus call to the compositor would be tidier and ties the application to one
 * desktop's interface, spelled one way, on one version. A command is a line of
 * configuration, so a different keyboard - or a different desktop, or a script that knows
 * something this does not - needs no code here. The defaults name {@code vkbd} because that
 * is what this tablet runs; anything that accepts a show and a hide argument will do.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It will not hold the application up. The command runs on its own thread, its output is
 * discarded, and nothing waits for it. A keyboard that is missing, slow or broken must not
 * be able to freeze an editor - that would be a worse fault than the one being fixed.
 *
 * <p>And it stops trying. If the command cannot be started once, it is not attempted again
 * for the life of the run: a machine without that keyboard would otherwise fork a doomed
 * process on every focus change.
 */
public final class VirtualKeyboard {

    private static final String SHOW_KEY = "keyboardShowCommand";
    private static final String HIDE_KEY = "keyboardHideCommand";

    /** Only Linux has this problem, and only Linux has these tools. */
    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private final UiSettings settings;
    private volatile boolean broken;

    public VirtualKeyboard(UiSettings settings) {
        this.settings = settings;
    }

    /**
     * The command run when a text field takes focus.
     *
     * <p>Configurable through {@code ~/.mdviewer/ui.properties}, so pointing this at
     * another keyboard is an edit rather than a build. An empty value switches it off,
     * which is how somebody on a desktop Linux machine with a real keyboard turns it off
     * without turning off touch mode.
     */
    public String showCommand() {
        return command(SHOW_KEY, "vkbd --show");
    }

    public String hideCommand() {
        return command(HIDE_KEY, "vkbd --hide");
    }

    /**
     * A missing setting means "use the default"; so does an empty one.
     *
     * <p>Treating empty as "switched off" is the tidier reading and it is a trap. A key
     * left with nothing after the equals sign is what a half-finished edit looks like, and
     * what an appended line looks like when the value was meant to come later - and the
     * result is a feature that vanishes with no error anywhere. Turning it off is spelled
     * {@code off}, which nobody types by accident.
     */
    private String command(String key, String fallback) {
        String stored = settings.get(key);
        if (stored != null) {
            String trimmed = stored.trim();
            if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("none")) {
                return "";
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return LINUX ? fallback : "";
    }

    /**
     * How much of the screen the keyboard covers, as a fraction of its height.
     *
     * <p>Needed because nothing tells the application. A compositor moves a window out of
     * the keyboard's way when the application takes part in the input-method protocol, and
     * this one cannot - so the keyboard is drawn over the window and the bottom of the
     * document is simply hidden behind it, caret included.
     *
     * <p>Asking the keyboard how tall it is would be better and there is nowhere to ask: it
     * is another process with its own window, and its height is its own business. So it is
     * configured, defaulting to the same 0.42 that vkbd itself defaults to.
     *
     * <p>Zero switches the whole reflow off, for a keyboard that floats rather than docks,
     * or a compositor that does move the window.
     */
    public double heightFraction() {
        String stored = settings.get("keyboardHeightFraction");
        if (stored != null && !stored.isBlank()) {
            try {
                double asked = Double.parseDouble(stored.trim());
                /* Past two thirds there is no document left to type into. */
                return Math.max(0, Math.min(0.66, asked));
            } catch (NumberFormatException notANumber) {
                /* Fall through to the default rather than refusing to make room at all. */
            }
        }
        return 0.42;
    }

    /**
     * Whether this machine should be summoning a keyboard at all.
     *
     * <p>Two conditions, and both matter. A touchscreen, because there is otherwise nothing
     * to type with on screen and no reason to want one. And <em>no</em> physical keyboard,
     * because a tablet in a keyboard case is a laptop: throwing a keyboard over half the
     * screen when there is a real one under your hands is worse than doing nothing.
     *
     * <p>Re-read rather than decided once at startup - a keyboard case gets attached and
     * detached, and the answer changes with it. The file is a few kilobytes of text and
     * this is asked when focus moves, not in a loop.
     *
     * <p>{@code keyboardSummon} in the settings overrides both: {@code always} or
     * {@code never}, for the cases this guesses wrong.
     */
    private long lastAsked;
    private boolean lastAnswer;

    public boolean isWanted() {
        /*
         * Cached for a second. This is asked every time focus moves, which during ordinary
         * use is often - tabbing through a dialog asks it once per field - and each answer
         * costs reading and parsing a file. A second is short enough that plugging a
         * keyboard in is noticed while you are still reaching for it.
         */
        long now = System.currentTimeMillis();
        if (now - lastAsked < 1000) {
            return lastAnswer;
        }
        lastAsked = now;
        lastAnswer = decideIfWanted();
        return lastAnswer;
    }

    private boolean decideIfWanted() {
        String override = settings.get("keyboardSummon");
        if (override != null) {
            String choice = override.trim().toLowerCase(Locale.ROOT);
            if (choice.equals("always")) {
                return true;
            }
            if (choice.equals("never")) {
                return false;
            }
        }
        Path devices = Path.of("/proc/bus/input/devices");
        return TouchScroll.hasTouchscreen(devices) && !hasPhysicalKeyboard(devices);
    }

    /**
     * Whether a real keyboard is attached, told apart from everything else claiming to be one.
     *
     * <p>Half the devices on a machine register as keyboards: a power button, a lid switch,
     * the volume keys on a tablet's case, and - importantly here - the on-screen keyboard
     * itself, which types by creating a virtual keyboard through {@code /dev/uinput}.
     * Counting anything with a {@code kbd} handler would find one on every machine and this
     * would never summon anything.
     *
     * <p>What separates a keyboard you can type on is that it has letters. The capability
     * bitmask in {@code /proc/bus/input/devices} says so exactly: A to Z are key codes 30 to
     * 44, which live in the last 64-bit word of the {@code B: KEY=} line. A power button's
     * last word is zero. So the question "can this thing type an A" is answered by one bit,
     * rather than by guessing from names.
     */
    static boolean hasPhysicalKeyboard(Path devices) {
        try {
            if (!Files.isReadable(devices)) {
                return false;
            }
            String name = "";
            boolean isKeyboardHandler = false;
            for (String line : Files.readAllLines(devices)) {
                if (line.startsWith("N: Name=")) {
                    name = line.toLowerCase(Locale.ROOT);
                    isKeyboardHandler = false;
                } else if (line.startsWith("H: Handlers=")) {
                    isKeyboardHandler = line.contains("kbd");
                } else if (line.startsWith("B: KEY=") && isKeyboardHandler) {
                    /*
                     * Two things here type through /dev/uinput and are indistinguishable
                     * from hardware at this level, and neither is a keyboard anybody is
                     * typing on:
                     *
                     *   vkbd - the on-screen keyboard itself. Counting it would mean the
                     *   moment it appeared, the application would decide a real keyboard
                     *   had arrived and stop summoning it.
                     *
                     *   keyd - a key remapper, which publishes a virtual keyboard whether
                     *   or not any hardware is attached. On this tablet it is present with
                     *   no keyboard plugged in at all.
                     */
                    if (name.contains("vkbd") || name.contains("keyd")
                            || name.contains("virtual")) {
                        continue;
                    }
                    if (hasLetterKeys(line.substring("B: KEY=".length()))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException cannotRead) {
            /* Assume a keyboard rather than throwing one over the screen unasked. */
            return true;
        }
    }

    /** True when the bitmask has the A-Z key codes, 30 to 44, set. */
    private static boolean hasLetterKeys(String bitmask) {
        String[] words = bitmask.trim().split("\\s+");
        if (words.length == 0) {
            return false;
        }
        try {
            long lowest = Long.parseUnsignedLong(words[words.length - 1], 16);
            for (int code = 30; code <= 44; code++) {
                if ((lowest & (1L << code)) == 0) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException notHex) {
            return false;
        }
    }

    /** The smallest window worth typing into; below this, move it up instead of squeezing. */
    private static final double MINIMUM_USEFUL_HEIGHT = 240;

    /**
     * Where a window should sit so the keyboard is not on top of it.
     *
     * <p>Separated from the window handling because it is the part that can be wrong in a
     * way nobody notices: a window one pixel too tall still looks right and still hides the
     * caret. Given numbers it returns numbers, and those can be checked.
     *
     * @param screenTop    top of the usable screen area
     * @param screenBottom bottom of it
     * @param windowTop    where the window is now
     * @param fraction     how much of the screen height the keyboard covers
     * @return {@code {top, height}} for the window, or null if it should be left alone
     */
    public static double[] fitAbove(double screenTop, double screenBottom,
                                    double windowTop, double fraction) {
        if (fraction <= 0 || screenBottom <= screenTop) {
            return null;
        }
        double keyboardTop = screenBottom - (screenBottom - screenTop) * fraction;
        double top = windowTop;
        if (keyboardTop - top < MINIMUM_USEFUL_HEIGHT) {
            /* Not enough room below where the window starts - move it up to make some,
               stopping at the top of the screen rather than going off it. */
            top = Math.max(screenTop, keyboardTop - MINIMUM_USEFUL_HEIGHT);
        }
        double height = keyboardTop - top;
        return height > 0 ? new double[] {top, height} : null;
    }

    /** Asks for the keyboard. Silent and non-blocking whether or not anything answers. */
    public void show() {
        run(showCommand());
    }

    /** Asks for it to go away again. */
    public void hide() {
        run(hideCommand());
    }

    private void run(String command) {
        if (broken || command == null || command.isBlank()) {
            return;
        }
        String[] parts = command.trim().split("\\s+");
        Thread worker = new Thread(() -> {
            try {
                new ProcessBuilder(parts)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException | RuntimeException noSuchKeyboard) {
                /*
                 * Almost always "no such command": this machine does not have that
                 * keyboard. Remember it, so a focus change does not fork a doomed process
                 * every time somebody clicks into the editor.
                 */
                broken = true;
            }
        }, "virtual-keyboard");
        worker.setDaemon(true);
        worker.start();
    }

    /** For tests: whether a failed start has switched this off for the rest of the run. */
    boolean isBroken() {
        return broken;
    }
}
