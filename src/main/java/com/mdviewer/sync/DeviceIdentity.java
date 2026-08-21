package com.mdviewer.sync;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * What this installation calls itself, so the account can shut it out on its own.
 *
 * <p>Not a credential, and deliberately not treated as one. It names a machine; the token
 * authenticates it. Written in the clear beside the configuration because there is nothing
 * to protect - knowing it lets somebody claim to be this machine, which is worth exactly
 * as much as claiming to be this machine without a token: nothing.
 *
 * <p>Random rather than derived from the hardware. A machine fingerprint would identify the
 * reader's computer across accounts and reinstalls, which is more than is needed to answer
 * "which of my machines is this" and is not a thing to build without being asked.
 */
public final class DeviceIdentity {

    private final Path file;
    private String id;

    public DeviceIdentity() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "device"));
    }

    DeviceIdentity(Path file) {
        this.file = file;
    }

    /**
     * The id, made on first use and kept.
     *
     * <p>Stable across restarts, because a machine that renamed itself every launch could
     * never be revoked - the list would fill with one entry per run and the entry somebody
     * revoked would already be gone.
     */
    public synchronized String id() {
        if (id != null) {
            return id;
        }
        try {
            if (Files.exists(file)) {
                String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!stored.isBlank()) {
                    id = stored;
                    return id;
                }
            }
        } catch (IOException e) {
            // Unreadable is the same as absent: make a new one rather than refuse to sync.
        }

        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, id, StandardCharsets.UTF_8);
        } catch (IOException e) {
            /*
             * An id that cannot be written still works for this run. The consequence is that
             * the machine appears as a new one next launch, which is untidy rather than
             * harmful - and better than refusing to sync over a file permission.
             */
            System.err.println("MDViewer: could not record this machine's name in " + file
                    + " - it will appear as a new machine next time. " + e.getMessage());
        }
        return id;
    }

    /**
     * Something the reader will recognise in a list of machines.
     *
     * <p>The hostname, what kind of machine it is, and the operating system. An identifier
     * somebody has never seen tells them nothing, and a list of those makes the feature
     * useless at the moment it matters.
     *
     * <p>The model earns its place on an account with two Windows laptops, where a hostname
     * and a system name are two rows saying the same thing. It is also the name the reader
     * has already been shown by their own operating system, which is the shortest path
     * between a row in this list and the machine on the desk.
     *
     * <p>Named as the desktop application rather than left to be inferred. The same account
     * carries browsers, and "which of these is the app and which is a tab I left open"
     * should not be a deduction from an operating system name.
     */
    public String label() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = System.getProperty("user.name", "unknown");
        }

        String model = MachineModel.of();
        String system = System.getProperty("os.name", "unknown system");
        return host + (model.isEmpty() ? "" : " - " + model)
                + " - MDViewer desktop on " + system;
    }
}
