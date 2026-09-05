package com.mdviewer.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * A JavaFX image as PNG bytes.
 *
 * <p>The obvious way to write this is {@code SwingFXUtils.fromFXImage}, which lives in the
 * javafx.swing module. This application does not depend on it and adding a module to convert
 * an image is a poor trade, so the pixels are copied by hand into a {@link BufferedImage}
 * that ImageIO does know how to write.
 *
 * <p>It is a loop over every pixel, which sounds worse than it is: a full-screen screenshot
 * on a tablet is a few million iterations and finishes well inside the time it takes to
 * notice. Anything larger would be worth reconsidering.
 */
public final class ClipboardImage {

    private ClipboardImage() {
    }

    /**
     * The clipboard's picture as PNG, asked of AWT rather than JavaFX.
     *
     * <p>JavaFX and AWT reach the same X11 selection through different code, and on Linux
     * they disagree: {@code javafx.scene.input.Clipboard.hasImage()} regularly answers false
     * for a screenshot that AWT reads without complaint. The GTK backend is particular about
     * which targets it will negotiate, and a screenshot arriving through XWayland from a
     * Wayland tool is exactly the case it tends to miss. That is not something an
     * application can correct - but it can ask the other one.
     *
     * <p>Tried only after JavaFX has said no, so the ordinary path stays the ordinary path
     * and this costs nothing when it is not needed.
     *
     * @return PNG bytes, or null if AWT cannot see a picture either
     */
    public static byte[] fromSystemClipboard() {
        try {
            java.awt.datatransfer.Clipboard clipboard =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                return null;
            }
            Object data = clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
            if (!(data instanceof java.awt.Image awtImage)) {
                return null;
            }
            BufferedImage buffered;
            if (awtImage instanceof BufferedImage already) {
                buffered = already;
            } else {
                int width = awtImage.getWidth(null);
                int height = awtImage.getHeight(null);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D canvas = buffered.createGraphics();
                canvas.drawImage(awtImage, 0, 0, null);
                canvas.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", out);
            return out.toByteArray();
        } catch (Throwable cannotRead) {
            /*
             * Deliberately Throwable. Reading another process's clipboard goes through
             * native code and can fail in ways that are not IOException - a headless
             * environment throws an Error - and none of them are worth taking an editor
             * down for.
             */
            return null;
        }
    }

    /**
     * The clipboard's picture read by running a command, for when no Java API can see it.
     *
     * <p>On a Wayland desktop the clipboard belongs to the compositor, and X11 clients see
     * only what XWayland bridges. KDE bridges pictures properly - measured on a Plasma
     * tablet, a copied screenshot arrives on the X11 clipboard in forty formats including
     * image/png - so on that desktop this is never reached, and it should stay that way.
     *
     * <p>It exists for the compositors that do not. {@code wl-paste} asks the compositor
     * directly, which is where the picture always is regardless of what was bridged. It
     * comes from the separate wl-clipboard package; when that is absent this returns null
     * and the caller explains rather than failing silently.
     *
     * <p>Deliberately last. An earlier version of this file claimed the X11 clipboard never
     * carries the image at all, which was measured against a clipboard that turned out to
     * be empty - the screenshot tool had been invoked over ssh and captured nothing. The
     * ordinary paths work; this is a backstop, not the mechanism.
     *
     * @param command the command to run; its standard output is taken as image bytes
     */
    public static byte[] fromCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command.trim().split("\\s+"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] bytes;
            try (var out = process.getInputStream()) {
                bytes = out.readAllBytes();
            }
            /* A clipboard read that hangs must not hang the editor with it. */
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (bytes.length == 0) {
                return null;
            }
            /*
             * Checked rather than trusted: wl-paste prints an error to stdout in some
             * versions when the type is unavailable, and an error message written into a
             * document as a .png is worse than nothing happening.
             */
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes)) == null ? null : bytes;
        } catch (Throwable cannotRun) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /** What AWT says is on the clipboard, for explaining why nothing could be pasted. */
    public static String systemClipboardFormats() {
        try {
            var flavors = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getAvailableDataFlavors();
            if (flavors.length == 0) {
                return "";
            }
            /*
             * Deduplicated. AWT reports one flavour per representation class, so a
             * clipboard holding a line of text lists text/plain twenty-odd times - a wall
             * of repetition in a dialog whose only job is to say what is there.
             */
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (java.awt.datatransfer.DataFlavor flavor : flavors) {
                names.add(flavor.getMimeType().split(";")[0].trim());
            }
            return String.join(", ", names);
        } catch (Throwable cannotRead) {
            return "";
        }
    }

    /**
     * @return the image encoded as PNG, or null if it cannot be read
     */
    public static byte[] png(Image image) {
        if (image == null) {
            return null;
        }
        PixelReader pixels = image.getPixelReader();
        if (pixels == null) {
            /* An image that failed to load reports a size and reads back nothing. */
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", out);
            return out.toByteArray();
        } catch (IOException cannotEncode) {
            return null;
        }
    }
}
