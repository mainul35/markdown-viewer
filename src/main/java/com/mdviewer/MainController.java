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
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;
import com.mdviewer.ui.DocumentView;
import com.mdviewer.ui.FileTreePanel;
import com.mdviewer.ui.WorkspaceView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainController {

    @FXML
    private BorderPane rootPane;

    @FXML
    private SplitPane mainSplit;

    @FXML
    private StackPane explorerHost;

    @FXML
    private TabPane workspaceTabs;

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

    @FXML
    private MenuItem explorerMenuItem;

    private Stage primaryStage;
    private HostServices hostServices;
    private EditorMode currentMode = EditorMode.SPLIT;

    private FileTreePanel fileTreePanel;
    private WebView webView;
    private SplitPane editorSplit;

    private final List<WorkspaceView> workspaces = new ArrayList<>();
    private int untitledCounter = 0;

    /** Suppresses the "modified" flag while a document's text is being loaded. */
    private boolean loadingDocument = false;

    /** Remembered so hiding and re-showing the explorer keeps its width. */
    private double explorerDivider = 0.2;

    /** Debounce so typing does not re-render the preview on every keystroke. */
    private PauseTransition previewDebounce;

    /** Pending restore of the status bar after a transient message. */
    private PauseTransition statusRestore;

    /** True once the preview shell page (CSS + injection hooks) has finished loading. */
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

    /**
     * Tab ceilings. Past these the strips stop being scannable, which is the whole point
     * of grouping files by workspace; opening more is refused with a status-bar message
     * rather than silently evicting something the user still has open.
     */
    private static final int MAX_WORKSPACES = 10;
    private static final int MAX_DOCUMENTS_PER_WORKSPACE = 20;

    private boolean darkMode = false;

    public enum EditorMode {
        RAW, SPLIT, FULL_PREVIEW
    }

    @FXML
    public void initialize() {
        webView = new WebView();
        webView.setMinWidth(0);

        editorSplit = new SplitPane();
        editorSplit.getStyleClass().add("editor-split");

        fileTreePanel = new FileTreePanel();
        fileTreePanel.setOnFileActivated(path -> openFile(path.toFile()));
        fileTreePanel.setOnReveal(this::handleRevealInTree);
        explorerHost.getChildren().add(fileTreePanel);

        previewDebounce = new PauseTransition(Duration.millis(200));
        previewDebounce.setOnFinished(e -> updatePreview());

        workspaceTabs.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> onActiveDocumentChanged());
        workspaceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        initPreviewEngine();

        updateWordCount();
        updateStatus();
        updateLayout();
        applyTheme();
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    // ------------------------------------------------------- workspace / tabs

    private WorkspaceView activeWorkspace() {
        Tab tab = workspaceTabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : (WorkspaceView) tab.getUserData();
    }

    private DocumentView activeDocument() {
        WorkspaceView workspace = activeWorkspace();
        if (workspace == null) {
            return null;
        }
        Tab tab = workspace.getDocumentTabs().getSelectionModel().getSelectedItem();
        return tab == null ? null : (DocumentView) tab.getUserData();
    }

    /**
     * Finds the workspace that owns {@code file}, creating one rooted at its folder.
     *
     * @return null if a new workspace was needed but the workspace limit is reached
     */
    private WorkspaceView workspaceFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (WorkspaceView workspace : workspaces) {
            if (workspace.contains(normalized)) {
                return workspace;
            }
        }
        return addWorkspace(normalized.getParent());
    }

    /** @return the workspace, or null if {@link #MAX_WORKSPACES} is already open */
    private WorkspaceView addWorkspace(Path root) {
        Path normalized = root == null ? null : root.toAbsolutePath().normalize();
        if (normalized != null) {
            for (WorkspaceView existing : workspaces) {
                if (normalized.equals(existing.getRoot())) {
                    return existing;
                }
            }
        }
        if (workspaces.size() >= MAX_WORKSPACES) {
            setTransientStatus("Workspace limit reached (" + MAX_WORKSPACES
                    + "). Close a workspace tab before opening another folder.");
            return null;
        }
        WorkspaceView workspace = new WorkspaceView(normalized);
        workspace.getDocumentTabs().getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> onActiveDocumentChanged());
        workspace.getTab().setOnCloseRequest(event -> {
            if (!closeWorkspace(workspace)) {
                event.consume();
            }
        });

        workspaces.add(workspace);
        workspaceTabs.getTabs().add(workspace.getTab());
        fileTreePanel.addWorkspaceRoot(normalized);
        return workspace;
    }

    /**
     * Moves the shared editor/preview area into the selected file tab. Only one tab can
     * hold it at a time - a JavaFX node has a single parent - so this is what makes the
     * tabs act as selectors over one editor rather than N editors and N WebViews.
     */
    private void mountEditorArea() {
        DocumentView active = activeDocument();
        for (WorkspaceView workspace : workspaces) {
            for (DocumentView document : workspace.getDocuments()) {
                if (document != active && document.getTab().getContent() == editorSplit) {
                    document.getTab().setContent(null);
                }
            }
        }
        if (active != null && active.getTab().getContent() != editorSplit) {
            active.getTab().setContent(editorSplit);
        }
    }

    private void onActiveDocumentChanged() {
        mountEditorArea();
        updateLayout();
        updateWordCount();
        updateStatus();
        updateTitle();
        MainApp.setCurrentFile(activeDocument() != null && activeDocument().getPath() != null
                ? activeDocument().getPath().toFile() : null);
    }

    private void selectDocument(WorkspaceView workspace, DocumentView document) {
        workspaceTabs.getSelectionModel().select(workspace.getTab());
        workspace.getDocumentTabs().getSelectionModel().select(document.getTab());
        onActiveDocumentChanged();
    }

    private DocumentView createDocument(WorkspaceView workspace, Path path, String text) {
        DocumentView document = new DocumentView(path, "Untitled-" + (++untitledCounter));

        loadingDocument = true;
        document.getEditor().setText(text);
        document.getEditor().positionCaret(0);
        loadingDocument = false;

        document.getEditor().textProperty().addListener((obs, old, now) -> {
            if (!loadingDocument) {
                document.setModified(true);
            }
            if (document == activeDocument()) {
                updateWordCount();
                updateTitle();
                schedulePreviewUpdate();
            }
        });

        document.getTab().setOnCloseRequest(event -> {
            if (!closeDocument(workspace, document)) {
                event.consume();
            }
        });

        workspace.add(document);
        return document;
    }

    // ------------------------------------------------------------------ files

    @FXML
    private void handleNewFile() {
        WorkspaceView workspace = activeWorkspace();
        if (workspace == null) {
            workspace = addWorkspace(null);
        }
        if (workspace == null) {
            return; // Workspace limit reached.
        }
        if (workspace.getDocuments().size() >= MAX_DOCUMENTS_PER_WORKSPACE) {
            setTransientStatus("\"" + workspace.getDisplayName() + "\" already has "
                    + MAX_DOCUMENTS_PER_WORKSPACE + " files open. Close one first.");
            return;
        }
        DocumentView document = createDocument(workspace, null, "");
        selectDocument(workspace, document);
    }

    @FXML
    private void handleOpenFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Markdown File");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown Files", "*.md", "*.markdown", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        DocumentView active = activeDocument();
        if (active != null && active.getBaseDir() != null) {
            fileChooser.setInitialDirectory(active.getBaseDir().toFile());
        }

        File selectedFile = fileChooser.showOpenDialog(primaryStage);
        if (selectedFile != null) {
            openFile(selectedFile);
        }
    }

    /** Adds a folder as a workspace without opening anything from it. */
    @FXML
    private void handleOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Folder as Workspace");
        File folder = chooser.showDialog(primaryStage);
        if (folder != null) {
            WorkspaceView workspace = addWorkspace(folder.toPath());
            if (workspace != null) {
                workspaceTabs.getSelectionModel().select(workspace.getTab());
            }
        }
    }

    public void openFile(File file) {
        if (file == null) {
            return;
        }
        Path path = file.toPath().toAbsolutePath().normalize();

        WorkspaceView workspace = workspaceFor(path);
        if (workspace == null) {
            return; // Workspace limit reached; addWorkspace already reported it.
        }
        DocumentView existing = workspace.findDocument(path);
        if (existing != null) {
            selectDocument(workspace, existing); // Already open - just focus it.
            return;
        }
        if (workspace.getDocuments().size() >= MAX_DOCUMENTS_PER_WORKSPACE) {
            workspaceTabs.getSelectionModel().select(workspace.getTab());
            setTransientStatus("\"" + workspace.getDisplayName() + "\" already has "
                    + MAX_DOCUMENTS_PER_WORKSPACE + " files open. Close one before opening "
                    + path.getFileName() + ".");
            return;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            DocumentView document = createDocument(workspace, path, content);
            selectDocument(workspace, document);
        } catch (IOException e) {
            showAlert("Error", "Failed to open file: " + e.getMessage());
        }
    }

    @FXML
    private void handleSaveFile() {
        DocumentView document = activeDocument();
        if (document == null) {
            return;
        }
        if (document.getPath() == null) {
            handleSaveAs();
        } else {
            saveDocument(document, document.getPath());
        }
    }

    @FXML
    private void handleSaveAs() {
        DocumentView document = activeDocument();
        if (document == null) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Markdown File");
        fileChooser.setInitialFileName(document.getPath() != null
                ? document.getDisplayName() : "document.md");
        if (document.getBaseDir() != null) {
            fileChooser.setInitialDirectory(document.getBaseDir().toFile());
        }
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown Files", "*.md", "*.markdown", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File selectedFile = fileChooser.showSaveDialog(primaryStage);
        if (selectedFile != null) {
            saveDocument(document, selectedFile.toPath().toAbsolutePath().normalize());
        }
    }

    private boolean saveDocument(DocumentView document, Path target) {
        try {
            Files.writeString(target, document.getEditor().getText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            showAlert("Error", "Failed to save file: " + e.getMessage());
            return false;
        }

        boolean pathChanged = !target.equals(document.getPath());
        document.setPath(target);
        document.setModified(false);

        if (pathChanged) {
            rehomeDocument(document, target);
        }
        MainApp.setCurrentFile(target.toFile());
        updateTitle();
        updateStatus();
        updatePreview(); // Base directory for relative images may have moved.
        return true;
    }

    /** After Save As, a document may belong to a different workspace than the one it sat in. */
    private void rehomeDocument(DocumentView document, Path target) {
        WorkspaceView current = owningWorkspace(document);
        if (current != null && current.contains(target)) {
            return;
        }
        WorkspaceView destination = workspaceFor(target);
        if (destination == null || destination == current) {
            return; // At the workspace limit the document simply stays where it is.
        }
        if (current != null) {
            document.getTab().setContent(null);
            current.remove(document);
        }
        destination.add(document);
        selectDocument(destination, document);
    }

    private WorkspaceView owningWorkspace(DocumentView document) {
        for (WorkspaceView workspace : workspaces) {
            if (workspace.getDocuments().contains(document)) {
                return workspace;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- closing

    @FXML
    private void handleCloseDocument() {
        WorkspaceView workspace = activeWorkspace();
        DocumentView document = activeDocument();
        if (workspace != null && document != null && closeDocument(workspace, document)) {
            // closeDocument only detaches the model; closing via the tab's X leaves tab
            // removal to JavaFX, so the menu path has to remove the tab itself.
            workspace.getDocumentTabs().getTabs().remove(document.getTab());
            onActiveDocumentChanged();
        }
    }

    @FXML
    private void handleCloseWorkspace() {
        WorkspaceView workspace = activeWorkspace();
        if (workspace != null && closeWorkspace(workspace)) {
            removeWorkspace(workspace);
        }
    }

    /**
     * Detaches a document from its workspace after confirming unsaved changes. The tab
     * itself is left alone: when this runs from the tab's close request, JavaFX removes
     * the tab, and removing it here as well would be a double removal.
     *
     * @return true if the document may be closed (saved, discarded, or unmodified)
     */
    private boolean closeDocument(WorkspaceView workspace, DocumentView document) {
        if (!confirmDiscard(document)) {
            return false;
        }
        if (document.getTab().getContent() == editorSplit) {
            document.getTab().setContent(null);
        }
        workspace.getDocuments().remove(document);
        Platform.runLater(this::onActiveDocumentChanged);
        return true;
    }

    private boolean closeWorkspace(WorkspaceView workspace) {
        for (DocumentView document : List.copyOf(workspace.getDocuments())) {
            if (!confirmDiscard(document)) {
                return false;
            }
            document.getTab().setContent(null);
        }
        Platform.runLater(() -> {
            removeWorkspace(workspace);
            onActiveDocumentChanged();
        });
        return true;
    }

    private void removeWorkspace(WorkspaceView workspace) {
        workspaces.remove(workspace);
        workspaceTabs.getTabs().remove(workspace.getTab());
        fileTreePanel.removeWorkspaceRoot(workspace.getRoot());
    }

    /** @return true if it is safe to discard this document's state */
    private boolean confirmDiscard(DocumentView document) {
        if (!document.isModified()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText(document.getDisplayName() + " has unsaved changes.");
        alert.setContentText("Do you want to save before closing?");

        ButtonType save = new ButtonType("Save");
        ButtonType discard = new ButtonType("Discard Changes");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);

        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        if (result.get() == save) {
            if (document.getPath() == null) {
                handleSaveAs();
                return !document.isModified();
            }
            return saveDocument(document, document.getPath());
        }
        return true; // Discard
    }

    @FXML
    private void handleExit() {
        for (WorkspaceView workspace : List.copyOf(workspaces)) {
            for (DocumentView document : List.copyOf(workspace.getDocuments())) {
                if (!confirmDiscard(document)) {
                    return;
                }
            }
        }
        Platform.exit();
    }

    @FXML
    private void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("About MDViewer");
        alert.setHeaderText("MDViewer - Markdown Editor");
        alert.setContentText("Version 1.0.0\nA professional desktop Markdown editor built with JavaFX.");
        alert.showAndWait();
    }

    // -------------------------------------------------------------- explorer

    @FXML
    private void handleRevealInTree() {
        DocumentView document = activeDocument();
        if (document == null || document.getPath() == null) {
            setTransientStatus("Nothing to reveal - the current document has not been saved yet.");
            return;
        }
        if (!isExplorerVisible()) {
            showExplorer(true);
        }
        if (!fileTreePanel.reveal(document.getPath())) {
            setTransientStatus("Could not locate " + document.getDisplayName() + " in the tree.");
        }
    }

    @FXML
    private void handleToggleExplorer() {
        showExplorer(!isExplorerVisible());
    }

    private boolean isExplorerVisible() {
        return mainSplit.getItems().contains(explorerHost);
    }

    private void showExplorer(boolean visible) {
        if (visible == isExplorerVisible()) {
            return;
        }
        if (visible) {
            mainSplit.getItems().add(0, explorerHost);
            mainSplit.setDividerPositions(explorerDivider);
            Platform.runLater(() -> mainSplit.setDividerPositions(explorerDivider));
        } else {
            if (mainSplit.getDividerPositions().length > 0) {
                explorerDivider = mainSplit.getDividerPositions()[0];
            }
            mainSplit.getItems().remove(explorerHost);
        }
        explorerMenuItem.setText(visible ? "Hide Explorer" : "Show Explorer");
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

        DocumentView document = activeDocument();
        editorSplit.getItems().clear();
        if (document == null) {
            if (currentMode != EditorMode.RAW) {
                previewDebounce.stop();
                updatePreview();
            }
            return;
        }

        switch (currentMode) {
            case RAW -> editorSplit.getItems().add(document.getEditor());
            case SPLIT -> {
                editorSplit.getItems().addAll(document.getEditor(), webView);
                editorSplit.setDividerPositions(0.5);
                Platform.runLater(() -> editorSplit.setDividerPositions(0.5));
            }
            case FULL_PREVIEW -> editorSplit.getItems().add(webView);
        }

        // Content may have changed while RAW mode suppressed preview updates.
        if (currentMode != EditorMode.RAW) {
            previewDebounce.stop();
            updatePreview();
        }
    }

    // ---------------------------------------------------------------- preview

    /**
     * Loads the preview "shell" (CSS + JS hooks) exactly once. Document content is
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
            String loc = newLoc.toLowerCase(Locale.ROOT);
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
        DocumentView document = activeDocument();
        String markdown = document == null ? "" : document.getEditor().getText();
        Path baseDir = document == null ? null : document.getBaseDir();

        MarkdownService.Result result = markdownService.render(markdown, baseDir);
        currentPreviewHtml = result.html();
        currentDiagrams = result.diagrams();
        int generation = ++previewGeneration;

        applyPreviewHtml(currentPreviewHtml);
        pushDiagrams(currentDiagrams, generation);
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

    // ----------------------------------------------------------------- status

    private void updateWordCount() {
        DocumentView document = activeDocument();
        String text = document == null ? "" : document.getEditor().getText().trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        wordCountLabel.setText("Words: " + words);
    }

    private void updateStatus() {
        DocumentView document = activeDocument();
        String encoding = document != null && document.getPath() != null ? "UTF-8" : "N/A";
        String fileName = document == null ? "No document" : document.getDisplayName();
        statusLabel.setText("File: " + fileName + " | Encoding: " + encoding);
    }

    /**
     * Shows a one-off message in the status bar for a few seconds.
     *
     * <p>The pending restore is replaced rather than added to: otherwise an earlier
     * message's timer would fire while a newer message is showing and clear it early.
     */
    private void setTransientStatus(String message) {
        statusLabel.setText(message);
        if (statusRestore != null) {
            statusRestore.stop();
        }
        statusRestore = new PauseTransition(Duration.seconds(4));
        statusRestore.setOnFinished(e -> updateStatus());
        statusRestore.play();
    }

    private void updateTitle() {
        if (primaryStage == null) {
            return;
        }
        DocumentView document = activeDocument();
        String title = "MDViewer - ";
        title += document == null ? "No document" : document.getDisplayName();
        if (document != null && document.isModified()) {
            title += " *";
        }
        primaryStage.setTitle(title);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(primaryStage);
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
