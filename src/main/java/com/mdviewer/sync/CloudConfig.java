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

    private static final String DEFAULT_ENDPOINT = "http://localhost:8090";
    private static final String DEFAULT_ISSUER = "https://vsdauthserver.visualsitedesigner.com";
    private static final String DEFAULT_CLIENT = "mdviewer-mdviewer-desktop-client";

    private static CloudSession shared;
    private static String sharedFor;

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

    /** The authorization server that issues the tokens the cloud API verifies. */
    public String issuer() {
        return properties.getProperty("cloud.issuer", DEFAULT_ISSUER).trim();
    }

    /** This application, as vsd-auth-server knows it. Public: a desktop client has no secret. */
    public String clientId() {
        return properties.getProperty("cloud.clientId", DEFAULT_CLIENT).trim();
    }

    /**
     * The sign-in, made once for the whole application.
     *
     * <p>Held statically rather than per instance because the menu actions each read a
     * fresh {@code CloudConfig}, and a session per instance would mean a token refreshed
     * for one action being unknown to the next - a round trip to the authorization server
     * for every sync, and a "signed in" state that appears to come and go.
     *
     * <p>Rebuilt if the file now names a different authorization server, so changing it
     * does not leave a session bound to the old one.
     */
    public static synchronized CloudSession session(String issuer, String clientId) {
        String key = issuer + " " + clientId;
        if (shared == null || !key.equals(sharedFor)) {
            shared = new CloudSession(issuer, clientId);
            sharedFor = key;
        }
        return shared;
    }

    public CloudSession session() {
        return session(issuer(), clientId());
    }

    public CloudClient client() {
        return new CloudClient(endpoint(), session());
    }

    public void set(boolean enabled, String endpoint) throws IOException {
        properties.setProperty("cloud.enabled", String.valueOf(enabled));
        properties.setProperty("cloud.endpoint", endpoint);
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
