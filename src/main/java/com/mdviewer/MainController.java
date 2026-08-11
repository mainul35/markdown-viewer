package com.mdviewer;

import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @FXML
    private Button themeButton;

    @FXML
    private MenuItem themeMenuItem;

    private Stage primaryStage;
    private HostServices hostServices;
    private File currentFile;
    private EditorMode currentMode = EditorMode.SPLIT;
    private boolean isModified = false;

    private TextArea textEditor;
    private WebView webView;
    private SplitPane splitPane;

    /** Debounce so typing does not re-render the preview on every keystroke. */
    private PauseTransition previewDebounce;

    /** True once the preview shell page (CSS + injection hook) has finished loading. */
    private boolean previewReady = false;

    /** Last rendered body HTML, re-applied whenever the shell page is (re)loaded. */
    private String currentPreviewHtml = "";

    /** Diagrams belonging to {@link #currentPreviewHtml}, re-pushed after a shell reload. */
    private List<MarkdownService.Diagram> currentDiagrams = List.of();

    /**
     * Incremented on every preview render. A background PlantUML result is discarded
     * unless it still carries the current generation, so edits cannot be overwritten
     * by a slow render of an older document.
     */
    private int previewGeneration = 0;

    private final MarkdownService markdownService = new MarkdownService();
    private final DiagramService diagramService = new DiagramService();

    /** Style class toggled on the scene root; see styles.css. */
    private static final String DARK_STYLE_CLASS = "dark-theme";

    private boolean darkMode = false;

    public enum EditorMode {
        RAW, SPLIT, FULL_PREVIEW
    }

    @FXML
    public void initialize() {
        textEditor = new TextArea();
        textEditor.setPromptText("Write your Markdown here...");
        // Font and padding come from styles.css - an inline style would override the
        // theme's colours, since inline styles beat stylesheet rules.
        textEditor.setWrapText(false);

        webView = new WebView();
        webView.setMinWidth(0);

        splitPane = new SplitPane();

        previewDebounce = new PauseTransition(Duration.millis(200));
        previewDebounce.setOnFinished(e -> updatePreview());

        textEditor.textProperty().addListener((obs, oldText, newText) -> {
            updateWordCount();
            if (!isModified) {
                isModified = true;
                updateTitle();
            }
            schedulePreviewUpdate();
        });

        initPreviewEngine();

        centerPane.getChildren().add(splitPane);
        updateWordCount();
        updateStatus();
        updateLayout();
        applyTheme();
    }

    // ------------------------------------------------------------------ theme

    @FXML
    private void handleToggleTheme() {
        darkMode = !darkMode;
        applyTheme();
    }

    /** Applies the current theme to the JavaFX chrome and to the preview document. */
    private void applyTheme() {
        if (darkMode) {
            if (!rootPane.getStyleClass().contains(DARK_STYLE_CLASS)) {
                rootPane.getStyleClass().add(DARK_STYLE_CLASS);
            }
        } else {
            rootPane.getStyleClass().remove(DARK_STYLE_CLASS);
        }

        String next = darkMode ? "Light Mode" : "Dark Mode";
        themeButton.setText(next);
        themeMenuItem.setText(next);

        applyPreviewTheme();
    }

    private void applyPreviewTheme() {
        if (!previewReady) {
            return; // Re-applied by the load-worker listener once the shell is up.
        }
        try {
            webView.getEngine().executeScript(
                    "window.__mdSetTheme(" + toJsStringLiteral(darkMode ? "dark" : "light") + ");");
        } catch (RuntimeException e) {
            // Preview page not in a usable state; the shell reload path re-applies it.
        }
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    // ---------------------------------------------------------------- preview

    /**
     * Loads the preview "shell" (CSS + a JS hook) exactly once. Document content is
     * afterwards pushed into the live DOM instead of reloading the whole page, which
     * keeps the preview's scroll position stable while typing.
     */
    private void initPreviewEngine() {
        WebEngine engine = webView.getEngine();

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                injectMermaid();
                previewReady = true;
                applyPreviewTheme();
                applyPreviewHtml(currentPreviewHtml);
                pushDiagrams(currentDiagrams, previewGeneration);
            } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                previewReady = false;
            }
        });

        // Clicking a link inside the preview must not navigate the WebView away from the
        // rendered document - hand the URL to the OS browser and stay put.
        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc == null) {
                return;
            }
            String loc = newLoc.toLowerCase();
            if (loc.startsWith("http://") || loc.startsWith("https://") || loc.startsWith("mailto:")) {
                engine.getLoadWorker().cancel();
                if (hostServices != null) {
                    hostServices.showDocument(newLoc);
                }
                Platform.runLater(this::loadPreviewShell);
            }
        });

        loadPreviewShell();
    }

    private void loadPreviewShell() {
        previewReady = false;
        // NOTE: no explicit content type. WebEngine.loadContent(html, "text/html; charset=UTF-8")
        // is rejected by WebKit (the load ends CANCELLED) and leaves the preview permanently
        // blank; the single-argument overload defaults to a plain "text/html" load.
        webView.getEngine().loadContent(buildPreviewShell());
    }

    private void schedulePreviewUpdate() {
        if (currentMode == EditorMode.RAW) {
            return;
        }
        previewDebounce.playFromStart();
    }

    private void updatePreview() {
        if (currentMode == EditorMode.RAW) {
            return;
        }
        Path baseDir = currentFile != null ? currentFile.toPath().toAbsolutePath().getParent() : null;
        MarkdownService.Result result = markdownService.render(textEditor.getText(), baseDir);

        currentPreviewHtml = result.html();
        currentDiagrams = result.diagrams();
        int generation = ++previewGeneration;

        applyPreviewHtml(currentPreviewHtml);
        pushDiagrams(currentDiagrams, generation);
    }

    /**
     * Fills in each PlantUML placeholder. Cached diagrams land synchronously so an
     * unchanged diagram never flickers; the rest arrive from the render thread.
     */
    private void pushDiagrams(List<MarkdownService.Diagram> diagrams, int generation) {
        for (MarkdownService.Diagram diagram : diagrams) {
            String cached = diagramService.cached(diagram.source());
            if (cached != null) {
                setDiagram(diagram.id(), cached, generation);
                continue;
            }
            diagramService.renderAsync(diagram.source()).thenAccept(svg ->
                    Platform.runLater(() -> setDiagram(diagram.id(), svg, generation)));
        }
    }

    private void setDiagram(String id, String svg, int generation) {
        if (generation != previewGeneration || !previewReady) {
            return; // Document moved on while this diagram was rendering.
        }
        try {
            webView.getEngine().executeScript(
                    "window.__mdSetDiagram(" + toJsStringLiteral(id) + "," + toJsStringLiteral(svg) + ");");
        } catch (RuntimeException e) {
            // Preview page was replaced mid-flight; the reload path re-pushes diagrams.
        }
    }

    /**
     * Evaluates the bundled mermaid build inside the preview page. It is injected via
     * executeScript rather than a &lt;script&gt; tag because the minified source contains
     * "&lt;/script&gt;" inside string literals, which would terminate the tag early.
     */
    private void injectMermaid() {
        try (InputStream in = getClass().getResourceAsStream("/js/mermaid.min.js")) {
            if (in == null) {
                return; // Mermaid blocks stay as plain text; everything else still works.
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            webView.getEngine().executeScript(source);
            webView.getEngine().executeScript(
                    "mermaid.initialize({startOnLoad:false, securityLevel:'strict', "
                            + "theme:'default', fontFamily:'Segoe UI, Helvetica, Arial, sans-serif'});");
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: mermaid unavailable - " + e);
        }
    }

    private void applyPreviewHtml(String html) {
        if (!previewReady) {
            // Shell still loading; the load-worker listener re-applies once it succeeds.
            return;
        }
        try {
            webView.getEngine().executeScript("window.__mdSetBody(" + toJsStringLiteral(html) + ");");
        } catch (RuntimeException e) {
            // The hook is gone (page replaced) - rebuild the shell and retry on load.
            loadPreviewShell();
        }
    }

    /** Escapes a Java string into a double-quoted JavaScript string literal. */
    private static String toJsStringLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // Control chars, plus U+2028/U+2029 which JS treats as line terminators.
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String buildPreviewShell() {
        return "<!DOCTYPE html>" +
            "<html>" +
            "<head>" +
                "<meta charset=\"UTF-8\">" +
                "<style>" +
                    // Both palettes ship in the page; __mdSetTheme flips the data-theme
                    // attribute, so switching costs no reload and keeps scroll position.
                    ":root {" +
                        "--bg: #ffffff;" +
                        "--fg: #24292e;" +
                        "--muted: #6a737d;" +
                        "--rule: #eaecef;" +
                        "--line: #dfe2e5;" +
                        "--code-bg: #f6f8fa;" +
                        "--stripe: #f6f8fa;" +
                        "--link: #0366d6;" +
                        "--err-fg: #b31d28;" +
                        "--err-bg: #ffeef0;" +
                        "--err-line: #fdaeb7;" +
                    "}" +
                    "html[data-theme=\"dark\"] {" +
                        "--bg: #0d1117;" +
                        "--fg: #c9d1d9;" +
                        "--muted: #8b949e;" +
                        "--rule: #21262d;" +
                        "--line: #30363d;" +
                        "--code-bg: #161b22;" +
                        "--stripe: #161b22;" +
                        "--link: #58a6ff;" +
                        "--err-fg: #ff7b72;" +
                        "--err-bg: #2d1214;" +
                        "--err-line: #6e2b30;" +
                    "}" +
                    "body {" +
                        "font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;" +
                        "padding: 20px;" +
                        "line-height: 1.6;" +
                        "color: var(--fg);" +
                        "background: var(--bg);" +
                        "word-wrap: break-word;" +
                    "}" +
                    "h1, h2, h3, h4, h5, h6 {" +
                        "margin-top: 24px;" +
                        "margin-bottom: 16px;" +
                        "font-weight: 600;" +
                        "line-height: 1.25;" +
                    "}" +
                    "h1 { font-size: 2em; border-bottom: 1px solid var(--rule); padding-bottom: 0.3em; }" +
                    "h2 { font-size: 1.5em; border-bottom: 1px solid var(--rule); padding-bottom: 0.3em; }" +
                    "h3 { font-size: 1.25em; }" +
                    "code {" +
                        "background-color: var(--code-bg);" +
                        "padding: 0.2em 0.4em;" +
                        "border-radius: 3px;" +
                        "font-family: SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;" +
                        "font-size: 85%;" +
                    "}" +
                    "pre {" +
                        "background-color: var(--code-bg);" +
                        "padding: 16px;" +
                        "border-radius: 3px;" +
                        "overflow: auto;" +
                    "}" +
                    "pre code {" +
                        "background-color: transparent;" +
                        "padding: 0;" +
                        "font-size: 100%;" +
                    "}" +
                    "blockquote {" +
                        "border-left: 4px solid var(--line);" +
                        "padding: 0 1em;" +
                        "color: var(--muted);" +
                        "margin-left: 0;" +
                    "}" +
                    "table {" +
                        "border-collapse: collapse;" +
                        "margin: 16px 0;" +
                        "display: block;" +
                        "overflow: auto;" +
                    "}" +
                    "th, td {" +
                        "border: 1px solid var(--line);" +
                        "padding: 6px 13px;" +
                    "}" +
                    "tr:nth-child(2n) {" +
                        "background-color: var(--stripe);" +
                    "}" +
                    "hr { height: 1px; border: 0; background-color: var(--line); margin: 24px 0; }" +
                    "img { max-width: 100%; }" +
                    "a { color: var(--link); text-decoration: none; }" +
                    "a:hover { text-decoration: underline; }" +
                    "ul, ol { padding-left: 2em; }" +
                    // Diagrams: SVG scales down to the pane, never forces horizontal scroll.
                    // The card stays light in both themes because PlantUML and mermaid bake
                    // dark strokes and text into the SVG - on a dark card they vanish.
                    ".mdv-diagram, pre.mermaid {" +
                        "margin: 16px 0;" +
                        "padding: 8px;" +
                        "background: #ffffff;" +
                        "border: 1px solid var(--line);" +
                        "border-radius: 3px;" +
                        "overflow-x: auto;" +
                        "text-align: center;" +
                    "}" +
                    ".mdv-diagram svg, pre.mermaid svg { max-width: 100%; height: auto; }" +
                    ".mdv-diagram-pending {" +
                        "color: var(--muted);" +
                        "font-style: italic;" +
                        "text-align: left;" +
                        "background: var(--code-bg);" +
                    "}" +
                    ".mdv-diagram-error {" +
                        "color: var(--err-fg);" +
                        "background: var(--err-bg);" +
                        "border: 1px solid var(--err-line);" +
                        "border-radius: 3px;" +
                        "padding: 12px;" +
                        "text-align: left;" +
                        "font-family: SFMono-Regular, Consolas, monospace;" +
                        "font-size: 90%;" +
                    "}" +
                "</style>" +
                "<script>" +
                    "window.__mdSetTheme = function (theme) {" +
                        "document.documentElement.setAttribute('data-theme', theme);" +
                    "};" +
                    "window.__mdRunMermaid = function () {" +
                        "if (!window.mermaid) { return; }" +
                        "try {" +
                            "var p = mermaid.run({ querySelector: '.mermaid' });" +
                            "if (p && p.catch) { p.catch(function () {}); }" +
                        "} catch (e) {}" +
                    "};" +
                    "window.__mdSetBody = function (html) {" +
                        "document.body.innerHTML = html;" +
                        "window.__mdRunMermaid();" +
                    "};" +
                    "window.__mdSetDiagram = function (id, svg) {" +
                        "var el = document.getElementById(id);" +
                        "if (!el) { return; }" +
                        "el.className = 'mdv-diagram';" +
                        "el.innerHTML = svg;" +
                    "};" +
                "</script>" +
            "</head>" +
            "<body></body>" +
            "</html>";
    }

    // ------------------------------------------------------------------ files

    @FXML
    private void handleNewFile() {
        if (checkUnsavedChanges()) {
            textEditor.clear();
            currentFile = null;
            isModified = false;
            MainApp.setCurrentFile(null);
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
            textEditor.positionCaret(0);
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
            Platform.exit();
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

    // ------------------------------------------------------------------ modes

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

    /**
     * Swaps the actual SplitPane children per mode. Collapsing via divider positions
     * alone does not work: both panes keep their minimum width and stay visible.
     */
    private void updateLayout() {
        modeLabel.setText("Mode: " + currentMode.name().replace("_", " "));

        splitPane.getItems().clear();
        switch (currentMode) {
            case RAW -> splitPane.getItems().add(textEditor);
            case SPLIT -> {
                splitPane.getItems().addAll(textEditor, webView);
                splitPane.setDividerPositions(0.5);
                Platform.runLater(() -> splitPane.setDividerPositions(0.5));
            }
            case FULL_PREVIEW -> splitPane.getItems().add(webView);
        }

        // Content may have changed while RAW mode suppressed preview updates.
        if (currentMode != EditorMode.RAW) {
            previewDebounce.stop();
            updatePreview();
        }
    }

    // ----------------------------------------------------------------- status

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
        } else {
            title += "Untitled";
        }
        if (isModified) title += " *";
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
            } else if (result.isEmpty()) {
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

    /** Releases the background diagram renderer; called from {@link MainApp#stop()}. */
    public void dispose() {
        diagramService.shutdown();
    }
}
