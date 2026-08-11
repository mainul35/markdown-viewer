package com.mdviewer;

import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import com.mdviewer.ui.FindBar;
import com.mdviewer.ui.MarkdownFiles;
import com.mdviewer.ui.WorkspaceView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    private FindBar findBar;
    private VBox editorPane;
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

    /** The document the preview is currently showing, which may lag the active tab. */
    private DocumentView previewedDocument;

    /** Offset the preview should sit at once the pending body update lands. */
    private double pendingScrollY = 0;

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

        findBar = new FindBar();
        findBar.installEscapeHandler();

        // The editor and the find bar share a column so the bar sits directly above the
        // text it searches, and the editor keeps all remaining height.
        editorPane = new VBox();
        editorPane.getStyleClass().add("editor-pane");

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

        mountEditorPane(document);
        switch (currentMode) {
            case RAW -> editorSplit.getItems().add(editorPane);
            case SPLIT -> {
                editorSplit.getItems().addAll(editorPane, webView);
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

    /** Rebuilds the editor column for the active document and re-points the find bar. */
    private void mountEditorPane(DocumentView document) {
        editorPane.getChildren().setAll(findBar, document.getEditor());
        VBox.setVgrow(document.getEditor(), Priority.ALWAYS);
        findBar.setTarget(document.getEditor());
    }

    // -------------------------------------------------------------------- find

    @FXML
    private void handleFind() {
        openFindBar(false);
    }

    @FXML
    private void handleFindReplace() {
        openFindBar(true);
    }

    /** Find needs a visible editor, so Full Preview drops back to Split first. */
    private void openFindBar(boolean withReplace) {
        if (activeDocument() == null) {
            setTransientStatus("Open a document before searching.");
            return;
        }
        if (currentMode == EditorMode.FULL_PREVIEW) {
            currentMode = EditorMode.SPLIT;
            updateLayout();
        }
        findBar.show(withReplace);
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
            if (loc.startsWith("file:")) {
                // Capture before cancelling: once the navigation tears the page down the
                // offset is unreadable, and this is the position the reader returns to.
                rememberScroll(previewedDocument);
                engine.getLoadWorker().cancel();
                followLocalLink(newLoc);
            } else if (loc.startsWith("http://") || loc.startsWith("https://") || loc.startsWith("mailto:")) {
                engine.getLoadWorker().cancel();
                if (hostServices != null) {
                    hostServices.showDocument(newLoc);
                }
                Platform.runLater(this::loadPreviewShell);
            }
        });

        loadPreviewShell();
    }

    /**
     * Opens a document linked from the preview and reveals it in the explorer.
     *
     * <p>Markdown files cross-reference each other constantly, so a link to a sibling
     * document should navigate the viewer rather than the WebView. The shell is reloaded
     * because the cancelled navigation may already have torn the page down; reloading
     * first means the newly opened document is applied when the shell comes back up.
     */
    private void followLocalLink(String url) {
        Path target;
        try {
            // Path.of(URI) rejects a fragment, and "file.md#section" is a normal link.
            int hash = url.indexOf('#');
            String bare = hash >= 0 ? url.substring(0, hash) : url;
            target = Path.of(URI.create(bare)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return; // Not a usable local path; leave the preview alone.
        }

        Platform.runLater(() -> {
            // The cancelled navigation leaves the page unusable - executeScript stops
            // having any effect rather than throwing, so the recovery path in
            // applyPreviewHtml never triggers. Rebuilding the shell is the reliable route.
            loadPreviewShell();
            if (!Files.isRegularFile(target)) {
                setTransientStatus("Linked file not found: " + target);
                return;
            }
            if (!MarkdownFiles.isMarkdown(target)) {
                setTransientStatus("Not a Markdown file, so not opened: " + target.getFileName());
                return;
            }
            openFile(target.toFile());
            handleRevealInTree();
        });
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

        // Decide where the preview should sit once the new body is in place: re-rendering
        // the same document keeps the reader where they are, while switching documents
        // resumes that document's own last position instead of jumping to the top.
        if (document != previewedDocument) {
            rememberScroll(previewedDocument);
            previewedDocument = document;
            pendingScrollY = document == null ? 0 : document.getPreviewScrollY();
        } else {
            double y = readScrollY();
            if (y >= 0) {
                pendingScrollY = y;
            }
        }

        String markdown = document == null ? "" : document.getEditor().getText();
        Path baseDir = document == null ? null : document.getBaseDir();

        MarkdownService.Result result = markdownService.render(markdown, baseDir);
        currentPreviewHtml = result.html();
        currentDiagrams = result.diagrams();
        int generation = ++previewGeneration;

        applyPreviewHtml(currentPreviewHtml);
        pushDiagrams(currentDiagrams, generation);
    }

    /** Reads the preview's current offset, or -1 when the page cannot be queried. */
    private double readScrollY() {
        if (!previewReady) {
            return -1;
        }
        try {
            Object value = webView.getEngine().executeScript("window.__mdScrollY()");
            return value instanceof Number n ? n.doubleValue() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Stores the on-screen position against the document currently displayed. An
     * unreadable page leaves the remembered position alone rather than resetting it to
     * the top - that is exactly the case when a link click has already torn the page down.
     */
    private void rememberScroll(DocumentView document) {
        if (document == null) {
            return;
        }
        double y = readScrollY();
        if (y >= 0) {
            document.setPreviewScrollY(y);
        }
    }

    private void applyPreviewHtml(String html) {
        if (!previewReady) {
            // Shell still loading; the load-worker listener re-applies once it succeeds.
            return;
        }
        try {
            webView.getEngine().executeScript("window.__mdSetBody(" + toJsStringLiteral(html) + ");");
            webView.getEngine().executeScript("window.__mdScrollTo(" + (long) pendingScrollY + ");");
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
            // A diagram is far taller than its placeholder, so everything below it shifts.
            // Re-anchor unless the reader has scrolled since the body was applied.
            webView.getEngine().executeScript("window.__mdKeepScroll(" + (long) pendingScrollY + ");");
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

    /**
     * The preview page: palette, typography and the JS hooks the controller drives.
     *
     * <p>Design direction is "drafting plate" - cool vellum rather than warm cream, a
     * blueprint teal accent, and fenced blocks presented as labelled technical plates. The
     * subject is long-form architecture documentation, so the priority is a page that stays
     * readable for an hour, not one that is striking for a second.
     *
     * <p>Fonts are system faces only: this is an offline desktop app with no network at
     * render time, so a webfont would simply fail to load. Sitka is a reading face that
     * ships with Windows and gives documents a plate-like voice that a UI sans cannot.
     */
    private String buildPreviewShell() {
        String css = """
            :root {
              --paper:#F6F8FA; --ink:#16202B; --ink-soft:#5A6875;
              --rule:#DFE5EC; --line:#C7D2DE;
              --accent:#0B6E7F; --accent-ink:#0A5A68; --accent-soft:#0B6E7F14;
              --mark:#A23B2E;
              --code-bg:#EFF3F7; --code-ink:#1B2A38; --stripe:#F1F5F9;
              --err-fg:#8C2F22; --err-bg:#FBEDEA; --err-line:#E4BDB5;
              --plate-shadow:0 1px 2px rgba(22,32,43,.05), 0 10px 24px -14px rgba(22,32,43,.22);
              --mono:"Cascadia Code","Cascadia Mono",Consolas,"Liberation Mono",monospace;
              --display:"Sitka Heading","Sitka Text",Cambria,Georgia,serif;
              --body:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,sans-serif;
            }
            html[data-theme="dark"] {
              --paper:#0F1620; --ink:#D7DEE6; --ink-soft:#8A9AAA;
              --rule:#22303F; --line:#2C3D4E;
              --accent:#3FB8CC; --accent-ink:#5FCBDD; --accent-soft:#3FB8CC1F;
              --mark:#E08A7B;
              --code-bg:#131C26; --code-ink:#C9D6E2; --stripe:#141E29;
              --err-fg:#F2A79A; --err-bg:#2A1614; --err-line:#5E2E28;
              --plate-shadow:0 1px 2px rgba(0,0,0,.35), 0 12px 28px -16px rgba(0,0,0,.75);
            }

            body {
              margin:0; padding:40px 44px 120px;
              background:var(--paper); color:var(--ink);
              font-family:var(--body); font-size:16px; line-height:1.68;
              -webkit-font-smoothing:antialiased; word-wrap:break-word;
            }

            /* Prose holds a comfortable measure; plates and tables break out of it.
               The measure is in rem, not ch: ch is relative to each element's own font, so
               headings would silently get a far wider column than the paragraphs under
               them and the two would stop sharing a left edge. */
            body > * { max-width:40rem; margin-left:auto; margin-right:auto; }
            body > .mdv-code, body > table, body > .mdv-diagram,
            body > pre.mermaid, body > .mdv-diagram-error, body > hr { max-width:none; }

            h1,h2,h3,h4,h5,h6 {
              font-family:var(--display); font-weight:600; line-height:1.22;
              letter-spacing:-.01em; margin:2.1em auto .65em; color:var(--ink);
            }
            h1 { font-size:2.3rem; letter-spacing:-.022em; margin-top:.2em; }
            h1::after {
              content:""; display:block; width:3.25rem; height:3px;
              margin-top:.6rem; background:var(--accent); border-radius:2px;
            }
            h2 { font-size:1.58rem; padding-bottom:.32em; border-bottom:1px solid var(--rule); }
            h3 { font-size:1.24rem; }
            h4 { font-size:1.05rem; color:var(--ink-soft); }
            h5,h6 { font-size:.95rem; color:var(--ink-soft); }

            p { margin:0 auto 1.15em; }
            ul,ol { padding-left:1.5em; margin:0 auto 1.15em; }
            li { margin:.3em 0; }
            li::marker { color:var(--accent); }

            strong { font-weight:650; }
            hr { height:1px; border:0; background:var(--rule); margin:2.6em auto; }

            a {
              color:var(--accent-ink); text-decoration:none;
              border-bottom:1px solid var(--accent-soft);
              transition:border-color .12s ease, color .12s ease;
            }
            a:hover { border-bottom-color:var(--accent); }
            a:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }

            /* Inline code reads as an identifier, not as body text. */
            :not(pre) > code {
              font-family:var(--mono); font-size:.86em;
              background:var(--accent-soft); color:var(--accent-ink);
              padding:.14em .42em; border-radius:4px;
            }

            /* The signature: a fenced block is a labelled plate, captioned with its own
               language tag, hung off an accent rule. */
            .mdv-code {
              margin:1.7em auto; background:var(--code-bg);
              border:1px solid var(--rule); border-left:3px solid var(--accent);
              border-radius:7px; overflow:hidden; box-shadow:var(--plate-shadow);
            }
            .mdv-code-lang {
              display:block; padding:7px 16px;
              font-family:var(--mono); font-size:10.5px; font-weight:600;
              letter-spacing:.16em; text-transform:uppercase; color:var(--ink-soft);
              border-bottom:1px solid var(--rule);
            }
            .mdv-code pre { margin:0; padding:16px 18px; background:none; overflow-x:auto; }
            pre code {
              font-family:var(--mono); font-size:13.5px; line-height:1.62;
              color:var(--code-ink); background:none; padding:0;
            }

            blockquote {
              margin:1.6em auto; padding:.1em 0 .1em 1.15em;
              border-left:3px solid var(--mark); color:var(--ink-soft);
            }
            blockquote p:last-child { margin-bottom:0; }

            table {
              display:block; overflow-x:auto; border-collapse:collapse;
              margin:1.8em auto; font-size:.94em;
              border:1px solid var(--rule); border-radius:7px;
            }
            th,td { padding:9px 15px; border-bottom:1px solid var(--rule); text-align:left; }
            thead th {
              background:var(--stripe); color:var(--ink-soft);
              font-size:.84em; font-weight:600; letter-spacing:.07em; text-transform:uppercase;
              border-bottom:1px solid var(--line);
            }
            tbody tr:nth-child(2n) { background:var(--stripe); }
            tbody tr:last-child td { border-bottom:none; }

            img { max-width:100%; border-radius:6px; }

            /* Diagrams share the plate family. The card keeps a light ground in both
               themes: PlantUML and mermaid bake dark strokes into the SVG, which would
               disappear on a dark panel. */
            .mdv-diagram, pre.mermaid {
              position:relative; margin:1.8em auto; padding:34px 16px 16px;
              background:#FFFFFF; color:#16202B;
              border:1px solid var(--rule); border-left:3px solid var(--accent);
              border-radius:7px; overflow-x:auto; text-align:center;
              box-shadow:var(--plate-shadow); font-family:var(--body);
            }
            .mdv-diagram::before, pre.mermaid::before {
              position:absolute; top:9px; left:16px;
              font-family:var(--mono); font-size:10.5px; font-weight:600;
              letter-spacing:.16em; text-transform:uppercase; color:#7B8895;
            }
            .mdv-diagram::before { content:"plantuml"; }
            pre.mermaid::before { content:"mermaid"; }
            .mdv-diagram svg, pre.mermaid svg { max-width:100%; height:auto; }

            .mdv-diagram-pending {
              color:#7B8895; font-style:italic; text-align:left;
              padding:34px 16px 16px; box-shadow:none;
            }
            .mdv-diagram-error {
              margin:1.7em auto; padding:14px 16px;
              color:var(--err-fg); background:var(--err-bg);
              border:1px solid var(--err-line); border-left:3px solid var(--err-fg);
              border-radius:7px; text-align:left;
              font-family:var(--mono); font-size:12.5px;
            }
            """;

        String js = """
            window.__mdSetTheme = function (theme) {
              document.documentElement.setAttribute('data-theme', theme);
            };
            /* Scroll bookkeeping. __mdScrolled records whether the reader moved the page
               themselves, so a diagram arriving late can restore the intended position
               without fighting someone who has already scrolled on. */
            window.__mdScrolled = false;
            window.__mdSuppress = false;
            window.addEventListener('scroll', function () {
              if (!window.__mdSuppress) { window.__mdScrolled = true; }
            });
            window.__mdScrollY = function () {
              return window.pageYOffset || document.documentElement.scrollTop || 0;
            };
            window.__mdScrollTo = function (y) {
              window.__mdSuppress = true;
              window.scrollTo(0, y);
              window.__mdScrolled = false;
              setTimeout(function () { window.__mdSuppress = false; }, 0);
            };
            window.__mdKeepScroll = function (y) {
              if (!window.__mdScrolled) { window.__mdScrollTo(y); }
            };
            window.__mdRunMermaid = function () {
              if (!window.mermaid) { return; }
              try {
                var p = mermaid.run({ querySelector: '.mermaid' });
                if (p && p.catch) { p.catch(function () {}); }
              } catch (e) {}
            };
            window.__mdSetBody = function (html) {
              document.body.innerHTML = html;
              window.__mdRunMermaid();
            };
            window.__mdSetDiagram = function (id, svg) {
              var el = document.getElementById(id);
              if (!el) { return; }
              el.className = 'mdv-diagram';
              el.innerHTML = svg;
            };
            """;

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + css + "</style><script>" + js + "</script></head><body></body></html>";
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
