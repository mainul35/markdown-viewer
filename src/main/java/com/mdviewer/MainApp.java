package com.mdviewer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

public class MainApp extends Application {

    private static File currentFile;

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();

        MainController controller = loader.getController();
        controller.setPrimaryStage(primaryStage);

        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());

        primaryStage.setTitle("MDViewer - Markdown Editor");
        primaryStage.setScene(scene);
        primaryStage.show();

        if (getParameters().getRaw().size() > 0) {
            String filePath = getParameters().getRaw().get(0);
            controller.openFile(new File(filePath));
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
