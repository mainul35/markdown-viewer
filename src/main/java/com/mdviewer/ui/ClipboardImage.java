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
