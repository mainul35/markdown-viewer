package com.mdviewer;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import com.mdviewer.service.DiagramService;
import com.mdviewer.service.ImageRef;
import com.mdviewer.service.MarkdownService;
import com.mdviewer.service.SourceEdits;
import com.mdviewer.service.TableSource;
import com.mdviewer.service.Trash;
import com.mdviewer.ui.DocumentView;
import com.mdviewer.ui.FileTreePanel;
import com.mdviewer.ui.CropDialog;
import com.mdviewer.ui.FindBar;
import com.mdviewer.ui.MarkdownFiles;
import com.mdviewer.ui.PathTreeItem;
import com.mdviewer.ui.PreviewToolbar;
import com.mdviewer.ui.WorkspaceView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    @FXML
    private CheckMenuItem autoRefreshMenuItem;

    /**
     * Re-reads the workspace tree on a timer. The explorer caches directory listings, so a
     * file added outside the app - by a build, a git pull, or another editor - is invisible
     * until something re-reads the folder.
     *
     * <p>Five minutes: long enough that the scan never competes with typing, short enough
     * that a file written in another window is there by the time you look for it. Manual
     * refresh exists for when that is not soon enough.
     */
    private static final Duration WORKSPACE_SYNC_INTERVAL = Duration.minutes(5);

    private Timeline workspaceSync;

    /** Guards against a slow scan overlapping the next tick, or a manual refresh. */
    private boolean workspaceSyncRunning = false;

    private Stage primaryStage;
    private HostServices hostServices;
    private EditorMode currentMode = EditorMode.SPLIT;

    private FileTreePanel fileTreePanel;
    private FindBar findBar;
    private PreviewToolbar previewToolbar;
    private VBox editorPane;
    private VBox previewPane;
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

        previewToolbar = new PreviewToolbar();
        previewToolbar.setOnAction(this::applyFormat);
        previewToolbar.setOnInsertTable(this::insertTable);

        // The preview column: formatting toolbar above the rendered document.
        previewPane = new VBox(previewToolbar, webView);
        previewPane.getStyleClass().add("preview-pane");
        VBox.setVgrow(webView, Priority.ALWAYS);

        installPreviewContextMenu();

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
        fileTreePanel.setFileActions(new ExplorerFileActions());
        fileTreePanel.setOnRefreshRequested(this::handleRefreshWorkspaces);
        explorerHost.getChildren().add(fileTreePanel);

        workspaceSync = new Timeline(
                new KeyFrame(WORKSPACE_SYNC_INTERVAL, e -> syncWorkspaces(false)));
        workspaceSync.setCycleCount(Timeline.INDEFINITE);
        if (autoRefreshMenuItem.isSelected()) {
            workspaceSync.play();
        }

        previewDebounce = new PauseTransition(Duration.millis(200));
        previewDebounce.setOnFinished(e -> updatePreview());

        workspaceTabs.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> onActiveDocumentChanged());
        workspaceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        /* The editor area is mounted here, once, and never moved again.

           It used to be the *content* of whichever document tab was selected, which meant
           every tab switch pulled the WebView out of the scene graph and pushed it back in
           somewhere else. A WebView does not survive that: its native rendering surface is
           torn down and rebuilt, and it comes back painting some layers and not others -
           rules and backgrounds present, no text - until something forces a full repaint.
           That is the blank preview, and it is why dragging the scrollbar "fixed" it while
           the wheel did not.

           So the two tab strips are now headers only (document tabs carry no content at
           all) and the editor area lives permanently below them. Nothing about the two-
           level tab UI changes on screen; the WebView simply stops being moved. */
        /* The tab strips are pinned to their preferred height, and everything else is
           allowed to shrink to nothing.

           A WebView reports a preferred height of 600px, so the VBox's preferred height
           always exceeds what the window can give it, and it shrinks children to fit. A
           TabPane's computed minimum is zero, so without this the two strips are the first
           thing squeezed away - collapsing to the 2px of the selected tab's underline,
           which is precisely what "the tabs are gone" looks like. Pinning min to pref
           takes them out of the negotiation, and the editor area absorbs all of it. */
        workspaceTabs.setMinHeight(Region.USE_PREF_SIZE);
        workspaceTabs.setMaxHeight(Region.USE_PREF_SIZE);
        VBox rightSide = new VBox(workspaceTabs, editorSplit);
        VBox.setVgrow(editorSplit, Priority.ALWAYS);
        rightSide.setMinHeight(0);
        editorSplit.setMinHeight(0);
        editorPane.setMinHeight(0);
        previewPane.setMinHeight(0);
        webView.setMinHeight(0);
        webView.setPrefHeight(0);

        int workspaceSlot = mainSplit.getItems().indexOf(workspaceTabs);
        mainSplit.getItems().set(workspaceSlot, rightSide);

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
    private void onActiveDocumentChanged() {
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

    // ------------------------------------------------------ file operations

    /**
     * File-system actions offered by the explorer's context menu.
     *
     * <p>Every one of these changes the disk, so each confirms or validates first, and
     * deletion goes to the recycle bin rather than being unrecoverable.
     */
    private final class ExplorerFileActions implements FileTreePanel.FileActions {

        @Override
        public void createFile(Path parentDirectory) {
            String name = askForName("New file", "File name", "untitled.md");
            if (name != null) {
                createFileNamed(parentDirectory, name);
            }
        }

        @Override
        public void createFolder(Path parentDirectory) {
            String name = askForName("New folder", "Folder name", "new-folder");
            if (name != null) {
                createFolderNamed(parentDirectory, name);
            }
        }

        @Override
        public void rename(Path target) {
            String current = target.getFileName().toString();
            String name = askForName("Rename", "New name", current);
            if (name != null && !name.equals(current)) {
                renameTo(target, name);
            }
        }

        @Override
        public void delete(Path target) {
            boolean directory = Files.isDirectory(target);
            String destination = Trash.trashSupported() ? "moved to the recycle bin"
                    : "deleted permanently - this system has no recycle bin available";
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(primaryStage);
            confirm.setTitle("Delete");
            confirm.setHeaderText("Delete " + target.getFileName() + "?");
            confirm.setContentText(directory
                    ? "The folder and everything inside it will be " + destination + "."
                    : "It will be " + destination + ".");
            ButtonType deleteButton = new ButtonType("Delete");
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            confirm.getButtonTypes().setAll(deleteButton, cancel);
            if (confirm.showAndWait().orElse(cancel) == deleteButton) {
                deleteTarget(target);
            }
        }
    }

    // The operations themselves, without the prompting, so the file-system behaviour can
    // be exercised directly rather than through modal dialogs.

    private void createFileNamed(Path parentDirectory, String rawName) {
        String name = MarkdownFiles.isMarkdown(Path.of(rawName)) ? rawName : rawName + ".md";
        Path target = parentDirectory.resolve(name);
        if (Files.exists(target)) {
            showAlert("Already exists", name + " is already in that folder.");
            return;
        }
        try {
            Files.createFile(target);
            fileTreePanel.refresh(parentDirectory);
            openFile(target.toFile());
        } catch (IOException e) {
            showAlert("Error", "Could not create the file: " + e.getMessage());
        }
    }

    private void createFolderNamed(Path parentDirectory, String name) {
        Path target = parentDirectory.resolve(name);
        try {
            Files.createDirectories(target);
            fileTreePanel.refresh(parentDirectory);
            // A folder with no markdown in it is filtered out of the tree, so say so
            // rather than leaving the user wondering where it went.
            setTransientStatus(MarkdownFiles.containsMarkdown(target)
                    ? "Created " + name
                    : "Created " + name + " - it appears once it holds a Markdown file.");
        } catch (IOException e) {
            showAlert("Error", "Could not create the folder: " + e.getMessage());
        }
    }

    private void renameTo(Path target, String name) {
        Path destination = target.resolveSibling(name);
        if (Files.exists(destination)) {
            showAlert("Already exists", name + " is already in that folder.");
            return;
        }
        try {
            Files.move(target, destination);
        } catch (IOException e) {
            showAlert("Error", "Could not rename: " + e.getMessage());
            return;
        }
        // A document open from the old path has to follow it, or saving would write the
        // file back under its previous name.
        for (WorkspaceView workspace : workspaces) {
            for (DocumentView document : workspace.getDocuments()) {
                Path path = document.getPath();
                if (path == null) {
                    continue;
                }
                if (path.equals(target)) {
                    document.setPath(destination);
                } else if (path.startsWith(target)) {
                    document.setPath(destination.resolve(target.relativize(path)));
                }
            }
        }
        fileTreePanel.refresh(target.getParent());
        updateStatus();
        updateTitle();
    }

    private void deleteTarget(Path target) {
        closeDocumentsUnder(target);
        if (!Trash.moveToTrash(target)) {
            showAlert("Error", "Could not delete " + target.getFileName()
                    + ". It may be open in another program.");
            return;
        }
        fileTreePanel.refresh(target.getParent());
        setTransientStatus(Trash.trashSupported()
                ? "Moved " + target.getFileName() + " to the recycle bin."
                : "Deleted " + target.getFileName() + ".");
    }

    /** Closes tabs for anything being deleted; their file is about to disappear. */
    private void closeDocumentsUnder(Path target) {
        for (WorkspaceView workspace : List.copyOf(workspaces)) {
            for (DocumentView document : List.copyOf(workspace.getDocuments())) {
                Path path = document.getPath();
                if (path != null && (path.equals(target) || path.startsWith(target))) {
                    document.setModified(false); // Do not prompt to save a deleted file.
                    workspace.getDocuments().remove(document);
                    workspace.getDocumentTabs().getTabs().remove(document.getTab());
                }
            }
        }
        onActiveDocumentChanged();
    }

    private String askForName(String title, String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.initOwner(primaryStage);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(label);
        String name = dialog.showAndWait().map(String::trim).orElse("");
        if (name.isEmpty()) {
            return null;
        }
        // A name with a separator in it would silently write outside the chosen folder.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            showAlert("Invalid name", "Use a plain file name, without path separators.");
            return null;
        }
        return name;
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

    @FXML
    private void handleRefreshWorkspaces() {
        syncWorkspaces(true);
    }

    @FXML
    private void handleToggleAutoRefresh() {
        if (autoRefreshMenuItem.isSelected()) {
            workspaceSync.playFromStart();
            setTransientStatus("Auto-refresh on - workspaces re-read every 5 minutes.");
        } else {
            workspaceSync.stop();
            setTransientStatus("Auto-refresh off - use View > Refresh Workspaces.");
        }
    }

    /**
     * Re-reads every cached directory and merges what changed into the tree.
     *
     * <p>The read happens on a background thread and the merge on the FX thread, because
     * the two have opposite constraints: listing a folder runs the markdown scan over
     * potentially thousands of entries, while the merge touches live scene-graph nodes.
     * Doing the whole thing on the FX thread would stall the UI every five minutes; doing
     * it all off the thread is not allowed.
     *
     * @param announce true for a manual refresh, which reports even when nothing changed -
     *                 silence would leave the user unsure the click did anything. The timer
     *                 stays quiet unless it actually found something.
     */
    private void syncWorkspaces(boolean announce) {
        if (workspaceSyncRunning) {
            if (announce) {
                setTransientStatus("Already refreshing...");
            }
            return;
        }
        List<Path> directories = fileTreePanel.loadedDirectories();
        if (directories.isEmpty()) {
            if (announce) {
                setTransientStatus("No workspace is open.");
            }
            return;
        }

        workspaceSyncRunning = true;
        Thread scan = new Thread(() -> {
            Map<Path, List<Path>> listings = new HashMap<>();
            for (Path directory : directories) {
                listings.put(directory, PathTreeItem.readEntries(directory));
            }
            Platform.runLater(() -> {
                workspaceSyncRunning = false;
                boolean changed = fileTreePanel.applyListings(listings);
                if (changed) {
                    setTransientStatus("Workspace refreshed.");
                } else if (announce) {
                    setTransientStatus("Workspace is up to date.");
                }
            });
        }, "workspace-sync");
        // Daemon: a scan in flight must never hold the JVM open when the window closes.
        scan.setDaemon(true);
        scan.start();
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
        if (document == null) {
            editorSplit.getItems().clear();
            if (currentMode != EditorMode.RAW) {
                previewDebounce.stop();
                updatePreview();
            }
            return;
        }

        mountEditorPane(document);

        /* Only rebuild when the split's contents actually differ. This runs on every tab
           switch, and clearing it unconditionally removed the WebView from the scene graph
           and re-added it even when the mode had not changed - the same teardown that
           blanks the preview, paid on every switch whether or not anything needed to move. */
        List<Node> wanted = switch (currentMode) {
            case RAW -> List.of(editorPane);
            case SPLIT -> List.of(editorPane, previewPane);
            case FULL_PREVIEW -> List.of(previewPane);
        };
        if (!editorSplit.getItems().equals(wanted)) {
            editorSplit.getItems().setAll(wanted);
            if (currentMode == EditorMode.SPLIT) {
                editorSplit.setDividerPositions(0.5);
                Platform.runLater(() -> editorSplit.setDividerPositions(0.5));
            }
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

    /**
     * Undo, routed to the active document's editor whatever currently has focus.
     *
     * <p>The editor already has undo; what it does not have is the keystroke. Every
     * formatting action from the preview goes through {@code editor.replaceText}, so it is
     * on the editor's undo stack - but with the preview focused the key press went to the
     * WebView, which has nothing to undo, and the edit looked permanent.
     */
    @FXML
    private void handleUndo() {
        DocumentView document = activeDocument();
        if (document == null) {
            return;
        }
        TextArea editor = document.getEditor();
        if (!editor.isUndoable()) {
            setTransientStatus("Nothing to undo.");
            return;
        }
        editor.undo();
        // In Full Preview there is no editor on screen to show the caret, so the preview
        // has to catch up immediately rather than on the usual typing debounce.
        previewDebounce.stop();
        updatePreview();
    }

    @FXML
    private void handleRedo() {
        DocumentView document = activeDocument();
        if (document == null) {
            return;
        }
        TextArea editor = document.getEditor();
        if (!editor.isRedoable()) {
            setTransientStatus("Nothing to redo.");
            return;
        }
        editor.redo();
        previewDebounce.stop();
        updatePreview();
    }

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

    // -------------------------------------------------------------- formatting

    /**
     * Replaces WebKit's built-in menu, which offers Copy and nothing else, with one that
     * also carries the formatting actions - the same operations as the toolbar, reachable
     * where the selection already is.
     */
    private void installPreviewContextMenu() {
        webView.setContextMenuEnabled(false);

        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copyPreviewSelection());

        ContextMenu textMenu = new ContextMenu(copy, new SeparatorMenuItem(),
                formatItem("Bold", PreviewToolbar.Action.BOLD),
                formatItem("Italic", PreviewToolbar.Action.ITALIC),
                formatItem("Strikethrough", PreviewToolbar.Action.STRIKETHROUGH),
                formatItem("Inline code", PreviewToolbar.Action.CODE),
                formatItem("Code block", PreviewToolbar.Action.CODE_BLOCK),
                new SeparatorMenuItem(),
                formatItem("Heading 1", PreviewToolbar.Action.HEADING_1),
                formatItem("Heading 2", PreviewToolbar.Action.HEADING_2),
                formatItem("Heading 3", PreviewToolbar.Action.HEADING_3),
                new SeparatorMenuItem(),
                formatItem("Bullet list", PreviewToolbar.Action.BULLET_LIST),
                formatItem("Numbered list", PreviewToolbar.Action.ORDERED_LIST),
                formatItem("Block quote", PreviewToolbar.Action.QUOTE),
                new SeparatorMenuItem(),
                formatItem("Align left", PreviewToolbar.Action.ALIGN_LEFT),
                formatItem("Centre", PreviewToolbar.Action.ALIGN_CENTER),
                formatItem("Align right", PreviewToolbar.Action.ALIGN_RIGHT),
                new SeparatorMenuItem(),
                formatItem("Link...", PreviewToolbar.Action.LINK),
                formatItem("Insert image...", PreviewToolbar.Action.IMAGE_INSERT));

        // Right-clicking an image is asking about that image, not about text.
        Menu size = new Menu("Resize");
        size.getItems().addAll(
                formatItem("75%", PreviewToolbar.Action.IMAGE_WIDTH_75),
                formatItem("100% (natural)", PreviewToolbar.Action.IMAGE_WIDTH_100),
                formatItem("125%", PreviewToolbar.Action.IMAGE_WIDTH_125),
                formatItem("150%", PreviewToolbar.Action.IMAGE_WIDTH_150));
        Menu position = new Menu("Position");
        position.getItems().addAll(
                formatItem("Left", PreviewToolbar.Action.ALIGN_LEFT),
                formatItem("Centre", PreviewToolbar.Action.ALIGN_CENTER),
                formatItem("Right", PreviewToolbar.Action.ALIGN_RIGHT));

        ContextMenu imageMenu = new ContextMenu(
                size, position,
                formatItem("Crop...", PreviewToolbar.Action.IMAGE_CROP),
                new SeparatorMenuItem(),
                formatItem("Caption...", PreviewToolbar.Action.IMAGE_CAPTION),
                formatItem("Replace image...", PreviewToolbar.Action.IMAGE_REPLACE),
                new SeparatorMenuItem(),
                formatItem("Copy image path", PreviewToolbar.Action.IMAGE_COPY_PATH),
                formatItem("Remove image", PreviewToolbar.Action.IMAGE_REMOVE));

        // Right-clicking a code plate is asking about that block's language.
        Menu languages = new Menu("Code language");
        for (String[] entry : CODE_LANGUAGES) {
            MenuItem item = new MenuItem(entry[0]);
            item.setOnAction(e -> setCodeLanguage(entry[1]));
            languages.getItems().add(item);
        }
        MenuItem otherLanguage = new MenuItem("Other...");
        otherLanguage.setOnAction(e -> promptCodeLanguage());
        MenuItem noLanguage = new MenuItem("None (plain text)");
        noLanguage.setOnAction(e -> setCodeLanguage(""));
        languages.getItems().addAll(new SeparatorMenuItem(), otherLanguage, noLanguage);

        ContextMenu codeMenu = new ContextMenu(copyItem("Copy"), new SeparatorMenuItem(),
                languages);

        webView.setOnContextMenuRequested(event -> {
            textMenu.hide();
            imageMenu.hide();
            codeMenu.hide();
            // The page records what was under the pointer on mousedown, which fires
            // before this, so the choice of menu is already known.
            boolean onImage = !previewString("window.__mdImageInfo()").isEmpty();
            boolean onCode = !previewString("window.__mdCodeInfo()").isEmpty();
            ContextMenu menu = onImage ? imageMenu : onCode ? codeMenu : textMenu;
            menu.show(webView, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        webView.setOnMousePressed(event -> {
            textMenu.hide();
            imageMenu.hide();
            codeMenu.hide();
        });
    }

    private MenuItem copyItem(String label) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(e -> copyPreviewSelection());
        return item;
    }

    /**
     * Offered on a code block's menu: display name to highlight.js identifier.
     *
     * <p>A short list of what these documents actually contain rather than everything the
     * highlighter knows - a forty-item menu is a worse way to find "Java" than a ten-item
     * one, and "Other..." covers the rest.
     */
    private static final String[][] CODE_LANGUAGES = {
            {"Bash / Shell", "bash"},
            {"Java", "java"},
            {"JavaScript", "javascript"},
            {"TypeScript", "typescript"},
            {"Python", "python"},
            {"JSON", "json"},
            {"YAML", "yaml"},
            {"XML / HTML", "xml"},
            {"CSS", "css"},
            {"SQL", "sql"},
            {"Groovy", "groovy"},
            {"Dockerfile", "dockerfile"},
    };

    private void promptCodeLanguage() {
        String current = previewString("window.__mdCodeInfo()");
        String[] parts = current.split(",", 3);
        TextInputDialog dialog = new TextInputDialog(parts.length > 2 ? parts[2] : "");
        dialog.initOwner(primaryStage);
        dialog.setTitle("Code language");
        dialog.setHeaderText("Language for this code block");
        dialog.setContentText("highlight.js name:");
        dialog.showAndWait().ifPresent(this::setCodeLanguage);
    }

    /**
     * Rewrites the info string of the code block that was right-clicked.
     *
     * <p>Only the opening fence line is replaced, so the code inside cannot be disturbed
     * by choosing a language for it.
     */
    private void setCodeLanguage(String language) {
        DocumentView document = activeDocument();
        String info = previewString("window.__mdCodeInfo()");
        if (document == null || info.isEmpty()) {
            return;
        }
        String[] parts = info.split(",", 3);
        int start;
        int end;
        try {
            start = Integer.parseInt(parts[0]);
            end = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        SourceEdits.Edit edit = SourceEdits.setFenceLanguage(
                document.getEditor().getText(), start, end, language);
        if (edit == null) {
            setTransientStatus("This block is indented code, which has no language tag.");
            return;
        }
        applyEdit(document, edit);
        setTransientStatus(language.isEmpty()
                ? "Code block set to plain text."
                : "Code block set to " + language + ".");
    }

    private void copyPreviewSelection() {
        String selected = previewString("window.__mdSelectionText()");
        if (!selected.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(selected);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    private MenuItem formatItem(String label, PreviewToolbar.Action action) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(e -> applyFormat(action));
        return item;
    }


    /** A range of the Markdown source that a toolbar action should act on. */
    private record SourceRange(int start, int end) {}

    /**
     * Resolves what the formatting toolbar should act on.
     *
     * <p>A preview selection is mapped back to source by looking inside the enclosing
     * element's own span for the right occurrence of the selected text - occurrence order
     * survives rendering because Markdown only ever adds characters around text. When the
     * preview has no selection the editor's own selection is used, which is what makes the
     * toolbar useful in Split mode too.
     */
    private SourceRange resolveTargetRange() {
        DocumentView document = activeDocument();
        if (document == null) {
            return null;
        }
        String source = document.getEditor().getText();

        String meta = previewString("window.__mdSelectionInfo()");
        String selected = previewString("window.__mdSelectionText()");
        if (!meta.isEmpty() && !selected.isEmpty()) {
            String[] parts = meta.split(",");
            try {
                int blockStart = Math.max(0, Integer.parseInt(parts[0].trim()));
                int blockEnd = Math.min(source.length(), Integer.parseInt(parts[1].trim()));
                int ordinal = Integer.parseInt(parts[2].trim());
                if (blockStart < blockEnd) {
                    String block = source.substring(blockStart, blockEnd);
                    int at = -1;
                    for (int i = 0; i <= ordinal; i++) {
                        at = block.indexOf(selected, at + 1);
                        if (at < 0) {
                            break;
                        }
                    }
                    if (at >= 0) {
                        return new SourceRange(blockStart + at, blockStart + at + selected.length());
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Fall through to the editor selection.
            }
            setTransientStatus("That selection spans formatting, so it could not be matched "
                    + "in the source. Use Code block for a whole paragraph, or select it "
                    + "in the editor.");
            return null;
        }

        var selection = document.getEditor().getSelection();
        return new SourceRange(selection.getStart(), selection.getEnd());
    }

    /**
     * The line range a block operation should cover.
     *
     * <p>Deliberately not text matching: headings, lists and quotes rewrite whole lines,
     * and the enclosing block already knows which lines those are. Falls back to the
     * editor's selection when the preview has none.
     */
    private SourceRange resolveBlockRange() {
        DocumentView document = activeDocument();
        if (document == null) {
            return null;
        }
        String source = document.getEditor().getText();

        String meta = previewString("window.__mdBlockInfo()");
        if (!meta.isEmpty()) {
            String[] parts = meta.split(",");
            try {
                int start = Math.max(0, Integer.parseInt(parts[0].trim()));
                int end = Math.min(source.length(), Integer.parseInt(parts[1].trim()));
                if (start <= end) {
                    return new SourceRange(start, end);
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Fall through to the editor selection.
            }
        }
        var selection = document.getEditor().getSelection();
        return new SourceRange(selection.getStart(), selection.getEnd());
    }

    private String previewString(String js) {
        if (!previewReady) {
            return "";
        }
        try {
            Object value = webView.getEngine().executeScript(js);
            return value == null ? "" : value.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Applies a toolbar action, translating it into one edit of the Markdown source. */
    private void applyFormat(PreviewToolbar.Action action) {
        DocumentView document = activeDocument();
        if (document == null) {
            setTransientStatus("Open a document before formatting.");
            return;
        }
        if (action == PreviewToolbar.Action.IMAGE_INSERT) {
            insertImage(document);
            return;
        }
        if (action == PreviewToolbar.Action.PRINT) {
            printPreview(document);
            return;
        }
        boolean imageSelected = !previewString("window.__mdImageInfo()").isEmpty();
        if (action.name().startsWith("IMAGE_")) {
            applyImageAction(document, action);
            return;
        }
        // Alignment goes to the image when one is selected, and to the text otherwise.
        if (action == PreviewToolbar.Action.ALIGN_LEFT
                || action == PreviewToolbar.Action.ALIGN_CENTER
                || action == PreviewToolbar.Action.ALIGN_RIGHT) {
            String align = switch (action) {
                case ALIGN_CENTER -> "center";
                case ALIGN_RIGHT -> "right";
                default -> "left";
            };
            if (imageSelected) {
                applyImageAction(document, action);
            } else {
                SourceRange target = resolveBlockRange();
                if (target != null) {
                    applyEdit(document, SourceEdits.alignBlock(document.getEditor().getText(),
                            target.start(), target.end(), align));
                }
            }
            return;
        }

        // Whole-line operations resolve differently from inline ones; see resolveBlockRange.
        boolean lineOperation = switch (action) {
            case HEADING_1, HEADING_2, HEADING_3, BULLET_LIST, ORDERED_LIST, QUOTE,
                 CODE_BLOCK -> true;
            default -> false;
        };
        SourceRange range = lineOperation ? resolveBlockRange() : resolveTargetRange();
        if (range == null) {
            return;
        }
        String source = document.getEditor().getText();

        /* Inline code upgrades itself when the selection crosses a line: backticks opened
           mid-paragraph are not a code block, they are backticks, and nothing you would
           want as inline code spans lines.

           That upgrade can only fire for a selection that was matchable back to the
           source. A preview selection covering a paragraph containing **bold** or `code`
           is not - resolveTargetRange gives up long before reaching here, because the
           rendered text no longer contains the markers the source does. Which is exactly
           why Code block is its own button: it resolves the enclosing block instead of
           matching text, so formatting inside the paragraph is irrelevant to it. */
        if (action == PreviewToolbar.Action.CODE
                && source.substring(range.start(), range.end()).contains("\n")) {
            applyEdit(document, SourceEdits.toggleFencedCode(
                    source, range.start(), range.end(), ""));
            return;
        }
        if (action == PreviewToolbar.Action.CODE_BLOCK) {
            applyEdit(document, SourceEdits.toggleFencedCode(
                    source, range.start(), range.end(), ""));
            return;
        }

        SourceEdits.Edit edit = switch (action) {
            case BOLD -> SourceEdits.toggleInline(source, range.start(), range.end(), "**");
            case ITALIC -> SourceEdits.toggleInline(source, range.start(), range.end(), "*");
            case STRIKETHROUGH -> SourceEdits.toggleInline(source, range.start(), range.end(), "~~");
            case CODE -> SourceEdits.toggleInline(source, range.start(), range.end(), "`");
            case HEADING_1 -> SourceEdits.setHeading(source, range.start(), 1);
            case HEADING_2 -> SourceEdits.setHeading(source, range.start(), 2);
            case HEADING_3 -> SourceEdits.setHeading(source, range.start(), 3);
            case BULLET_LIST -> SourceEdits.toggleBullet(source, range.start(), range.end());
            case ORDERED_LIST -> SourceEdits.toggleOrdered(source, range.start(), range.end());
            case QUOTE -> SourceEdits.toggleQuote(source, range.start(), range.end());
            case LINK -> linkEdit(source, range);
            default -> null;
        };
        if (edit != null) {
            applyEdit(document, edit);
        }
    }

    private SourceEdits.Edit linkEdit(String source, SourceRange range) {
        TextInputDialog dialog = new TextInputDialog("https://");
        dialog.initOwner(primaryStage);
        dialog.setTitle("Insert link");
        dialog.setHeaderText("Link target");
        dialog.setContentText("URL");
        var url = dialog.showAndWait();
        return url.map(u -> SourceEdits.link(source, range.start(), range.end(), u)).orElse(null);
    }

    private void applyEdit(DocumentView document, SourceEdits.Edit edit) {
        TextArea editor = document.getEditor();
        editor.replaceText(edit.start(), edit.end(), edit.replacement());
        editor.selectRange(edit.selectionStart(), edit.selectionEnd());
        previewDebounce.stop();
        updatePreview();
    }

    // ------------------------------------------------------------------ print

    /**
     * Page margin, in points, on the left and right of a printed page (72pt = 1 inch).
     */
    private static final double PRINT_SIDE_MARGIN = 54;

    /**
     * Top and bottom margin. Deliberately deeper than the sides: this is the header and
     * footer band, and it is the page margin rather than anything drawn into the document
     * because WebKit 615 does not implement {@code @page} margin boxes. Being a margin is
     * what makes it identical on every page, including the last.
     */
    private static final double PRINT_HEADER_MARGIN = 72;

    /**
     * Prints the rendered preview, or exports it to PDF through whichever virtual printer
     * the user picks in the dialog.
     *
     * <p>Prints the WebView rather than the Markdown: the page already carries the design,
     * and its {@code @media print} block adjusts what does not belong on paper. Printing
     * the source through a second renderer would mean two implementations of the same
     * document drifting apart.
     */
    private void printPreview(DocumentView document) {
        if (currentMode == EditorMode.RAW) {
            // The preview is not mounted in RAW mode, so there is nothing rendered to
            // print. Drop to Split first, the same fallback Find uses.
            handleSplitMode();
        }
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            setTransientStatus("No printer is available. Add one, or install a PDF printer.");
            return;
        }

        Printer printer = job.getPrinter();
        PageLayout layout = printer.createPageLayout(
                Paper.A4, PageOrientation.PORTRAIT,
                PRINT_SIDE_MARGIN, PRINT_SIDE_MARGIN,
                PRINT_HEADER_MARGIN, PRINT_HEADER_MARGIN);
        // Set before the dialog so these are what it opens on; the user can still change
        // paper or orientation there and their choice wins.
        String jobName = printJobName(document);
        job.getJobSettings().setPageLayout(layout);
        job.getJobSettings().setJobName(jobName);

        if (!job.showPrintDialog(primaryStage)) {
            job.endJob();
            setTransientStatus("Printing cancelled.");
            return;
        }

        // Set again after the dialog, which returns a fresh set of job settings for
        // whichever printer was chosen; the name does not always survive that. This names
        // the job in the print queue.
        job.getJobSettings().setJobName(jobName);

        /* Windows' "Print to PDF" opens its own Save dialog with an empty filename box,
           and there is no way to seed it from here. The document name does reach the
           spooler - JavaFX passes JobSettings.jobName through to the AWT job, which was
           verified directly - but the driver does not use it for the filename, and the
           Destination attribute it advertises is ignored through JavaFX's print path.

           So the name is put on the status bar instead, where it stays visible behind the
           dialog and can be read off while typing it in. Not a fix; the best that can be
           done without replacing printing with a PDF writer of our own. */
        statusLabel.setText("Save the PDF as:  " + jobName);
        if (statusRestore != null) {
            statusRestore.stop();
        }

        webView.getEngine().print(job);
        job.endJob();
        setTransientStatus("Sent " + jobName + " to " + job.getPrinter().getName() + ".");
    }

    /**
     * What the print queue calls this job, and what Print to PDF offers as the filename.
     *
     * <p>The document's own first heading rather than its file name: a file called
     * {@code README.md} produces a PDF that ought to be called "Resume Builder", which is
     * what the page actually says. Falls back to the file name when a document has no
     * heading at all.
     */
    private String printJobName(DocumentView document) {
        String title = markdownService.documentTitle(document.getEditor().getText());
        String candidate = safeFileName(title);
        if (!candidate.isEmpty()) {
            return candidate;
        }
        String fallback = safeFileName(stripExtension(document.getDisplayName()));
        return fallback.isEmpty() ? "Document" : fallback;
    }

    /** Windows forbids these outright in a file name. */
    private static final java.util.regex.Pattern ILLEGAL_IN_FILENAME =
            java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");

    /**
     * Makes a heading safe to hand to a save dialog as a filename.
     *
     * <p>A heading is prose and may hold anything. Passing {@code Build & Run: what/why}
     * straight through gives Print to PDF a name the filesystem will reject, and the
     * dialog's recovery from that is to silently substitute its own - which looks exactly
     * like the job name having been ignored.
     */
    private static String safeFileName(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = ILLEGAL_IN_FILENAME.matcher(text).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        // Windows also refuses names ending in a dot or a space.
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120).trim();
        }
        return cleaned;
    }

    // ----------------------------------------------------------------- images

    /**
     * Copies the chosen file next to the document and inserts a relative reference.
     *
     * <p>Copying rather than linking in place is what keeps the document portable: a
     * reference to a file elsewhere on this machine breaks the moment the folder is shared
     * or moved.
     */
    private void insertImage(DocumentView document) {
        Path baseDir = document.getBaseDir();
        if (baseDir == null) {
            setTransientStatus("Save the document first so the image can be stored beside it.");
            return;
        }

        Path target = chooseAndCopyImage(document, "Insert image");
        if (target == null) {
            return;
        }
        String alt = stripExtension(target.getFileName().toString());
        // Built through ImageRef so the destination is escaped in one place: a
        // screenshot filename with spaces is not a valid bare Markdown destination.
        String snippet = new ImageRef(alt, relativeAsset(document, target)).toMarkup();

        TextArea editor = document.getEditor();
        int caret = editor.getCaretPosition();
        editor.insertText(caret, snippet);
        editor.selectRange(caret + 2, caret + 2 + alt.length());
        previewDebounce.stop();
        updatePreview();
        setTransientStatus("Copied to assets/" + target.getFileName());
    }

    /**
     * Inserts an empty table of the chosen size and selects its first header cell.
     *
     * <p>Placed after the caret's line rather than at the caret - see
     * {@link SourceEdits#insertTable} for why - and applied through the same edit path as
     * every other formatting action, so it undoes in one step.
     */
    private void insertTable(int rows, int columns) {
        DocumentView document = activeDocument();
        if (document == null) {
            setTransientStatus("Open a document before inserting a table.");
            return;
        }
        applyEdit(document, SourceEdits.insertTable(
                document.getEditor().getText(),
                document.getEditor().getCaretPosition(), rows, columns));
        document.getEditor().requestFocus();
        setTransientStatus("Inserted a " + rows + " x " + columns + " table.");
    }

    /** Chooser plus copy-into-assets, shared by Insert image and Replace image. */
    private Path chooseAndCopyImage(DocumentView document, String title) {
        Path baseDir = document.getBaseDir();
        if (baseDir == null) {
            setTransientStatus("Save the document first so the image can be stored beside it.");
            return null;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg", "*.webp", "*.bmp"));
        File picked = chooser.showOpenDialog(primaryStage);
        if (picked == null) {
            return null;
        }
        try {
            Path assets = baseDir.resolve("assets");
            Files.createDirectories(assets);
            Path target = uniqueTarget(assets, picked.getName());
            Files.copy(picked.toPath(), target);
            return target;
        } catch (IOException e) {
            showAlert("Error", "Could not copy the image: " + e.getMessage());
            return null;
        }
    }

    /** Never overwrite: an existing name gets a numeric suffix. */
    private static Path uniqueTarget(Path folder, String fileName) {
        Path candidate = folder.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String stem = stripExtension(fileName);
        String extension = fileName.substring(stem.length());
        for (int i = 2; ; i++) {
            candidate = folder.resolve(stem + "-" + i + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * Rewrites the selected image as HTML carrying its alignment and width.
     *
     * <p>Markdown has no syntax for either, so the reference becomes an {@code <img>} in a
     * paragraph with an align attribute - the form that also renders correctly on GitHub,
     * rather than a viewer-specific extension that would only work here.
     */
    private void applyImageAction(DocumentView document, PreviewToolbar.Action action) {
        String meta = previewString("window.__mdImageInfo()");
        if (meta.isEmpty()) {
            setTransientStatus("Click an image in the preview first.");
            return;
        }
        String[] parts = meta.split(",");
        String source = document.getEditor().getText();
        int start;
        int end;
        try {
            start = Math.max(0, Integer.parseInt(parts[0].trim()));
            end = Math.min(source.length(), Integer.parseInt(parts[1].trim()));
        } catch (RuntimeException e) {
            return;
        }
        if (start >= end) {
            return;
        }

        ImageRef current = ImageRef.parse(source.substring(start, end));
        if (current == null) {
            setTransientStatus("Could not read that image reference.");
            return;
        }

        if (action == PreviewToolbar.Action.IMAGE_COPY_PATH) {
            ClipboardContent content = new ClipboardContent();
            content.putString(current.src());
            Clipboard.getSystemClipboard().setContent(content);
            setTransientStatus("Copied " + current.src());
            return;
        }
        if (action == PreviewToolbar.Action.IMAGE_REMOVE) {
            applyEdit(document, new SourceEdits.Edit(start, end, "", start, start));
            setTransientStatus("Image reference removed. The file itself is still in assets/.");
            return;
        }

        ImageRef updated = switch (action) {
            case ALIGN_LEFT -> current.withAlign("left");
            case ALIGN_CENTER -> current.withAlign("center");
            case ALIGN_RIGHT -> current.withAlign("right");
            case IMAGE_WIDTH_75 -> current.withWidth("75%");
            case IMAGE_WIDTH_100 -> current.withWidth("100%");
            case IMAGE_WIDTH_125 -> current.withWidth("125%");
            case IMAGE_WIDTH_150 -> current.withWidth("150%");
            case IMAGE_CAPTION -> captionOf(current);
            case IMAGE_REPLACE -> replacementFor(document, current);
            case IMAGE_CROP -> croppedFrom(document, current);
            default -> current;
        };
        if (updated == null || updated.equals(current)) {
            return;
        }
        String replacement = updated.toMarkup();
        applyEdit(document, new SourceEdits.Edit(start, end, replacement, start,
                start + replacement.length()));
    }

    /** @return the reference with an edited caption, or null if the dialog was cancelled */
    private ImageRef captionOf(ImageRef current) {
        TextInputDialog dialog = new TextInputDialog(
                current.caption() == null ? "" : current.caption());
        dialog.initOwner(primaryStage);
        dialog.setTitle("Image caption");
        dialog.setHeaderText("Caption shown beneath the image");
        dialog.setContentText("Caption");
        // An empty caption removes it, which is the only way back to a plain image.
        return dialog.showAndWait().map(current::withCaption).orElse(null);
    }

    private ImageRef replacementFor(DocumentView document, ImageRef current) {
        Path copied = chooseAndCopyImage(document, "Replace image");
        return copied == null ? null : current.withSrc(relativeAsset(document, copied));
    }

    private ImageRef croppedFrom(DocumentView document, ImageRef current) {
        Path baseDir = document.getBaseDir();
        if (baseDir == null) {
            setTransientStatus("Save the document before cropping.");
            return null;
        }
        Path file = baseDir.resolve(current.src().replace('/', File.separatorChar)).normalize();
        if (!Files.isRegularFile(file)) {
            setTransientStatus("Cannot find the image file to crop: " + current.src());
            return null;
        }
        Path cropped = CropDialog.cropInPlaceCopy(primaryStage, file);
        return cropped == null ? null : current.withSrc(relativeAsset(document, cropped));
    }

    private String relativeAsset(DocumentView document, Path file) {
        return document.getBaseDir().relativize(file).toString().replace('\\', '/');
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
                injectHighlighter();
                installBridge();
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
    /**
     * Path to the bundled highlight.js inside the webjar. The version is part of the path,
     * so it has to be kept in step with the dependency in pom.xml - a mismatch is a silent
     * miss, since a missing highlighter is a supported state rather than an error.
     */
    private static final String HLJS_RESOURCE =
            "/META-INF/resources/webjars/highlightjs/11.11.1/highlight.min.js";

    /**
     * Loads the bundled highlight.js into the preview page.
     *
     * <p>Injected the same way as mermaid, and for the same reason: this is an offline app,
     * so a highlighter loaded from a CDN would simply never arrive. Failure is not fatal -
     * code blocks keep the unhighlighted look they have always had.
     */
    private void injectHighlighter() {
        try (InputStream in = getClass().getResourceAsStream(HLJS_RESOURCE)) {
            if (in == null) {
                System.err.println("MDViewer: highlight.js not on the classpath at "
                        + HLJS_RESOURCE + " - code blocks will not be highlighted.");
                return;
            }
            webView.getEngine().executeScript(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            // Nothing is auto-detected: a block with no language keeps the plain look
            // rather than being guessed at, which is what the fence not saying so means.
            webView.getEngine().executeScript(
                    "hljs.configure({ignoreUnescapedHTML:true, languages:[]});");
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: highlight.js unavailable - " + e);
        }
    }

    /**
     * The object the preview page calls back into, held in a field on purpose.
     *
     * <p>A {@code JSObject.setMember} target is only weakly reachable from the page, so a
     * bridge that exists just as an argument is collected at the next GC and every call
     * from the page silently stops working - typically minutes later, which makes it look
     * like an intermittent bug rather than a lifetime one.
     */
    private final PreviewBridge previewBridge = new PreviewBridge();

    private void installBridge() {
        try {
            netscape.javascript.JSObject window =
                    (netscape.javascript.JSObject) webView.getEngine().executeScript("window");
            window.setMember("mdvBridge", previewBridge);
        } catch (RuntimeException e) {
            System.err.println("MDViewer: preview bridge unavailable - " + e);
        }
    }

    /**
     * Called from the preview page's JavaScript. Public because the WebView needs to
     * reflect on it.
     *
     * <p>Every method here runs on the FX thread - JavaFX evaluates page script there -
     * so the controller can be touched directly. Re-rendering, though, is deferred: these
     * calls arrive from inside a DOM event handler on an element that a re-render would
     * destroy underneath it.
     */
    public final class PreviewBridge {

        /** The Markdown of one table cell, so the reader edits what is really there. */
        public String cellSource(int tableStart, int row, int column) {
            DocumentView document = activeDocument();
            if (document == null) {
                return null;
            }
            String tableText = tableTextAt(document.getEditor().getText(), tableStart);
            return tableText == null ? null : TableSource.cell(tableText, row, column);
        }

        public void commitCell(int tableStart, int row, int column, String value) {
            DocumentView document = activeDocument();
            if (document == null) {
                return;
            }
            String source = document.getEditor().getText();
            String tableText = tableTextAt(source, tableStart);
            if (tableText == null) {
                Platform.runLater(() -> updatePreview());
                return;
            }
            String rewritten = TableSource.withCell(tableText, row, column, value);
            if (rewritten == null) {
                Platform.runLater(() -> updatePreview());
                return;
            }
            int end = tableStart + tableText.length();
            Platform.runLater(() -> {
                applyEdit(document, new SourceEdits.Edit(
                        tableStart, end, rewritten, tableStart, tableStart));
                setTransientStatus("Table cell updated.");
            });
        }

        /** Puts a cell back to its rendered form after an edit that changed nothing. */
        public void cancelCell() {
            Platform.runLater(() -> updatePreview());
        }

        /** The Markdown a rendered block came from, for editing it in place. */
        public String blockSource(int start, int end) {
            DocumentView document = activeDocument();
            if (document == null) {
                return null;
            }
            String source = document.getEditor().getText();
            if (start < 0 || end > source.length() || start >= end) {
                return null;
            }
            return source.substring(start, end);
        }

        /**
         * Replaces a block's Markdown with what was edited in the preview.
         *
         * <p>{@code original} is what the page was handed at the start of the edit, and
         * the range is only replaced if the document still says exactly that. The offsets
         * come from a render, and a render can be a moment behind the editor - after a
         * keystroke, before the preview debounce catches up. Writing to a stale range
         * would overwrite whatever had moved into it, which on a document is not a bug
         * you notice until much later.
         */
        public void commitBlock(int start, int end, String original, String value) {
            DocumentView document = activeDocument();
            if (document == null) {
                return;
            }
            String source = document.getEditor().getText();
            if (start < 0 || end > source.length() || start >= end
                    || !source.substring(start, end).equals(original)) {
                Platform.runLater(() -> {
                    updatePreview();
                    setTransientStatus("The document changed while that block was open; "
                            + "the edit was not applied.");
                });
                return;
            }
            // contenteditable can leave a trailing newline behind that was never typed,
            // and it would show up as a spurious blank line in the source.
            String cleaned = value.replace("\r\n", "\n").replace("\r", "\n");
            while (cleaned.endsWith("\n")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            String replacement = cleaned;
            Platform.runLater(() -> {
                applyEdit(document, new SourceEdits.Edit(
                        start, end, replacement, start, start + replacement.length()));
                setTransientStatus("Block updated.");
            });
        }
    }

    /**
     * The table's text starting at {@code start}, up to the first blank line.
     *
     * <p>Recovered from the source rather than trusted from the page: the offset comes
     * from the rendered document, which may be a render behind the editor if something
     * else changed the text in between.
     */
    private static String tableTextAt(String source, int start) {
        if (start < 0 || start >= source.length()) {
            return null;
        }
        int end = start;
        while (end < source.length()) {
            int lineEnd = SourceEdits.lineEnd(source, end);
            if (source.substring(end, lineEnd).isBlank()) {
                break;
            }
            end = lineEnd;
            if (end >= source.length()) {
                break;
            }
            end++; // step over the newline
        }
        String text = source.substring(start, Math.min(end, source.length()));
        while (text.endsWith("\n") || text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return TableSource.parse(text) == null ? null : text;
    }

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

            /* The reading column, and how far plates may break out of it.
               --measure is in rem, not ch: ch is relative to each element's own font, so
               headings would silently get a far wider column than the paragraphs under
               them and the two would stop sharing a left edge.

               --plate is what a code block, table or diagram spans: the measure plus a
               fixed breakout to the right. Fixed rather than proportional, because a
               breakout that grows with the window turns a code block on a 4K monitor
               into a line too long to read back.

               --gutter centres the two of them as one block. The widest thing on the
               page is a plate, so centring the plate centres the page; the prose then
               starts at that same left edge and simply stops short of the right. max()
               clamps the gutter to zero once the window is too narrow to have any
               breakout to give. */
            :root {
              --measure:40rem; --breakout:12rem;
              --plate:min(100%, calc(var(--measure) + var(--breakout)));
              --gutter:max(0px, calc((100% - var(--measure) - var(--breakout)) / 2));
            }
            /* Horizontal placement lives here and nowhere else - the element rules below
               set vertical margins only. Two rules agreeing on a left edge is one rule
               too many. */
            body > * {
              width:min(100%, var(--measure));
              margin-left:var(--gutter); margin-right:auto;
            }
            body > .mdv-code, body > table, body > .mdv-diagram,
            body > pre.mermaid, body > .mdv-diagram-error, body > hr {
              width:var(--plate); max-width:none;
              margin-left:var(--gutter); margin-right:auto;
              /* border-box, or the plates' own padding and accent border would be
                 added outside the computed width and drag the left edge back off
                 the prose column by exactly as much. */
              box-sizing:border-box;
            }

            h1,h2,h3,h4,h5,h6 {
              font-family:var(--display); font-weight:600; line-height:1.22;
              letter-spacing:-.01em; margin-top:2.1em; margin-bottom:.65em; color:var(--ink);
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

            p { margin-top:0; margin-bottom:1.15em; }
            ul,ol { padding-left:1.5em; margin-top:0; margin-bottom:1.15em; }
            li { margin:.3em 0; }
            li::marker { color:var(--accent); }

            strong { font-weight:650; }
            hr { height:1px; border:0; background:var(--rule); margin-top:2.6em; margin-bottom:2.6em; }

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
              margin-top:1.7em; margin-bottom:1.7em; background:var(--code-bg);
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

            /* highlight.js token colours, written here rather than taking one of its
               themes: every hljs theme brings its own background and its own idea of a
               code block, and would fight the plate this sits in. Hues are the preview's
               own - the accent for keywords, the mark red for literals - so a highlighted
               block still reads as part of this document. */
            .hljs-keyword, .hljs-selector-tag, .hljs-literal, .hljs-section, .hljs-doctag {
              color:var(--accent-ink); font-weight:600;
            }
            .hljs-string, .hljs-regexp, .hljs-addition, .hljs-attribute, .hljs-meta .hljs-string {
              color:var(--mark);
            }
            .hljs-number, .hljs-symbol, .hljs-bullet, .hljs-link, .hljs-selector-attr {
              color:#7A4FB5;
            }
            .hljs-comment, .hljs-quote, .hljs-deletion {
              color:var(--ink-soft); font-style:italic;
            }
            .hljs-title, .hljs-name, .hljs-title.function_, .hljs-title.class_ {
              color:#1F6FB2; font-weight:600;
            }
            .hljs-type, .hljs-built_in, .hljs-class .hljs-title, .hljs-params {
              color:#0F7A6B;
            }
            .hljs-attr, .hljs-variable, .hljs-template-variable, .hljs-selector-id,
            .hljs-selector-class {
              color:#8A5A1E;
            }
            .hljs-meta, .hljs-tag { color:var(--ink-soft); }
            .hljs-emphasis { font-style:italic; }
            .hljs-strong { font-weight:700; }

            /* The light hues above are chosen against a near-white plate and go muddy on
               a dark one, so the dark theme gets its own set rather than a filter. */
            html[data-theme="dark"] .hljs-number,
            html[data-theme="dark"] .hljs-symbol,
            html[data-theme="dark"] .hljs-bullet,
            html[data-theme="dark"] .hljs-link,
            html[data-theme="dark"] .hljs-selector-attr { color:#C39BF0; }
            html[data-theme="dark"] .hljs-title,
            html[data-theme="dark"] .hljs-name,
            html[data-theme="dark"] .hljs-title.function_,
            html[data-theme="dark"] .hljs-title.class_ { color:#6FB6ED; }
            html[data-theme="dark"] .hljs-type,
            html[data-theme="dark"] .hljs-built_in,
            html[data-theme="dark"] .hljs-class .hljs-title,
            html[data-theme="dark"] .hljs-params { color:#5FC9B6; }
            html[data-theme="dark"] .hljs-attr,
            html[data-theme="dark"] .hljs-variable,
            html[data-theme="dark"] .hljs-template-variable,
            html[data-theme="dark"] .hljs-selector-id,
            html[data-theme="dark"] .hljs-selector-class { color:#D8A85C; }

            blockquote {
              margin-top:1.6em; margin-bottom:1.6em; padding:.1em 0 .1em 1.15em;
              border-left:3px solid var(--mark); color:var(--ink-soft);
            }
            blockquote p:last-child { margin-bottom:0; }

            /* display:table, not block. As a block the <table> box filled its plate but
               the rows inside it did not: the cells generate an anonymous table box that
               shrink-wraps its content, so the grid stopped wherever the text happened to
               end and left the rest of the bordered plate empty on the right. A real
               table box stretches its columns to the width it is given. */
            table {
              display:table; table-layout:auto; width:100%; border-collapse:collapse;
              margin-top:1.8em; margin-bottom:1.8em; font-size:.94em;
              border:1px solid var(--rule); border-radius:7px;
            }
            /* Sideways scrolling went with display:block. Wrapping is the better trade
               for a document anyway - a column you have to scroll to read is a column you
               will not read - but an unbroken URL still has to be allowed to break. */
            th, td { overflow-wrap:break-word; word-break:break-word; }
            /* A cell under edit shows raw Markdown, so it gets the mono face - the
               change of typeface is itself the signal that this is the source now and
               not the rendered form. */
            /* A block under edit shows its raw Markdown, same signal as a cell: the
               mono face and the accent frame say "this is the source now". */
            .mdv-block-editing {
              background:var(--accent-soft);
              outline:2px solid var(--accent); outline-offset:2px;
              border-radius:4px;
              padding:0;
            }
            /* A heading's ::after draws an accent bar; under an open editor it reads as
               part of the text being edited. */
            h1.mdv-block-editing::after { display:none; }
            /* The editor inherits nothing from the block it replaces - a heading's
               display face at 2.3rem is not what raw Markdown should be typed in. */
            textarea.mdv-block-editor {
              display:block; width:100%; box-sizing:border-box;
              margin:0; padding:6px 8px;
              border:0; outline:none; resize:none; overflow:hidden;
              background:transparent; color:var(--ink);
              font-family:var(--mono); font-size:13.5px; line-height:1.6;
              font-weight:400; font-style:normal; letter-spacing:0;
              text-align:left;
            }

            td.mdv-cell-editing, th.mdv-cell-editing {
              font-family:var(--mono); font-size:.92em;
              background:var(--accent-soft);
              outline:2px solid var(--accent); outline-offset:-2px;
              white-space:pre-wrap;
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
            /* An explicitly sized image is allowed past the prose column - that is the
               point of choosing 125% or 150%, which the default clamp would swallow. */
            img[width] { max-width:none; }
            /* An image clicked in the preview is the target of the position and size
               controls, so it has to be visibly the chosen one. */
            img.mdv-img-selected { outline:2px solid var(--accent); outline-offset:2px; }
            figure { margin-top:1.8em; margin-bottom:1.8em; }
            figure img { display:inline-block; }
            figcaption {
              margin-top:.6em; color:var(--ink-soft);
              font-size:.88em; font-style:italic;
            }
            figure[align="center"], div[align="center"] { text-align:center; }
            figure[align="right"], div[align="right"] { text-align:right; }
            p[align="center"] { text-align:center; }
            p[align="right"] { text-align:right; }
            p[align="left"] { text-align:left; }

            /* Diagrams share the plate family. The card keeps a light ground in both
               themes: PlantUML and mermaid bake dark strokes into the SVG, which would
               disappear on a dark panel. */
            .mdv-diagram, pre.mermaid {
              position:relative; margin-top:1.8em; margin-bottom:1.8em; padding:34px 16px 16px;
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
              margin-top:1.7em; margin-bottom:1.7em; padding:14px 16px;
              color:var(--err-fg); background:var(--err-bg);
              border:1px solid var(--err-line); border-left:3px solid var(--err-fg);
              border-radius:7px; text-align:left;
              font-family:var(--mono); font-size:12.5px;
            }

            /* ------------------------------------------------------------ print

               Paper is a different medium and several screen decisions are actively
               wrong on it. Each rule below undoes one of them.

               The header and footer bands are the printer job's page margins rather
               than anything drawn here: WebKit 615 does not implement @page margin
               boxes, so a running header cannot carry content, but the *space* is
               consistent on every page because the margin is. */
            @media print {
              /* Always the light palette. Printing the dark theme empties a cartridge
                 to make text that was designed to glow, and the diagram cards are
                 light regardless - dark mode would print white-on-white text next to
                 them. Listed with the dark selector so it wins on specificity rather
                 than by !important. */
              html, html[data-theme="dark"] {
                --paper:#FFFFFF; --ink:#16202B; --ink-soft:#4A5763;
                --rule:#C9D2DC; --line:#B4C0CD;
                --accent:#0B6E7F; --accent-ink:#0A5A68; --accent-soft:#0B6E7F14;
                --mark:#A23B2E;
                --code-bg:#F4F7FA; --code-ink:#16202B; --stripe:#F2F5F8;
                --plate-shadow:none;
              }

              /* The reading measure and its breakout gutter are screen furniture. On
                 paper the page margin is the measure, so everything runs full width. */
              :root { --measure:100%; --breakout:0rem; --gutter:0px; }
              body { padding:0; font-size:10.5pt; background:#FFFFFF; }
              body > * { width:100%; margin-left:0; }
              body > .mdv-code, body > table, body > .mdv-diagram,
              body > pre.mermaid, body > .mdv-diagram-error, body > hr {
                width:100%; margin-left:0;
              }

              /* Anything that is one visual object moves to the next page whole
                 rather than being cut in half by a page break. */
              img, figure, .mdv-diagram, pre.mermaid, .mdv-diagram-error, .mdv-code {
                page-break-inside:avoid; break-inside:avoid;
              }

              /* Tables are the deliberate exception: a long table SHOULD run across
                 pages, and its header has to follow. display:table is restated rather
                 than inherited so this keeps working whatever the screen rule is - an
                 earlier version made tables display:block for sideways scrolling, and a
                 block table has no header group to repeat and no rows to break between,
                 so it was cut mid-row with the column names left on page one. */
              table {
                display:table; width:100%; overflow:visible;
                page-break-inside:auto; break-inside:auto;
              }
              thead { display:table-header-group; }
              tfoot { display:table-footer-group; }
              tr, img { page-break-inside:avoid; break-inside:avoid; }

              /* A heading alone at the foot of a page, and single lines stranded
                 either side of a break. */
              h1, h2, h3, h4, h5, h6 { page-break-after:avoid; break-after:avoid; }
              p, li { orphans:3; widows:3; }

              /* There is no scrolling on paper: a long code line must wrap or it is
                 simply lost off the right edge. */
              .mdv-code { overflow:visible; }
              .mdv-code pre { overflow:visible; }
              pre code { white-space:pre-wrap; word-wrap:break-word; }

              /* Soft shadows print as grey smudges. */
              .mdv-code, .mdv-diagram, pre.mermaid, table { box-shadow:none; }

              /* A link's destination is invisible on paper, so keep it legible as
                 text rather than as a coloured affordance that does nothing. */
              a { color:var(--ink); text-decoration:underline; border-bottom:none; }

              /* The selection outline is an editing artefact. */
              img.mdv-img-selected { outline:none; }
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
            /* Syntax highlighting. hljs is injected separately, like mermaid, and may not
               be there at all if the resource is missing - in which case code blocks keep
               the plain look they had before and nothing else changes. */
            window.__mdHighlight = function () {
              if (!window.hljs) { return; }
              var blocks = document.querySelectorAll('.mdv-code pre code[class*="language-"]');
              for (var i = 0; i < blocks.length; i++) {
                var block = blocks[i];
                if (block.getAttribute('data-highlighted') === 'yes') { continue; }
                try {
                  hljs.highlightElement(block);
                } catch (e) {
                  /* An unknown language is not worth losing the block over. */
                  block.setAttribute('data-highlighted', 'yes');
                }
              }
            };
            window.__mdSetBody = function (html) {
              document.body.innerHTML = html;
              /* The previous DOM is gone, so any image selected in it is too. Leaving the
                 old offsets behind would send the next size or alignment action at an
                 element that no longer exists. */
              window.__mdSelectedImage = '';
              window.__mdCodeBlock = '';
              /* The elements these pointed at have just been destroyed. Leaving them set
                 leaves the "already editing" guard permanently true, and every later
                 double-click is silently refused - which looks exactly like the feature
                 not working at all. */
              window.__mdEditingCell = null;
              window.__mdEditingBlock = null;
              window.__mdRunMermaid();
              window.__mdHighlight();
            };
            window.__mdSetDiagram = function (id, svg) {
              var el = document.getElementById(id);
              if (!el) { return; }
              el.className = 'mdv-diagram';
              el.innerHTML = svg;
            };

            /* --- editing from the preview -----------------------------------
               Every element carries the Markdown offsets it was rendered from.
               A selection reports the enclosing element's range plus which
               occurrence of the selected text it is, which is enough for the
               controller to find the same text in the source: Markdown only
               adds characters around text, so occurrence order is preserved. */
            function mdAnchor(node) {
              var el = node && node.nodeType === 1 ? node : (node ? node.parentNode : null);
              while (el && !(el.getAttribute && el.getAttribute('data-md-start'))) {
                el = el.parentElement;
              }
              return el;
            }
            window.__mdSelectionInfo = function () {
              var sel = window.getSelection();
              if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { return ''; }
              var range = sel.getRangeAt(0);
              var el = mdAnchor(range.startContainer);
              if (!el) { return ''; }
              var text = sel.toString();
              if (!text) { return ''; }
              var pre = range.cloneRange();
              pre.selectNodeContents(el);
              pre.setEnd(range.startContainer, range.startOffset);
              var prefix = pre.toString();
              var ordinal = 0;
              var at = prefix.indexOf(text);
              while (at >= 0) { ordinal++; at = prefix.indexOf(text, at + text.length); }
              return el.getAttribute('data-md-start') + ',' +
                     el.getAttribute('data-md-end') + ',' + ordinal;
            };
            window.__mdSelectionText = function () {
              var sel = window.getSelection();
              return sel ? sel.toString() : '';
            };

            /* Block operations - headings, lists, quotes - work on whole lines, so they
               anchor to the enclosing block rather than the innermost inline. Anchoring
               them to an inline would scope a multi-line selection to, say, the <strong>
               it happens to start in, and the selected text would not be found there. */
            var MD_BLOCK_TAGS = {P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,
                                 LI:1,BLOCKQUOTE:1,TD:1,TH:1,PRE:1};
            function mdBlockAnchor(node) {
              var el = node && node.nodeType === 1 ? node : (node ? node.parentNode : null);
              while (el && !(MD_BLOCK_TAGS[el.tagName] && el.getAttribute
                             && el.getAttribute('data-md-start'))) {
                el = el.parentElement;
              }
              return el;
            }
            window.__mdBlockInfo = function () {
              var sel = window.getSelection();
              if (!sel || sel.rangeCount === 0) { return ''; }
              var range = sel.getRangeAt(0);
              var first = mdBlockAnchor(range.startContainer);
              var last = mdBlockAnchor(range.endContainer) || first;
              if (!first) { first = last; }
              if (!first || !last) { return ''; }
              var s = parseInt(first.getAttribute('data-md-start'), 10);
              var e = parseInt(last.getAttribute('data-md-end'), 10);
              if (isNaN(s) || isNaN(e)) { return ''; }
              return Math.min(s, e) + ',' + Math.max(s, e);
            };

            /* Pressing on an image selects it for the image controls. This listens for
               mousedown rather than click for two reasons: a right-click never produces a
               click event, and mousedown lands before the context menu is built, so by the
               time the menu opens it already knows whether an image is under the pointer. */
            window.__mdSelectedImage = '';
            document.addEventListener('mousedown', function (event) {
              var previous = document.querySelector('img.mdv-img-selected');
              if (previous) { previous.classList.remove('mdv-img-selected'); }
              if (event.target && event.target.tagName === 'IMG') {
                event.target.classList.add('mdv-img-selected');
                var el = mdAnchor(event.target);
                window.__mdSelectedImage = el
                    ? el.getAttribute('data-md-start') + ',' + el.getAttribute('data-md-end')
                    : '';
              } else {
                window.__mdSelectedImage = '';
              }
            });
            window.__mdImageInfo = function () { return window.__mdSelectedImage; };

            /* The code plate under the pointer, recorded the same way and for the same
               reason as the image: a right-click produces no click event, so the target
               has to be captured on mousedown, before the context menu is asked for. */
            window.__mdCodeBlock = '';
            document.addEventListener('mousedown', function (event) {
              var el = event.target;
              while (el && el !== document.body
                     && !(el.classList && el.classList.contains('mdv-code'))) {
                el = el.parentElement;
              }
              if (el && el.classList && el.classList.contains('mdv-code')
                  && el.getAttribute('data-md-start')) {
                window.__mdCodeBlock = el.getAttribute('data-md-start') + ','
                    + el.getAttribute('data-md-end') + ','
                    + (el.getAttribute('data-mdv-lang') || '');
              } else {
                window.__mdCodeBlock = '';
              }
            });
            window.__mdCodeInfo = function () { return window.__mdCodeBlock; };

            /* ---- editing a table cell in place ----------------------------------
               Double-click, not single: a table is read far more often than it is
               edited, and a single click would put a caret in a cell every time
               someone went to select a value out of one.

               What appears for editing is the cell's Markdown, fetched from the
               document rather than taken from the rendered cell. A cell showing
               styled code is `like this` in the source, and handing back what the
               screen shows would drop the backticks the moment it was saved. */
            window.__mdEditingCell = null;

            function mdCellCoords(cell) {
              var table = cell;
              while (table && table.tagName !== 'TABLE') { table = table.parentElement; }
              if (!table || !table.getAttribute('data-md-start')) { return null; }
              var row = cell.getAttribute('data-mdv-row');
              var col = cell.getAttribute('data-mdv-col');
              if (row === null || col === null) { return null; }
              return {
                table: parseInt(table.getAttribute('data-md-start'), 10),
                row: parseInt(row, 10),
                col: parseInt(col, 10)
              };
            }

            function mdBeginCellEdit(cell) {
              if (!window.mdvBridge || window.__mdEditingCell) { return; }
              var at = mdCellCoords(cell);
              if (!at) { return; }
              var markdown = window.mdvBridge.cellSource(at.table, at.row, at.col);
              if (markdown === null || markdown === undefined) { return; }
              cell.setAttribute('data-mdv-original', markdown);
              cell.textContent = markdown;
              cell.setAttribute('contenteditable', 'true');
              cell.classList.add('mdv-cell-editing');
              window.__mdEditingCell = cell;
              cell.focus();
              var range = document.createRange();
              range.selectNodeContents(cell);
              var sel = window.getSelection();
              sel.removeAllRanges();
              sel.addRange(range);
            }

            function mdEndCellEdit(cell, commit) {
              if (window.__mdEditingCell !== cell) { return; }
              window.__mdEditingCell = null;
              cell.removeAttribute('contenteditable');
              cell.classList.remove('mdv-cell-editing');
              var value = cell.textContent;
              var original = cell.getAttribute('data-mdv-original');
              cell.removeAttribute('data-mdv-original');
              var at = mdCellCoords(cell);
              if (!at || !window.mdvBridge) { return; }
              if (!commit || value === original) {
                /* Nothing changed, so nothing is written - but the cell is showing raw
                   Markdown right now and has to be put back to its rendered self. */
                window.mdvBridge.cancelCell();
                return;
              }
              window.mdvBridge.commitCell(at.table, at.row, at.col, value);
            }

            document.addEventListener('dblclick', function (event) {
              var cell = event.target;
              while (cell && cell !== document.body
                     && cell.tagName !== 'TD' && cell.tagName !== 'TH') {
                cell = cell.parentElement;
              }
              if (cell && (cell.tagName === 'TD' || cell.tagName === 'TH')) {
                mdBeginCellEdit(cell);
                return;
              }
              mdBeginBlockEdit(event.target);
            });

            /* ---- editing any other block in place -------------------------------
               The same idea as a table cell, and simpler: every rendered block already
               carries the offsets of the Markdown it came from, so the edit is a
               straight replacement of that range. No re-serialising, because unlike a
               table there is no structure to rebuild.

               What is offered is again the source. A paragraph containing **bold**
               shows its asterisks while being edited - which is the point, since it is
               the only way to change them from here. */
            var MD_EDITABLE_TAGS = {
              P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, LI: 1, BLOCKQUOTE: 1
            };
            window.__mdEditingBlock = null;

            function mdEditableBlock(node) {
              var el = node && node.nodeType === 1 ? node : (node ? node.parentElement : null);
              while (el && el !== document.body) {
                /* A table is edited cell by cell and a diagram is not text at all;
                   either would be destroyed by a whole-block replacement. */
                if (el.tagName === 'TABLE' || el.tagName === 'TD' || el.tagName === 'TH'
                    || (el.classList && (el.classList.contains('mdv-diagram')
                        || el.classList.contains('mermaid')))) {
                  return null;
                }
                if (el.getAttribute && el.getAttribute('data-md-start')
                    && (MD_EDITABLE_TAGS[el.tagName]
                        || (el.classList && el.classList.contains('mdv-code')))) {
                  return el;
                }
                el = el.parentElement;
              }
              return null;
            }

            /* A real textarea, not contenteditable.

               contenteditable stores line breaks as elements, so reading the value back
               means asking innerText what the layout looked like - and for a block whose
               CSS does not preserve whitespace that answer has the newlines replaced by
               spaces. A fenced code block round-tripped through that comes back as one
               line, which Markdown then reads as a paragraph with inline code in it: the
               block silently stops being a block. A textarea has a value, and the value
               is exactly what was typed. */
            function mdAutosize(area) {
              area.style.height = 'auto';
              area.style.height = (area.scrollHeight + 2) + 'px';
            }

            function mdBeginBlockEdit(target) {
              if (!window.mdvBridge || window.__mdEditingCell || window.__mdEditingBlock) {
                return;
              }
              var block = mdEditableBlock(target);
              if (!block) { return; }
              var start = parseInt(block.getAttribute('data-md-start'), 10);
              var end = parseInt(block.getAttribute('data-md-end'), 10);
              var markdown = window.mdvBridge.blockSource(start, end);
              if (markdown === null || markdown === undefined) { return; }

              var area = document.createElement('textarea');
              area.className = 'mdv-block-editor';
              area.value = markdown;
              area.setAttribute('spellcheck', 'false');
              block.setAttribute('data-mdv-original', markdown);
              block.innerHTML = '';
              block.appendChild(area);
              block.classList.add('mdv-block-editing');
              window.__mdEditingBlock = block;

              area.addEventListener('input', function () { mdAutosize(area); });
              area.addEventListener('blur', function () { mdEndBlockEdit(block, true); });
              area.addEventListener('keydown', function (event) {
                if (event.key === 'Escape') {
                  event.preventDefault();
                  mdEndBlockEdit(block, false);
                } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                  /* Enter alone inserts a line break, because a Markdown block is allowed
                     to span lines and breaking one is an ordinary edit. Ctrl+Enter
                     commits, as does clicking away. */
                  event.preventDefault();
                  mdEndBlockEdit(block, true);
                }
              });

              mdAutosize(area);
              area.focus();
              area.select();
            }

            function mdEndBlockEdit(block, commit) {
              if (window.__mdEditingBlock !== block) { return; }
              window.__mdEditingBlock = null;
              block.classList.remove('mdv-block-editing');
              var area = block.querySelector('textarea.mdv-block-editor');
              var value = area ? area.value : null;
              var original = block.getAttribute('data-mdv-original');
              block.removeAttribute('data-mdv-original');
              var start = parseInt(block.getAttribute('data-md-start'), 10);
              var end = parseInt(block.getAttribute('data-md-end'), 10);
              if (!window.mdvBridge) { return; }
              if (!commit || value === null || value === original) {
                window.mdvBridge.cancelCell();
                return;
              }
              window.mdvBridge.commitBlock(start, end, original, value);
            }

            document.addEventListener('keydown', function (event) {
              var cell = window.__mdEditingCell;
              if (!cell) { return; }
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                mdEndCellEdit(cell, true);
              } else if (event.key === 'Escape') {
                event.preventDefault();
                mdEndCellEdit(cell, false);
              } else if (event.key === 'Tab') {
                /* Tab would move focus out of the page entirely; committing first is
                   what makes filling a row in feel like a table rather than a form. */
                event.preventDefault();
                mdEndCellEdit(cell, true);
              }
            }, true);

            document.addEventListener('focusout', function (event) {
              if (window.__mdEditingCell && event.target === window.__mdEditingCell) {
                mdEndCellEdit(event.target, true);
              }
            }, true);
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
