package com.mdviewer.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where the cloud is, and who this machine says it is.
 *
 * <p>Read from {@code ~/.mdviewer/cloud.properties}, beside the AI settings and in the
 * same format, so there is one place to look for anything this application was configured
 * with.
 *
 * <p>Written with sync switched off. Cloud sync sends documents to another machine, and a
 * feature that does that should be something the reader turned on rather than something
 * they discover has been running.
 */
public final class CloudConfig {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8081";

    private final Path file;
    private final Properties properties = new Properties();

    public CloudConfig() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "cloud.properties"));
    }

    CloudConfig(Path file) {
        this.file = file;
        load();
    }

    public boolean isEnabled() {
        return Boolean.parseBoolean(properties.getProperty("cloud.enabled", "false"));
    }

    public String endpoint() {
        return properties.getProperty("cloud.endpoint", DEFAULT_ENDPOINT).trim();
    }

    /**
     * The identity sent with each request.
     *
     * <p>A development subject for now. When sign-in against vsd-auth-server is wired up
     * this becomes a token and no caller changes - which is the reason it is behind a
     * method rather than read from the file at each use.
     */
    public String subject() {
        return properties.getProperty("cloud.subject", System.getProperty("user.name", "desktop"));
    }

    public CloudClient client() {
        return new CloudClient(endpoint(), subject());
    }

    public void set(boolean enabled, String endpoint, String subject) throws IOException {
        properties.setProperty("cloud.enabled", String.valueOf(enabled));
        properties.setProperty("cloud.endpoint", endpoint);
        properties.setProperty("cloud.subject", subject);
        save();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("MDViewer: could not read " + file + " - " + e.getMessage());
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(out, "MDViewer cloud sync. No document leaves this machine "
                    + "unless cloud.enabled is true and a sync is asked for.");
        }
    }
}
