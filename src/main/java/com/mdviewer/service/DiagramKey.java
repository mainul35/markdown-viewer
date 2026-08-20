package com.mdviewer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The name a rendered diagram is filed under.
 *
 * <p>This machine renders PlantUML and the browser cannot - the layout engine is Java, and
 * the cloud server has no processor to spare for it. So the desktop uploads what it drew and
 * the browser asks for it by name, and the name has to be something both ends can work out
 * from the only thing they both have: the diagram source in front of them.
 *
 * <p><strong>The rule, which the web client repeats and must keep repeating:</strong> take
 * the fence body exactly as it is written in the document, normalise CRLF to LF, strip
 * leading and trailing whitespace, and take the SHA-256 of the UTF-8 bytes as lower-case hex.
 *
 * <p>The <em>raw</em> body, deliberately - not what {@link DiagramService#prepare} makes of
 * it. That method rewrites C4 includes and inserts a layout pragma, which is Java the browser
 * does not have and never will; hashing its output would give this machine a name the browser
 * could not compute, and every diagram would look unrendered.
 */
public final class DiagramKey {

    private DiagramKey() {
    }

    public static String of(String source) {
        String normalised = source == null ? "" : source.replace("\r\n", "\n").replace("\r", "\n").strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java runtime.
            throw new IllegalStateException("this machine has no SHA-256", e);
        }
    }
}
