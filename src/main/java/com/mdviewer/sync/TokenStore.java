package com.mdviewer.sync;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Keeps the refresh token between runs.
 *
 * <p><strong>What this protects against, stated plainly.</strong> The file is encrypted
 * under a key derived from this machine and this user account, and the key is nowhere on
 * disk. So the file is useless somewhere else: in a backup, on a synced drive, in a
 * repository it should never have reached, on a disk pulled out of the machine. That is a
 * real property and it is the one worth having, because those are the ways a credential
 * file actually escapes.
 *
 * <p><strong>What it does not protect against.</strong> Anything running as this user on
 * this machine can derive the same key and read the token, because it has to be able to -
 * that is what lets MDViewer read it without asking for a password every launch. This is
 * not a secure enclave and calling it encryption should not suggest otherwise.
 *
 * <p>The alternative that would be stronger is the operating system keychain, which costs
 * a native dependency and three platform-specific paths; the alternative that needs no
 * code is storing nothing and signing in every launch, which people come to resent and
 * then work around. This is the middle one, chosen deliberately. Only the refresh token is
 * kept - the access token lives in memory and is worth minutes.
 */
public final class TokenStore {

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;

    public TokenStore() {
        this(Path.of(System.getProperty("user.home", "."), ".mdviewer", "credentials"));
    }

    TokenStore(Path file) {
        this.file = file;
    }

    /** The stored refresh token, or empty when there is none or it cannot be read. */
    public String read() {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            byte[] blob = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.UTF_8).trim());
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, salt, 0, SALT_BYTES);
            System.arraycopy(blob, SALT_BYTES, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(blob, SALT_BYTES + IV_BYTES,
                    blob.length - SALT_BYTES - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            /*
             * A file written by another machine or user decrypts to nothing, and so does one
             * that has been damaged. Both mean the same thing to the reader - sign in again -
             * and neither is worth an error dialog on startup.
             */
            return "";
        }
    }

    public void write(String refreshToken) throws IOException {
        if (refreshToken == null || refreshToken.isBlank()) {
            clear();
            return;
        }
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));

            byte[] blob = new byte[salt.length + iv.length + sealed.length];
            System.arraycopy(salt, 0, blob, 0, salt.length);
            System.arraycopy(iv, 0, blob, salt.length, iv.length);
            System.arraycopy(sealed, 0, blob, salt.length + iv.length, sealed.length);

            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(blob),
                    StandardCharsets.UTF_8);
            restrictToOwner();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not store the sign-in - " + e.getMessage(), e);
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * The key, derived rather than stored.
     *
     * <p>The inputs are things about this machine and account that are stable across
     * restarts and different elsewhere. They are not secrets and are not treated as any -
     * the property being bought is "this file is inert on another machine", not "this
     * passphrase is hard to guess".
     */
    private SecretKey key(byte[] salt) throws Exception {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "unknown-host";
        }
        String material = String.join("\u0000",
                System.getProperty("user.name", "?"),
                System.getProperty("user.home", "?"),
                System.getProperty("os.name", "?"),
                host);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] derived = factory.generateSecret(
                new PBEKeySpec(material.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(derived, "AES");
    }

    /** Best effort. Windows has no POSIX bits; there the user profile directory is the guard. */
    private void restrictToOwner() {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            // Expected on Windows.
        }
    }
}
