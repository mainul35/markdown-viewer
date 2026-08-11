package com.mdviewer;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class MainController {

    @FXML
    private BorderPane rootPane;

    @FXML
    private StackPane centerPane;

    @FXML
    private Label statusLabel;

    @FXML
    private Label wordCountLabel;

    @FXML
    private Label modeLabel;

    private Stage primaryStage;
    private File currentFile;
    private EditorMode currentMode = EditorMode.SPLIT;
    private boolean isModified = false;

    private TextArea textEditor;
    private WebView webView;
    private SplitPane splitPane;

    private final Parser markdownParser = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .build();

    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .build();

    public enum EditorMode {
        RAW, SPLIT, FULL_PREVIEW
    }

    @FXML
    public void initialize() {
        textEditor = new TextArea();
        textEditor.setPromptText("Write your Markdown here...");
        textEditor.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 14px; -fx-padding: 10px;");
        textEditor.setWrapText(false);

        webView = new WebView();
        webView.setMinWidth(100);
        webView.setPrefWidth(600);

        splitPane = new SplitPane();
        splitPane.getItems().addAll(textEditor, webView);
        splitPane.setDividerPositions(0.5);

        textEditor.textProperty().addListener((obs, oldText, newText) -> {
            updateWordCount();
            if (!isModified) {
                isModified = true;
                updateTitle();
            }
            if (currentMode != EditorMode.RAW) {
                updatePreview();
            }
        });

        centerPane.getChildren().add(splitPane);
        updateWordCount();
        updateStatus();
        updateLayout();

        javafx.application.Platform.runLater(() -> {
            updatePreview();
        });
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    @FXML
    private void handleNewFile() {
        if (checkUnsavedChanges()) {
            textEditor.clear();
            currentFile = null;
            isModified = false;
            updateTitle();
            updatePreview();
            updateStatus();
        }
    }

    @FXML
    private void handleOpenFile() {
        if (checkUnsavedChanges()) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Open Markdown File");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Markdown Files", "*.md", "*.markdown", "*.txt"),
                    new FileChooser.ExtensionFilter("All Files", "*.*")
            );

            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) {
                openFile(selectedFile);
            }
        }
    }

    public void openFile(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            textEditor.setText(content);
            currentFile = file;
            isModified = false;
            MainApp.setCurrentFile(file);
            updateTitle();
            updatePreview();
            updateStatus();
        } catch (IOException e) {
            showAlert("Error", "Failed to open file: " + e.getMessage());
        }
    }

    @FXML
    private void handleSaveFile() {
        if (currentFile == null) {
            handleSaveAs();
        } else {
            saveToFile(currentFile);
        }
    }

    @FXML
    private void handleSaveAs() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Markdown File");
        fileChooser.setInitialFileName("document.md");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown Files", "*.md", "*.markdown", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File selectedFile = fileChooser.showSaveDialog(primaryStage);
        if (selectedFile != null) {
            saveToFile(selectedFile);
        }
    }

    private void saveToFile(File file) {
        try {
            Files.writeString(file.toPath(), textEditor.getText(), StandardCharsets.UTF_8);
            currentFile = file;
            MainApp.setCurrentFile(file);
            isModified = false;
            updateTitle();
            updateStatus();
        } catch (IOException e) {
            showAlert("Error", "Failed to save file: " + e.getMessage());
        }
    }

    @FXML
    private void handleExit() {
        if (checkUnsavedChanges()) {
            System.exit(0);
        }
    }

    @FXML
    private void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About MDViewer");
        alert.setHeaderText("MDViewer - Markdown Editor");
        alert.setContentText("Version 1.0.0\nA professional desktop Markdown editor built with JavaFX.");
        alert.showAndWait();
    }

    @FXML
    private void handleRawMode() {
        currentMode = EditorMode.RAW;
        updateLayout();
    }

    @FXML
    private void handleSplitMode() {
        currentMode = EditorMode.SPLIT;
        updateLayout();
    }

    @FXML
    private void handleFullPreviewMode() {
        currentMode = EditorMode.FULL_PREVIEW;
        updateLayout();
    }

    private void updateLayout() {
        modeLabel.setText("Mode: " + currentMode.name().replace("_", " "));

        switch (currentMode) {
            case RAW:
                splitPane.setDividerPositions(1.0);
                break;
            case SPLIT:
                splitPane.setDividerPositions(0.5);
                break;
            case FULL_PREVIEW:
                splitPane.setDividerPositions(0.0);
                break;
        }

        javafx.application.Platform.runLater(() -> {
            updatePreview();
        });
    }

    private void updatePreview() {
        if (currentMode == EditorMode.RAW) {
            return;
        }
        if (webView == null || webView.getEngine() == null) {
            return;
        }
        String markdown = textEditor.getText();
        if (markdown == null || markdown.isEmpty()) {
            return;
        }
        Node document = markdownParser.parse(markdown);
        String html = htmlRenderer.render(document);

        String fullHtml = getFullHtmlWithCss(html);
        webView.getEngine().loadContent(fullHtml, "text/html; charset=UTF-8");
    }

    private String getFullHtmlWithCss(String content) {
        return "<!DOCTYPE html>" +
            "<html>" +
            "<head>" +
                "<meta charset=\"UTF-8\">" +
                "<style>" +
                    "body {" +
                        "font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;" +
                        "padding: 20px;" +
                        "line-height: 1.6;" +
                        "color: #24292e;" +
                        "background: #ffffff;" +
                    "}" +
                    "h1, h2, h3, h4, h5, h6 {" +
                        "margin-top: 24px;" +
                        "margin-bottom: 16px;" +
                        "font-weight: 600;" +
                        "line-height: 1.25;" +
                    "}" +
                    "h1 { font-size: 2em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }" +
                    "h2 { font-size: 1.5em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }" +
                    "h3 { font-size: 1.25em; }" +
                    "code {" +
                        "background-color: #f6f8fa;" +
                        "padding: 0.2em 0.4em;" +
                        "border-radius: 3px;" +
                        "font-family: SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;" +
                    "}" +
                    "pre {" +
                        "background-color: #f6f8fa;" +
                        "padding: 16px;" +
                        "border-radius: 3px;" +
                        "overflow: auto;" +
                    "}" +
                    "pre code {" +
                        "background-color: transparent;" +
                        "padding: 0;" +
                    "}" +
                    "blockquote {" +
                        "border-left: 4px solid #dfe2e5;" +
                        "padding: 0 1em;" +
                        "color: #6a737d;" +
                    "}" +
                    "table {" +
                        "border-collapse: collapse;" +
                        "width: 100%;" +
                        "margin: 16px 0;" +
                    "}" +
                    "th, td {" +
                        "border: 1px solid #dfe2e5;" +
                        "padding: 6px 13px;" +
                    "}" +
                    "tr:nth-child(2n) {" +
                        "background-color: #f6f8fa;" +
                    "}" +
                    "img { max-width: 100%; }" +
                    "a { color: #0366d6; text-decoration: none; }" +
                    "a:hover { text-decoration: underline; }" +
                    "ul, ol { padding-left: 2em; }" +
                "</style>" +
            "</head>" +
            "<body>" +
                content +
            "</body>" +
            "</html>";
    }

    private void updateWordCount() {
        String text = textEditor.getText().trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        wordCountLabel.setText("Words: " + words);
    }

    private void updateStatus() {
        String encoding = currentFile != null ? "UTF-8" : "N/A";
        String fileName = currentFile != null ? currentFile.getName() : "Untitled";
        statusLabel.setText("File: " + fileName + " | Encoding: " + encoding);
    }

    private void updateTitle() {
        if (primaryStage == null) return;
        String title = "MDViewer - ";
        if (currentFile != null) {
            title += currentFile.getName();
            if (isModified) title += " *";
        } else {
            title += "Untitled";
            if (isModified) title += " *";
        }
        primaryStage.setTitle(title);
    }

    private boolean checkUnsavedChanges() {
        if (isModified) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("You have unsaved changes.");
            alert.setContentText("Do you want to save before continuing?");

            ButtonType saveButton = new ButtonType("Save");
            ButtonType discardButton = new ButtonType("Discard Changes");
            ButtonType cancelButton = new ButtonType("Cancel");

            alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
            var result = alert.showAndWait();

            if (result.isPresent() && result.get() == saveButton) {
                handleSaveFile();
                return !isModified;
            } else if (result.isPresent() && result.get() == cancelButton) {
                return false;
            }
        }
        return true;
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
