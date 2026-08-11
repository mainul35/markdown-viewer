package com.mdviewer.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drag a rectangle over an image and write the selected region to a new file.
 *
 * <p>Writes a copy rather than overwriting: the original may be referenced from other
 * documents, and a crop is not reversible once the pixels are gone.
 */
public final class CropDialog {

    private CropDialog() {
    }

    /**
     * @return the newly written cropped file, or null if the user cancelled or nothing
     *         usable was selected
     */
    public static Path cropInPlaceCopy(Stage owner, Path file) {
        Image source;
        try (InputStream in = Files.newInputStream(file)) {
            source = new Image(in);
        } catch (IOException e) {
            return null;
        }
        if (source.isError() || source.getWidth() <= 0) {
            return null;
        }

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Crop " + file.getFileName());

        // Fit the image to a workable size; the crop is computed back in source pixels.
        double maxWidth = 900;
        double maxHeight = 560;
        double scale = Math.min(1, Math.min(maxWidth / source.getWidth(), maxHeight / source.getHeight()));
        double viewWidth = source.getWidth() * scale;
        double viewHeight = source.getHeight() * scale;

        ImageView view = new ImageView(source);
        view.setFitWidth(viewWidth);
        view.setFitHeight(viewHeight);
        view.setPreserveRatio(true);

        Rectangle selection = new Rectangle(0, 0, 0, 0);
        selection.setFill(Color.web("#0B6E7F", 0.18));
        selection.setStroke(Color.web("#0B6E7F"));
        selection.setStrokeWidth(1.5);
        selection.getStrokeDashArray().addAll(6.0, 4.0);
        selection.setVisible(false);

        Pane canvas = new Pane(view, selection);
        canvas.setPrefSize(viewWidth, viewHeight);
        canvas.setMaxSize(viewWidth, viewHeight);

        double[] anchor = new double[2];
        canvas.setOnMousePressed(e -> {
            anchor[0] = clamp(e.getX(), 0, viewWidth);
            anchor[1] = clamp(e.getY(), 0, viewHeight);
            selection.setX(anchor[0]);
            selection.setY(anchor[1]);
            selection.setWidth(0);
            selection.setHeight(0);
            selection.setVisible(true);
        });
        canvas.setOnMouseDragged(e -> {
            double x = clamp(e.getX(), 0, viewWidth);
            double y = clamp(e.getY(), 0, viewHeight);
            selection.setX(Math.min(anchor[0], x));
            selection.setY(Math.min(anchor[1], y));
            selection.setWidth(Math.abs(x - anchor[0]));
            selection.setHeight(Math.abs(y - anchor[1]));
        });

        Label hint = new Label("Drag to choose the area to keep.");
        hint.getStyleClass().add("crop-hint");

        Button cancel = new Button("Cancel");
        Button crop = new Button("Crop");
        crop.setDefaultButton(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox buttons = new HBox(8, hint, spacer, cancel, crop);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().add("crop-buttons");

        Path[] result = new Path[1];
        cancel.setOnAction(e -> dialog.close());
        crop.setOnAction(e -> {
            if (selection.getWidth() < 4 || selection.getHeight() < 4) {
                hint.setText("Select a larger area, then press Crop.");
                return;
            }
            result[0] = write(source, file,
                    (int) Math.round(selection.getX() / scale),
                    (int) Math.round(selection.getY() / scale),
                    (int) Math.round(selection.getWidth() / scale),
                    (int) Math.round(selection.getHeight() / scale));
            dialog.close();
        });

        BorderPane root = new BorderPane(canvas);
        root.setBottom(buttons);
        root.getStyleClass().add("crop-root");

        Scene scene = new Scene(root);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
            if (owner.getScene().getRoot().getStyleClass().contains("dark-theme")) {
                root.getStyleClass().add("dark-theme");
            }
        }
        dialog.setScene(scene);
        dialog.showAndWait();
        return result[0];
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Writes the region as a PNG beside the original, never overwriting it. */
    private static Path write(Image source, Path original, int x, int y, int width, int height) {
        int maxWidth = (int) source.getWidth();
        int maxHeight = (int) source.getHeight();
        x = (int) clamp(x, 0, maxWidth - 1);
        y = (int) clamp(y, 0, maxHeight - 1);
        width = (int) clamp(width, 1, maxWidth - x);
        height = (int) clamp(height, 1, maxHeight - y);

        PixelReader reader = source.getPixelReader();
        if (reader == null) {
            return null;
        }
        WritableImage cropped = new WritableImage(reader, x, y, width, height);

        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader croppedReader = cropped.getPixelReader();
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                buffered.setRGB(px, py, croppedReader.getArgb(px, py));
            }
        }

        try {
            Path target = uniqueSibling(original, "-cropped.png");
            javax.imageio.ImageIO.write(buffered, "png", target.toFile());
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private static Path uniqueSibling(Path original, String suffix) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot <= 0 ? name : name.substring(0, dot);
        Path folder = original.getParent();
        Path candidate = folder.resolve(stem + suffix);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = folder.resolve(stem + "-cropped-" + i + ".png");
        }
        return candidate;
    }
}
