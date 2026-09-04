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
