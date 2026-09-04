package com.mdviewer.ui;

import java.io.IOException;
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
