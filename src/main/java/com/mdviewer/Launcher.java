package com.mdviewer;

/**
 * Entry point for the bundled standalone jar.
 *
 * <p>When JavaFX is on the classpath (as it is inside the shaded jar) the JVM refuses to
 * launch a main class that extends {@link javafx.application.Application}. Starting from a
 * plain class that merely calls into {@link MainApp} sidesteps that check, so
 * {@code java -jar mdviewer-<version>.jar} works with no JavaFX SDK installed on the machine.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
