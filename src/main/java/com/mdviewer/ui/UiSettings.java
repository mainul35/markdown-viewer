package com.mdviewer.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The handful of interface choices that outlive a run.
 *
 * <p>One file, read and written on demand rather than held open, because these change
 * about once in the life of an installation and the cost of re-reading is nothing next to
 * the cost of two components disagreeing about what is in it.
 *
 * <p>Nothing here fails a launch. A settings file that cannot be read leaves the defaults
 * in place; one that cannot be written means the choice applies to this run and is
 * forgotten, which is annoying and not worth refusing to start over.
 */
public final class UiSettings {

    private final Path file;

    public UiSettings() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "ui.properties"));
    }

    public UiSettings(Path file) {
        this.file = file;
    }

    public String get(String key) {
        return read().getProperty(key);
    }

    public void put(String key, String value) {
        Properties properties = read();
        properties.setProperty(key, value);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "MDViewer interface settings");
            }
        } catch (IOException cannotWrite) {
            /* Applies for this run; just will not be remembered. */
        }
    }

    private Properties read() {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException cannotRead) {
                /* Defaults, rather than a failed launch. */
            }
        }
        return properties;
    }
}
