package com.mdviewer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

public class MainApp extends Application {

    /** Share of the screen's usable area the window occupies on first launch. */
    private static final double SCREEN_FRACTION = 0.85;

    /** Floor for the window size - below this the split view stops being usable. */
    private static final double MIN_WIDTH = 900;
    private static final double MIN_HEIGHT = 600;

    private static File currentFile;

    private MainController controller;

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();

        controller = loader.getController();
        controller.setPrimaryStage(primaryStage);
        controller.setHostServices(getHostServices());

        // Size against the screen's *visual* bounds (which exclude the taskbar) rather than
        // a hard-coded 1200x800, so the window fits a laptop panel and still fills a large
        // monitor sensibly. Everything below it is layout-managed and resizes from here.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Rectangle2D window = computeWindowBounds(screen);

        Scene scene = new Scene(root, window.getWidth(), window.getHeight());
        scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());

        primaryStage.setTitle("MDViewer - Markdown Editor");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(Math.min(MIN_WIDTH, screen.getWidth()));
        primaryStage.setMinHeight(Math.min(MIN_HEIGHT, screen.getHeight()));
        primaryStage.setX(window.getMinX());
        primaryStage.setY(window.getMinY());
        primaryStage.show();

        // getParameters() is null unless the JavaFX launcher created this instance.
        Parameters params = getParameters();
        if (params != null && !params.getRaw().isEmpty()) {
            controller.openFile(new File(params.getRaw().get(0)));
        }
    }

    /**
     * Window geometry for a given screen work area: {@value #SCREEN_FRACTION} of it,
     * never smaller than the {@link #MIN_WIDTH}x{@link #MIN_HEIGHT} floor and never larger
     * than the screen itself, centred. Package-private and pure so it can be tested
     * against screen sizes this machine does not have.
     */
    static Rectangle2D computeWindowBounds(Rectangle2D screen) {
        double width = clamp(screen.getWidth() * SCREEN_FRACTION,
                Math.min(MIN_WIDTH, screen.getWidth()), screen.getWidth());
        double height = clamp(screen.getHeight() * SCREEN_FRACTION,
                Math.min(MIN_HEIGHT, screen.getHeight()), screen.getHeight());

        double x = screen.getMinX() + (screen.getWidth() - width) / 2;
        double y = screen.getMinY() + (screen.getHeight() - height) / 2;
        return new Rectangle2D(x, y, width, height);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.dispose();
        }
    }

    public static File getCurrentFile() {
        return currentFile;
    }

    public static void setCurrentFile(File file) {
        currentFile = file;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
