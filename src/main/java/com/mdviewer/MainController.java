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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.Group;
import javafx.scene.control.ToggleButton;
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
import com.mdviewer.service.ChartData;
import com.mdviewer.service.MarkdownService;
import com.mdviewer.service.SourceEdits;
import com.mdviewer.service.TableSource;
import com.mdviewer.service.WorkspaceHistory;
import com.mdviewer.ai.AiConfig;
import com.mdviewer.ai.AiPanel;
import com.mdviewer.service.Trash;
import com.mdviewer.ui.DocumentView;
import com.mdviewer.ui.FileTreePanel;
import com.mdviewer.ui.ChartDialog;
import com.mdviewer.ui.CropDialog;
import com.mdviewer.ui.FindBar;
import com.mdviewer.ui.MarkdownFiles;
import com.mdviewer.ui.PathTreeItem;
import com.mdviewer.ui.DisplaySize;
import com.mdviewer.ui.PreviewToolbar;
import com.mdviewer.ui.LongPress;
import com.mdviewer.ui.VirtualKeyboard;
import com.mdviewer.ui.TouchScroll;
import com.mdviewer.ui.WelcomeView;
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
import java.util.LinkedHashMap;
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
    /** Who is signed in, along the bottom of the explorer. */
    private com.mdviewer.ui.AccountBar accountBar;

    @FXML
    private TabPane workspaceTabs;

    @FXML
    private Label statusLabel;

    @FXML
    private Label wordCountLabel;

    @FXML
    private Label modeLabel;

    @FXML
    private javafx.scene.layout.HBox statusBar;

    /** On while something is talking to the cloud. Added to the status bar at startup. */
    private final com.mdviewer.ui.SyncIndicator syncIndicator = new com.mdviewer.ui.SyncIndicator();

    /** The last background failure shown, so the same one is not shown every five minutes. */
    private String lastAutoSyncTrouble;

    @FXML
    private Button themeButton;

    @FXML
    private MenuItem themeMenuItem;

    @FXML
    private MenuItem explorerMenuItem;

    @FXML
    private javafx.scene.control.CheckMenuItem touchScrollMenuItem;

    @FXML
    private javafx.scene.control.MenuBar menuBar;

    @FXML
    private javafx.scene.control.RadioMenuItem displayTabletItem;

    @FXML
    private javafx.scene.control.RadioMenuItem displayRegularItem;

    @FXML
    private javafx.scene.control.RadioMenuItem displayLargeItem;

    @FXML
    private MenuItem assistantMenuItem;

    private AiPanel aiPanel;
    /** Remembered so hiding and re-showing the assistant keeps its width. */
    private double assistantDivider = 0.72;

    /** The mode to go back to when the assistant closes, or null when it is not open. */
    private EditorMode modeBeforeAssistant;

    /** The edge tab that opens and closes the assistant. */
    private final ToggleButton assistantTab = new ToggleButton("Assistant");

    @FXML
    private CheckMenuItem autoRefreshMenuItem;

    @FXML
    private CheckMenuItem cloudAutoSyncMenuItem;

    private com.mdviewer.sync.AutoSyncService cloudAutoSync;

    /**
     * Carries the document being written to the cloud about once a minute.
     *
     * <p>Made when it is first needed rather than at startup: most sessions never touch the
     * cloud, and an unused connection should cost nothing.
     */
    private com.mdviewer.sync.DraftLink draftLink;

    @FXML
    private Menu recentWorkspacesMenu;

    private final WorkspaceHistory workspaceHistory = new WorkspaceHistory();

    /** Shown while nothing is open; the editor column sits behind it in a StackPane. */
    private WelcomeView welcomeView;

    /** The tabs, editor and preview as one column - what the welcome screen stands in for. */
    private Node editorSide;

    /** So the explorer is restored only if the welcome screen was what hid it. */
    private boolean explorerHiddenForWelcome;

    private static final String APP_VERSION = "1.0.0";

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

    /** Sends what PlantUML drew to the cloud, so the browser can show the same picture. */
    private final com.mdviewer.sync.DiagramUpload diagramUpload = new com.mdviewer.sync.DiagramUpload();

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

    /** Past this the file tree is taking space the document needs more. */
    private static final double MAX_EXPLORER_WIDTH = 240;

    /**
     * Tab ceilings. Past these the strips stop being scannable, which is the whole point
     * of grouping files by workspace; opening more is refused with a status-bar message
     * rather than silently evicting something the user still has open.
     */
    private static final int MAX_WORKSPACES = 10;
    private static final int MAX_DOCUMENTS_PER_WORKSPACE = 20;

    private boolean darkMode = false;

    /** Drag to scroll instead of drag to select, for touchscreens. */
    private final TouchScroll touchScroll = new TouchScroll();

    /** Settings file shared with {@link TouchScroll}. */
    private final com.mdviewer.ui.UiSettings uiSettings = new com.mdviewer.ui.UiSettings();

    /** Asks the desktop's on-screen keyboard to appear; see {@link VirtualKeyboard}. */
    private final VirtualKeyboard virtualKeyboard = new VirtualKeyboard(uiSettings);

    /**
     * Delays hiding the keyboard, so moving between two text fields does not flash it.
     *
     * <p>Focus leaves the editor before it arrives anywhere else, so a hide on focus lost
     * and a show on focus gained would fire in that order every time - the keyboard would
     * drop and come back for a click from the editor into the find bar.
     */
    private final javafx.animation.PauseTransition keyboardHide =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));

    /** How much room the interface gives itself. Chosen in Settings, not measured. */
    private DisplaySize displaySize = DisplaySize.REGULAR;

    public enum EditorMode {
        RAW, SPLIT, FULL_PREVIEW
    }

    @FXML
    public void initialize() {
        webView = new WebView();
        webView.setMinWidth(0);
        touchScroll.install(webView);
        LongPress.install(webView, touchScroll::isEnabled);
        if (touchScrollMenuItem != null) {
            touchScrollMenuItem.setSelected(touchScroll.isEnabled());
        }
        displaySize = DisplaySize.load(uiSettings);
        applyDisplaySize();
        installResponsiveLayout();

        previewToolbar = new PreviewToolbar();
        previewToolbar.setOnAction(this::applyFormat);
        previewToolbar.setOnInsertTable(this::insertTable);
        previewToolbar.setOnInsertChart(this::insertChart);

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

        accountBar = new com.mdviewer.ui.AccountBar();
        accountBar.setActions(new AccountActions());

        fileTreePanel = new FileTreePanel();
        fileTreePanel.setOpenOnSingleClick(touchScroll.isEnabled());
        LongPress.install(fileTreePanel.getTreeView(), touchScroll::isEnabled);
        fileTreePanel.setOnFileActivated(path -> openFile(path.toFile()));
        fileTreePanel.setOnReveal(this::handleRevealInTree);
        fileTreePanel.setFileActions(new ExplorerFileActions());
        fileTreePanel.setOnRefreshRequested(this::handleRefreshWorkspaces);
        fileTreePanel.setOnSyncRequested(this::handleSyncToCloud);
        fileTreePanel.setFooter(accountBar);
        explorerHost.getChildren().add(fileTreePanel);
        refreshAccountBar();

        /*
         * The spinner goes last, after the label that grows, so it sits against the right
         * edge and away from the three facts beside it - those are always true, and this one
         * is only sometimes.
         */
        syncIndicator.watch(com.mdviewer.sync.SyncActivity.shared());
        statusBar.getChildren().add(syncIndicator);

        /*
         * Cloud auto-sync watches whichever workspace is open. It reports through the status
         * line rather than a dialog: this runs while somebody is writing, and a modal that
         * appears over their document every few minutes would be worse than no sync at all.
         */
        // The tick has to match the file, or the menu is a second opinion about the setting.
        cloudAutoSyncMenuItem.setSelected(new com.mdviewer.sync.CloudConfig().autoSync());

        cloudAutoSync = new com.mdviewer.sync.AutoSyncService(
                message -> javafx.application.Platform.runLater(() -> setTransientStatus(message)));
        cloudAutoSync.setOnTrouble(failure ->
                javafx.application.Platform.runLater(() -> reportAutoSyncTrouble(failure)));

        workspaceSync = new Timeline(
                new KeyFrame(WORKSPACE_SYNC_INTERVAL, e -> syncWorkspaces(false)));
        workspaceSync.setCycleCount(Timeline.INDEFINITE);
        if (autoRefreshMenuItem.isSelected()) {
            workspaceSync.play();
        }

        installReplaceShortcut();

        /* Built here but not mounted. The assistant is the one part of this app that
           sends anything anywhere, so it appears when it is asked for and not before. */
        aiPanel = new AiPanel(new AiConfig());
        aiPanel.setDocumentSupplier(() -> {
            DocumentView document = activeDocument();
            return document == null ? "" : document.getEditor().getText();
        });
        aiPanel.setDocumentNameSupplier(() -> {
            DocumentView document = activeDocument();
            return document == null ? "an unsaved document" : document.getDisplayName();
        });
        aiPanel.setWorkspaceRootSupplier(() -> {
            WorkspaceView workspace = activeWorkspace();
            return workspace == null ? null : workspace.getRoot();
        });
        installToolStripe();

        /* Built when the menu opens rather than kept in step with every open and close.
           A menu nobody is looking at does not need to be correct, and rebuilding on
           demand means there is one place that can be wrong instead of several. */
        recentWorkspacesMenu.setOnShowing(e -> rebuildRecentWorkspaces());
        // After, not before: a label's real width is only known once the popup has been
        // laid out with the fonts the stylesheet actually gave it.
        recentWorkspacesMenu.setOnShown(e -> alignRecentRows());
        rebuildRecentWorkspaces();

        previewDebounce = new PauseTransition(Duration.millis(200));
        previewDebounce.setOnFinished(e -> updatePreview());

        workspaceTabs.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> {
                    onActiveDocumentChanged();
                    // The workspace in front of the reader is the one worth keeping in step.
                    WorkspaceView workspace = activeWorkspace();
                    cloudAutoSync.watch(workspace == null ? null : workspace.getRoot());
                });
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

        // After the right-hand side exists, because it is that whole column - tabs, editor
        // and preview together - the welcome screen stands in front of.
        installWelcome(rightSide);

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
        updateWelcome();
        // Only a real folder is worth remembering; the scratch workspace holding unsaved
        // documents has no root to return to.
        if (normalized != null) {
            workspaceHistory.record(normalized);
        }
        return workspace;
    }

    /**
     * Moves the shared editor/preview area into the selected file tab. Only one tab can
     * hold it at a time - a JavaFX node has a single parent - so this is what makes the
     * tabs act as selectors over one editor rather than N editors and N WebViews.
     */
    /**
     * Puts the welcome screen behind the tabs, to be shown while nothing is open.
     *
     * <p>The tabs stay in the scene rather than being swapped out. Removing and re-adding
     * the pane that holds the editor and the preview is the teardown that blanks the
     * WebView, and the empty state is exactly when that would look like a broken window.
     * Both live in a StackPane and take turns being visible.
     */
    private void installWelcome(Node editorSide) {
        welcomeView = new WelcomeView(APP_VERSION,
                this::handleNewFile, this::handleOpenFile, this::handleOpenFolder,
                this::openRecentWorkspace);
        this.editorSide = editorSide;

        /* The host goes into the split first and the column into the host after. Built the
           other way round - new StackPane(editorSide, welcomeView) - the constructor
           re-parents the column, which takes it out of the split, and the index read a
           line earlier no longer points at anything. */
        int index = mainSplit.getItems().indexOf(editorSide);
        StackPane host = new StackPane();
        if (index >= 0) {
            mainSplit.getItems().set(index, host);
        }
        host.getChildren().addAll(editorSide, welcomeView);
        updateWelcome();
    }

    /**
     * Shows the welcome screen when there is nothing open, and hides it otherwise.
     *
     * <p>The explorer goes with it: a file tree with no roots is an empty grey column, and
     * two empty panels say less than one screen that offers somewhere to start.
     */
    private void updateWelcome() {
        if (welcomeView == null) {
            return;
        }
        boolean empty = workspaces.isEmpty();
        welcomeView.setVisible(empty);
        welcomeView.setManaged(empty);
        if (editorSide != null) {
            editorSide.setVisible(!empty);
        }
        if (empty) {
            welcomeView.setRecent(workspaceHistory.list());
            if (isExplorerVisible()) {
                explorerHiddenForWelcome = true;
                showExplorer(false);
            }
        } else if (explorerHiddenForWelcome) {
            explorerHiddenForWelcome = false;
            showExplorer(true);
        }
    }

    private void onActiveDocumentChanged() {
        updateLayout();
        updateWordCount();
        updateStatus();
        updateTitle();
        protectActiveDocument();
        /* The assistant follows the document. One panel, but a conversation each: asking
           about a design note and then opening a specification used to carry the first
           document's questions into the second. Keyed by path so a file keeps its thread
           across tab closes and reopens; unsaved documents fall back to their tab name,
           which is the only identity they have. */
        if (aiPanel != null) {
            DocumentView document = activeDocument();
            String key = document == null ? ""
                    : (document.getPath() != null ? document.getPath().toString()
                            : "untitled:" + document.getDisplayName());
            aiPanel.setActiveDocument(key,
                    document == null ? null : document.getDisplayName());
        }
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
        touchScroll.install(document.getEditor());
        installImagePaste(document);
        installKeyboardSummon(document.getEditor());
        LongPress.install(document.getEditor(), touchScroll::isEnabled);

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
        // Closing the last one puts the welcome screen back, so the window is never an
        // empty grey rectangle with no indication of what to do next.
        updateWelcome();
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
    private void handleProviderSettings() {
        if (com.mdviewer.ai.ProviderSettings.show(
                rootPane.getScene() == null ? null : rootPane.getScene().getWindow(),
                aiPanel.getConfig())) {
            // The picker is built from the config, so it has to be rebuilt when the config
            // changes underneath it.
            aiPanel.refreshProviders();
            setTransientStatus("AI providers updated.");
        }
    }

    /**
     * Syncs the active workspace folder, after showing what that would do.
     *
     * <p>Per workspace, never per document and never for everything open at once: a
     * workspace is the unit someone decided to put in the cloud, and the folder currently
     * in front of them is the only one they can be said to have asked about.
     */
    /**
     * Signs in to the cloud, in the reader's own browser.
     *
     * <p>The browser rather than a window inside MDViewer, and that is the point: the
     * password goes to the authorization server the reader can see the address of, and this
     * application never has the chance to read it. A sign-in page drawn inside an
     * application asks to be trusted; one in the browser can be checked.
     */
    /**
     * Turns automatic cloud sync on or off, and remembers it.
     *
     * <p>Remembered in the same file as everything else about the cloud, so the answer
     * survives a restart - a setting that quietly reverts is one nobody trusts twice.
     */
    /**
     * Points the draft link at whatever is being written now.
     *
     * <p>Only the document in front of the reader is worth protecting: it is the only one
     * whose unsaved state is not already on disk. Moving to another document sends the last
     * of the first one on the way past, because release does that.
     */
    private void protectActiveDocument() {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        DocumentView document = activeDocument();
        WorkspaceView workspace = activeWorkspace();

        if (!config.isEnabled() || document == null || workspace == null
                || document.getPath() == null) {
            if (draftLink != null) {
                draftLink.release();
            }
            return;
        }

        try {
            com.mdviewer.sync.SyncState state =
                    com.mdviewer.sync.SyncState.forWorkspace(workspace.getRoot());
            if (!state.isEnrolled()) {
                // Not linked to a cloud workspace, so there is nowhere to put a draft.
                if (draftLink != null) {
                    draftLink.release();
                }
                return;
            }

            if (draftLink == null) {
                draftLink = new com.mdviewer.sync.DraftLink(config.endpoint(), config.session(),
                        state1 -> javafx.application.Platform.runLater(() -> showDraftState(state1)));
            }

            String path = com.mdviewer.sync.WorkspaceScanner.relative(
                    workspace.getRoot().toRealPath(), document.getPath());

            /* The text is read on the draft link's own thread, so reading it has to be safe
               from one - hence the hop back onto the UI thread for the value. */
            draftLink.protect(state.workspaceId(), path, () -> {
                final String[] held = new String[1];
                final java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
                javafx.application.Platform.runLater(() -> {
                    try {
                        DocumentView now = activeDocument();
                        held[0] = now == null ? null : now.getEditor().getText();
                    } finally {
                        read.countDown();
                    }
                });
                try {
                    return read.await(5, java.util.concurrent.TimeUnit.SECONDS) ? held[0] : null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
        } catch (Exception e) {
            // Cloud drafts are a convenience. Nothing about editing depends on them, and a
            // reader who cannot reach the cloud should not hear about it while typing.
        }
    }

    /**
     * Says whether what is being written is protected, quietly.
     *
     * <p>In the status line and nowhere else. This changes when a laptop leaves a network,
     * which is often and is not news - a dialog for it would be an interruption every time
     * somebody walked out of the office.
     */
    private void showDraftState(com.mdviewer.sync.DraftLink.State state) {
        switch (state) {
            case PROTECTED -> setTransientStatus("Unsaved changes are being kept in the cloud.");
            case UNPROTECTED -> setTransientStatus("The cloud cannot be reached, so unsaved "
                    + "changes are only on this machine.");
            default -> { }
        }
    }

    @FXML
    private void handleToggleCloudAutoSync() {
        try {
            new com.mdviewer.sync.CloudConfig().setAutoSync(cloudAutoSyncMenuItem.isSelected());
            setTransientStatus(cloudAutoSyncMenuItem.isSelected()
                    ? "Workspaces will keep themselves in step with the cloud."
                    : "Automatic sync is off. Use Settings > Cloud Sync when you want to sync.");
        } catch (Exception e) {
            setTransientStatus("Could not save that setting - "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @FXML
    private void handleCloudSignIn() {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        com.mdviewer.sync.CloudSession session = config.session();

        setTransientStatus("Opening " + config.issuer() + " in your browser...");

        Thread worker = new Thread(() -> {
            String message;
            try {
                String missing = session.signIn(uri ->
                        javafx.application.Platform.runLater(() ->
                                hostServices.showDocument(uri.toString())));

                String account = session.account();
                message = "Signed in" + (account.isBlank() ? "" : " as " + account) + ".";
                com.mdviewer.ui.AccountBar.onUi(this::refreshAccountBar);

                /*
                 * Tell the cloud this machine is here. Nothing else would until the first
                 * sync, so the machine list would not show the machine the reader just
                 * signed in on - which looks like the sign-in failed.
                 */
                try {
                    config.client().announce();
                } catch (Exception e) {
                    // Not worth failing a successful sign-in over. The machine registers on
                    // the first sync instead, which is where it used to happen anyway.
                    message += System.lineSeparator() + System.lineSeparator()
                            + "This machine will appear in your machine list after the first sync.";
                }
                if (!missing.isBlank()) {
                    /*
                     * Authenticated but not entitled. Worth saying here rather than letting
                     * it surface as a 403 during a sync, where it looks like the sync is
                     * broken rather than the grant being absent.
                     */
                    message += System.lineSeparator() + System.lineSeparator()
                            + "This account did not receive: " + missing
                            + System.lineSeparator()
                            + "Sync will be refused until those are granted to the MDViewer "
                            + "application in the authorization server.";
                }
            } catch (Exception e) {
                message = "Not signed in."
                        + System.lineSeparator() + System.lineSeparator()
                        + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String said = message;
            javafx.application.Platform.runLater(() -> {
                Alert done = new Alert(Alert.AlertType.INFORMATION);
                done.initOwner(primaryStage);
                done.setTitle("Cloud sign-in");
                done.setHeaderText(null);
                done.getDialogPane().setMinWidth(460);
                done.setContentText(said);
                done.showAndWait();
            });
        }, "cloud-sign-in");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Forgets the sign-in on this machine.
     *
     * <p>Local only, and said so in the dialog. Other machines keep theirs, and anything
     * already in the cloud stays there - signing out of a reader is not a way to withdraw
     * documents, and it would be a poor thing to let someone believe it was.
     */
    @FXML
    private void handleCloudSignOut() {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        try {
            config.session().signOut();
            refreshAccountBar();
            setTransientStatus("Signed out on this machine. Documents already in the cloud "
                    + "are untouched.");
        } catch (Exception e) {
            setTransientStatus("Could not sign out - "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Puts the account bar in step with what is actually signed in.
     *
     * <p>Called after signing in or out, and once when the window is built. The bar is not
     * asked to work it out for itself: whether there is a session is a question about
     * configuration and stored tokens, and a control that answered it would be a second
     * opinion about the thing the rest of this class already owns.
     */
    private void refreshAccountBar() {
        if (accountBar == null) {
            return;
        }
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        if (!config.isEnabled() || !config.session().hasStoredSignIn()) {
            accountBar.setSignedOut();
            return;
        }

        /*
         * Shown before the plan is known. The account name is in the stored token and the
         * quota is a request to a server that may be slow or unreachable, so waiting for the
         * second would leave the bar blank on a window that has just opened - and blank is
         * what "signed out" looks like.
         */
        String account = config.session().account();
        accountBar.setSignedIn(account, "", 0, 0);

        Thread worker = new Thread(() -> {
            try {
                com.mdviewer.sync.CloudClient.Quota quota = config.client().quota();
                /* The name too: reading the quota is what loads the tokens, so on a window
                   that has just opened this is the first moment the account is known. */
                String named = config.session().account();
                com.mdviewer.ui.AccountBar.onUi(() -> {
                    if (!named.isBlank()) {
                        accountBar.setSignedIn(named, quota.tier(), quota.usedBytes(),
                                quota.limitBytes());
                    } else {
                        accountBar.setPlan(quota.tier(), quota.usedBytes(), quota.limitBytes());
                    }
                });
            } catch (Exception e) {
                /*
                 * Silent. Being unable to reach the server says nothing about whether
                 * somebody is signed in, and a bar that reported every failed request would
                 * be an error message on a laptop that is merely offline.
                 */
            }
        }, "cloud-account");
        worker.setDaemon(true);
        worker.start();
    }

    /** What the account bar's menu does, which is what the Settings menu has always done. */
    private final class AccountActions implements com.mdviewer.ui.AccountBar.Actions {

        @Override
        public void signIn() {
            handleCloudSignIn();
        }

        @Override
        public void signOut() {
            handleCloudSignOut();
        }

        /**
         * Opens the plan page in the browser.
         *
         * <p>Not a dialog here. What a plan costs and what it includes changes without this
         * application being rebuilt, and a desktop app carrying its own copy of a price list
         * is a desktop app that is eventually wrong about money.
         */
        @Override
        public void upgrade() {
            com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
            String endpoint = config.endpoint();
            String site = endpoint.replaceFirst("/+$", "");
            hostServices.showDocument(site + "/account/plan");
            setTransientStatus("Opened your plan in the browser.");
        }
    }

    /**
     * Sends one file, or one folder, to the cloud - and nothing else.
     *
     * <p>Reached by right-clicking in the tree, which is where the decision belongs. Syncing
     * a whole workspace is a commitment to everything in it, and somebody with one folder of
     * notes worth keeping and a scratch directory beside it should be able to say so by
     * pointing at the folder they mean.
     *
     * <p>A file keeps its path: {@code notes/2026/plan.md} arrives under that name, so the
     * folders around it exist in the cloud too. A folder means everything the scanner finds
     * beneath it, which is the same set a full sync of that folder would have carried.
     *
     * <p>Nothing is downloaded and nothing is deleted. This adds or updates what was named
     * and leaves the rest of the workspace as it was, which is what makes it safe to reach
     * for on a folder without first working out what a full sync would do.
     */
    private void handleSyncToCloud(Path target) {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        if (!config.isEnabled()) {
            setTransientStatus("Sign in first - Settings > Sign In to Cloud.");
            return;
        }

        Path root = workspaceRootFor(target);
        if (root == null) {
            setTransientStatus("That is not inside an open workspace.");
            return;
        }

        final com.mdviewer.sync.SyncState state;
        try {
            state = com.mdviewer.sync.SyncState.forWorkspace(root);
        } catch (IOException e) {
            setTransientStatus("Could not read this workspace's sync state: " + e.getMessage());
            return;
        }

        /*
         * Linking, if it has not been done. This is the one question the sync code refuses to
         * answer on somebody's behalf - a folder called "docs" might be the same one from
         * another machine or an unrelated project that shares a name, and joining the wrong
         * one merges two sets of documents that were never meant to meet. Asked here, once,
         * with the name it would create.
         */
        if (!state.isEnrolled()) {
            String name = root.getFileName() == null ? "workspace" : root.getFileName().toString();
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
            ask.initOwner(primaryStage);
            ask.setTitle("Sync to cloud");
            ask.setHeaderText("This folder is not in the cloud yet.");
            ask.getDialogPane().setMinWidth(460);
            ask.setContentText("Create a cloud workspace called \"" + name + "\" and send it there?"
                    + System.lineSeparator() + System.lineSeparator()
                    + "To join one that already exists instead, use Settings > Cloud Sync.");
            if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        String name = target.getFileName() == null ? "This" : target.getFileName().toString();
        String doing = "Sending " + name;
        setTransientStatus(doing + " to " + config.client().host() + "...");

        Thread worker = new Thread(() -> {
            String said;
            Throwable failed = null;

            /* try-with-resources, so the spinner stops however this ends. A spinner left
               running by an early return makes the reader distrust everything else on the
               bar. */
            try (AutoCloseable ignored = com.mdviewer.sync.SyncActivity.shared().begin(doing)) {
                com.mdviewer.sync.CloudClient cloud = config.client();
                com.mdviewer.sync.SyncRunner runner =
                        new com.mdviewer.sync.SyncRunner(root, cloud, state, message -> { });

                if (!state.isEnrolled()) {
                    String workspaceName = root.getFileName() == null
                            ? "workspace" : root.getFileName().toString();
                    runner.createAndLink(workspaceName);
                }

                com.mdviewer.sync.WorkspaceScanner.Scan scan =
                        com.mdviewer.sync.WorkspaceScanner.scan(root);
                java.util.Set<String> wanted =
                        com.mdviewer.sync.PartialSync.pathsUnder(root, target, scan);

                if (wanted.isEmpty()) {
                    said = "There is nothing here that syncs. Markdown documents and the "
                            + "images they use are what a workspace carries.";
                } else {
                    com.mdviewer.sync.SyncRunner.Push push = runner.push(wanted);
                    said = push.sent() + (push.sent() == 1 ? " document sent" : " documents sent")
                            + (push.alreadyThere() > 0
                                    ? ", " + push.alreadyThere() + " already there" : "")
                            + ". The workspace is now at revision " + push.revision() + ".";
                }
            } catch (Exception e) {
                failed = e;
                said = "Nothing was sent - " + com.mdviewer.ui.SyncErrorDialog.codeOf(e);
            }

            final String message = said;
            final Throwable failure = failed;
            javafx.application.Platform.runLater(() -> {
                setTransientStatus(message);
                if (failure != null) {
                    /* This one was asked for, so its failure is answered in front of the
                       reader rather than in a line they may never look at. */
                    com.mdviewer.ui.SyncErrorDialog.show(primaryStage, doing, failure);
                }
            });
        }, "mdviewer-partial-sync");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Shows what went wrong in the background - once per fault, not once per attempt.
     *
     * <p>Automatic sync runs every five minutes, so a laptop off the network fails it twelve
     * times an hour. A dialog each time is a dialog people dismiss without reading, and then
     * the one that matters is dismissed the same way. So the same failure is shown once, the
     * status line carries it after that, and a fault that clears and returns is shown again.
     */
    private void reportAutoSyncTrouble(Throwable failure) {
        if (failure == null) {
            lastAutoSyncTrouble = null;   // Working again; the next fault is news.
            return;
        }

        String signature = com.mdviewer.ui.SyncErrorDialog.codeOf(failure) + " / "
                + (failure.getMessage() == null ? failure.toString() : failure.getMessage());
        if (signature.equals(lastAutoSyncTrouble)) {
            setTransientStatus("Automatic sync is still failing - "
                    + com.mdviewer.ui.SyncErrorDialog.codeOf(failure));
            return;
        }
        lastAutoSyncTrouble = signature;
        com.mdviewer.ui.SyncErrorDialog.show(primaryStage, "Automatic sync", failure);
    }

    /** The open workspace this path belongs to, or null if it is outside all of them. */
    private Path workspaceRootFor(Path path) {
        for (WorkspaceView workspace : workspaces) {
            try {
                if (com.mdviewer.sync.WorkspaceScanner.isInside(workspace.getRoot(), path)) {
                    return workspace.getRoot();
                }
            } catch (IOException e) {
                // A workspace whose root has gone is not the answer; keep looking.
            }
        }
        return null;
    }

    @FXML
    private void handleCloudSync() {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        if (!config.isEnabled()) {
            setTransientStatus("Cloud sync is off. Turn it on in "
                    + System.getProperty("user.home") + "\\.mdviewer\\cloud.properties");
            return;
        }
        WorkspaceView workspace = activeWorkspace();
        Path root = workspace == null ? null : workspace.getRoot();
        if (root == null) {
            setTransientStatus("Open a folder before syncing - a workspace is what syncs, "
                    + "not a single document.");
            return;
        }
        com.mdviewer.sync.CloudSyncDialog.open(primaryStage, root, config);
        // A sync can add, change or remove files under the workspace, so the tree on screen
        // is out of date by definition once it finishes.
        handleRefreshWorkspaces();
    }

    /**
     * Sends this machine's AI settings to the account, and takes back what is there.
     *
     * <p>Its own action rather than part of a workspace sync, because settings belong to
     * the account and workspaces do not - folding them together would mean the reader's
     * provider configuration changed as a side effect of syncing some documents.
     *
     * <p>Credentials are removed before anything is sent, and what was held back is named
     * in the result: a decision the reader is told about rather than a surprise waiting on
     * the other machine.
     */
    @FXML
    private void handleCloudSettingsSync() {
        com.mdviewer.sync.CloudConfig config = new com.mdviewer.sync.CloudConfig();
        if (!config.isEnabled()) {
            setTransientStatus("Cloud sync is off. Turn it on in "
                    + System.getProperty("user.home") + "\\.mdviewer\\cloud.properties");
            return;
        }

        Thread worker = new Thread(() -> {
            String summary;
            Throwable failed = null;
            try (AutoCloseable ignored =
                         com.mdviewer.sync.SyncActivity.shared().begin("Syncing settings")) {
                com.mdviewer.sync.CloudClient cloud = config.client();
                com.mdviewer.sync.SettingsSync settings = new com.mdviewer.sync.SettingsSync();

                /* Down first, then up. Taking what is there before sending means another
                   machine's additions survive; sending first would overwrite them with
                   whatever this machine happened to hold. */
                com.mdviewer.sync.SettingsSync.Incoming incoming =
                        settings.apply(cloud.getSettings());
                com.mdviewer.sync.SettingsSync.Outgoing outgoing = settings.outgoing();
                cloud.putSettings(outgoing.json());

                StringBuilder said = new StringBuilder();
                said.append("Settings synced with ").append(cloud.host()).append(".")
                        .append(System.lineSeparator()).append(System.lineSeparator());
                said.append(incoming.added()).append(" added, ")
                        .append(incoming.changed()).append(" changed on this machine.")
                        .append(System.lineSeparator());
                if (!outgoing.withheld().isEmpty()) {
                    said.append(System.lineSeparator())
                            .append("Kept on this machine, as always:")
                            .append(System.lineSeparator());
                    for (String name : outgoing.withheld()) {
                        said.append("    ").append(name).append(System.lineSeparator());
                    }
                    said.append(System.lineSeparator())
                            .append("Another machine will ask for these once.");
                }
                summary = said.toString();
            } catch (Exception e) {
                failed = e;
                summary = "Settings were not synced.";
            }
            final String message = summary;
            final Throwable failure = failed;
            javafx.application.Platform.runLater(() -> {
                if (failure != null) {
                    // A failure gets the error dialog, which names the code and can be
                    // copied - an INFORMATION box saying something went wrong is the
                    // wrong shape for something that did.
                    com.mdviewer.ui.SyncErrorDialog.show(primaryStage, "Syncing settings", failure);
                    return;
                }
                Alert done = new Alert(Alert.AlertType.INFORMATION);
                done.initOwner(primaryStage);
                done.setTitle("Cloud settings");
                done.setHeaderText(null);
                done.getDialogPane().setMinWidth(460);
                done.setContentText(message);
                done.showAndWait();
                // The assistant's picker is built from the config, so it has to be rebuilt
                // when the config has changed underneath it.
                if (aiPanel != null) {
                    aiPanel.refreshProviders();
                }
            });
        }, "cloud-settings-sync");
        worker.setDaemon(true);
        worker.start();
        setTransientStatus("Syncing settings...");
    }

    @FXML
    private void handleToggleAssistant() {
        showAssistant(!isAssistantVisible());
    }

    /**
     * The tool stripe down the right edge, the way an IDE does it.
     *
     * <p>The assistant was reachable only from the View menu, which is two clicks and a
     * memory test for something opened and closed all day. A labelled tab on the edge is
     * always visible, says whether the panel is open, and is the same click either way.
     *
     * <p>Rotated inside a Group on purpose: a rotated node keeps its unrotated layout
     * bounds, so a bare rotated button would reserve a wide, short box and overlap what is
     * beside it. A Group takes its size from what it actually draws.
     */
    private void installToolStripe() {
        assistantTab.getStyleClass().add("tool-stripe-button");
        assistantTab.setRotate(90);
        assistantTab.setFocusTraversable(false);
        assistantTab.setTooltip(new Tooltip("Show or hide the assistant"));
        assistantTab.setOnAction(e -> showAssistant(assistantTab.isSelected()));

        VBox stripe = new VBox(new Group(assistantTab));
        stripe.getStyleClass().add("tool-stripe");
        stripe.setAlignment(Pos.TOP_CENTER);
        rootPane.setRight(stripe);
    }

    private boolean isAssistantVisible() {
        return mainSplit.getItems().contains(aiPanel);
    }

    private void showAssistant(boolean visible) {
        if (visible == isAssistantVisible()) {
            return;
        }
        if (visible) {
            mainSplit.getItems().add(aiPanel);
            int divider = mainSplit.getItems().size() - 2;
            // Twice: a divider cannot be positioned until the new item has been laid out,
            // the same two-pass dance the split's own mode switching needs.
            mainSplit.setDividerPosition(divider, assistantDivider);
            Platform.runLater(() -> mainSplit.setDividerPosition(divider, assistantDivider));
            /* Three columns is one too many. With the tree, the editor, the preview and
               the assistant all sharing the width, none of them is wide enough to work in
               - and the assistant is for reading about the document, which is what the
               preview is for too. The editor stands down while it is open, and the mode it
               was in comes back when it closes. */
            modeBeforeAssistant = currentMode;
            if (currentMode != EditorMode.FULL_PREVIEW) {
                currentMode = EditorMode.FULL_PREVIEW;
                updateLayout();
            }
            aiPanel.getInput().requestFocus();
        } else {
            int divider = mainSplit.getItems().size() - 2;
            if (divider >= 0 && divider < mainSplit.getDividerPositions().length) {
                assistantDivider = mainSplit.getDividerPositions()[divider];
            }
            mainSplit.getItems().remove(aiPanel);
            /* The preview keeps the whole column. Springing back to a split editor on
               close was worse than leaving it alone: closing the assistant is asking for
               more room to read in, and being handed a half-width preview and an editor
               nobody asked for is the opposite of that. Raw and Split are one click away
               for anyone who wants them. */
            modeBeforeAssistant = null;
        }
        assistantMenuItem.setText(visible ? "Hide Assistant" : "Show Assistant");
        // The menu and the stripe are two ways to the same switch, so neither may show a
        // state the other has just changed.
        assistantTab.setSelected(visible);
    }

    @FXML
    private void handleToggleExplorer() {
        showExplorer(!isExplorerVisible());
    }

    /**
     * Turns drag-to-scroll on or off.
     *
     * <p>A touchscreen that the system reports as an ordinary pointer is indistinguishable
     * from a mouse, so the app cannot decide this for you: the same drag is a selection to
     * one person and a scroll to another. The tick is remembered between runs.
     */
    @FXML
    private void handleToggleTouchScroll() {
        touchScroll.setEnabled(touchScrollMenuItem.isSelected());
        if (fileTreePanel != null) {
            fileTreePanel.setOpenOnSingleClick(touchScroll.isEnabled());
        }
    }

    /**
     * Grows every hit target while touch mode is on.
     *
     * <p>Shares the switch with drag-to-scroll because the two problems arrive together: a
     * machine being driven by a finger also needs targets a finger can hit. Applied as a
     * style class on the root so the rules live in the stylesheet with everything else,
     * rather than as sizes set from code that the theme cannot then override.
     */
    /**
     * Keeps the layout matched to how much width there actually is.
     *
     * <p>Watches the scene rather than the screen, so rotating a tablet or dragging the
     * window narrower is noticed. The scene does not exist while FXML is being built, hence
     * the wait for it to arrive.
     */
    private void installResponsiveLayout() {
        if (rootPane == null) {
            return;
        }
        if (rootPane.getScene() != null) {
            watchWidth(rootPane.getScene());
        } else {
            rootPane.sceneProperty().addListener((observable, had, scene) -> {
                if (scene != null) {
                    watchWidth(scene);
                }
            });
        }
    }

    private void watchWidth(javafx.scene.Scene scene) {
        // Menu stylesheets attach to the scene, which did not exist while FXML was built.
        styleMenus();
        applyWidth(scene.getWidth());
        scene.widthProperty().addListener(
                (observable, was, now) -> applyWidth(now.doubleValue()));
    }

    private void applyWidth(double width) {
        if (width > 0) {
            capExplorer(width);
        }
    }

    /**
     * Stops the file tree taking a third of a narrow window.
     *
     * <p>The divider is a fraction, so a sidebar that looks right at 1600px is half the
     * document at 800. This only ever narrows it: a wide window is left alone, and so is a
     * sidebar the reader has already dragged smaller than the cap.
     */
    private void capExplorer(double width) {
        if (mainSplit == null || !isExplorerVisible() || mainSplit.getDividers().isEmpty()) {
            return;
        }
        double cap = MAX_EXPLORER_WIDTH / width;
        if (mainSplit.getDividerPositions()[0] > cap) {
            mainSplit.setDividerPosition(0, cap);
            explorerDivider = cap;
        }
    }

    /**
     * Applies the chosen display size, and ticks the menu item that matches it.
     *
     * <p>Regular adds no class at all. Styling the baseline would mean every rule in the
     * stylesheet needing a counterpart, and a theme that forgets one silently stops
     * applying at whichever size nobody tested.
     */
    private void applyDisplaySize() {
        if (rootPane == null) {
            return;
        }
        rootPane.getStyleClass().removeAll(DisplaySize.allStyleClasses());
        String wanted = displaySize.styleClass();
        if (wanted != null && !rootPane.getStyleClass().contains(wanted)) {
            rootPane.getStyleClass().add(wanted);
        }
        if (displayTabletItem != null) {
            displayTabletItem.setSelected(displaySize == DisplaySize.TABLET);
            displayRegularItem.setSelected(displaySize == DisplaySize.REGULAR);
            displayLargeItem.setSelected(displaySize == DisplaySize.LARGE);
        }
        styleMenus();
    }

    /**
     * Sizes the menu popups, which the main stylesheet cannot reach on its own.
     *
     * <p>A menu popup is its own window. It inherits the scene's <em>stylesheets</em> - the
     * recent workspaces rows in this application are styled from the main file and they
     * live in a popup - but not the root's <em>style classes</em>. So a rule written as
     * {@code .display-tablet .menu-item} can never match, while a plain {@code .menu-item}
     * always will. That is why the first attempt at this silently did nothing.
     *
     * <p>The way to scope an unscopable rule is therefore to scope the file: one small
     * stylesheet per size, added to the scene when that size is chosen and taken away when
     * it is not.
     */
    private void styleMenus() {
        if (rootPane == null || rootPane.getScene() == null) {
            return;
        }
        var sheets = rootPane.getScene().getStylesheets();
        sheets.removeAll(menuSheet("menus-tablet"), menuSheet("menus-large"));
        String wanted = switch (displaySize) {
            case TABLET -> menuSheet("menus-tablet");
            case LARGE -> menuSheet("menus-large");
            case REGULAR -> null;
        };
        if (wanted != null && !sheets.contains(wanted)) {
            sheets.add(wanted);
        }
    }

    private String menuSheet(String name) {
        return MainController.class.getResource("/css/" + name + ".css").toExternalForm();
    }

    /** Settings > Display Size. */
    @FXML
    private void handleDisplaySize() {
        if (displayTabletItem != null && displayTabletItem.isSelected()) {
            displaySize = DisplaySize.TABLET;
        } else if (displayLargeItem != null && displayLargeItem.isSelected()) {
            displaySize = DisplaySize.LARGE;
        } else {
            displaySize = DisplaySize.REGULAR;
        }
        displaySize.save(uiSettings);
        applyDisplaySize();
        setTransientStatus("Display size: " + displaySize.label());
    }

    /**
     * Rebuilds the Recent Workspaces menu from the history file.
     *
     * <p>Folders that are already open are shown but disabled rather than hidden: a
     * workspace vanishing from the list the moment you open it makes the list look like it
     * forgot, and the greyed entry is what tells you it is already there.
     */
    private void rebuildRecentWorkspaces() {
        recentWorkspacesMenu.getItems().clear();
        // Items added below are new objects, so they carry none of the sizing applied
        // earlier; styleMenus() runs again at the end of this method.
        List<Path> recent = workspaceHistory.list();
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem("No recent workspaces");
            empty.setDisable(true);
            recentWorkspacesMenu.getItems().add(empty);
            styleMenus();
            return;
        }
        for (Path root : recent) {
            boolean alreadyOpen = workspaces.stream()
                    .anyMatch(w -> root.equals(w.getRoot()));
            recentWorkspacesMenu.getItems().add(recentWorkspaceItem(root, alreadyOpen));
        }
        MenuItem clear = new MenuItem("Clear Recent Workspaces");
        clear.setOnAction(e -> {
            workspaceHistory.clear();
            rebuildRecentWorkspaces();
            setTransientStatus("Recent workspaces cleared.");
        });
        recentWorkspacesMenu.getItems().addAll(new SeparatorMenuItem(), clear);
        styleMenus();
    }

    /**
     * One row of the recent list: the workspace, and a cross to forget it.
     *
     * <p>A {@link CustomMenuItem} rather than a MenuItem, because a MenuItem is one label
     * and one action and this row needs two of each. Clearing the whole list was the only
     * way to drop a single entry before, which is a poor trade when one scratch folder is
     * sitting among the projects you actually use.
     *
     * <p>{@code hideOnClick} is off: the cross removes the entry and the menu stays open,
     * so several can be dropped in one go. Opening a workspace does close it, which is why
     * that path hides the menu itself.
     */
    private CustomMenuItem recentWorkspaceItem(Path root, boolean alreadyOpen) {
        Label label = new Label(recentLabel(root));
        /* Its own class, and its own colour in the stylesheet. A plain Label in here takes
           its fill from modena's ".menu-item .label", a rule written for native menu rows;
           relying on it means the text is one selector away from being invisible against
           the popup, with nothing to say why. A custom row should colour its own text. */
        label.getStyleClass().add("recent-row-label");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setDisable(alreadyOpen);
        HBox.setHgrow(label, Priority.ALWAYS);

        Button forget = new Button("\u2715");
        forget.getStyleClass().add("recent-forget");
        forget.setFocusTraversable(false);
        forget.setTooltip(new Tooltip("Forget " + root));
        forget.setOnAction(e -> {
            // Consumed so the row's own action does not also run and open the workspace
            // that has just been forgotten.
            e.consume();
            workspaceHistory.remove(root);
            rebuildRecentWorkspaces();
            setTransientStatus("Removed " + root.getFileName() + " from recent workspaces.");
        });

        /* The spacer only helps once every row is the same width, which a menu does not
           arrange on its own: a CustomMenuItem's content keeps its own preferred width
           rather than being stretched to the popup, measured at 302 pixels for one row
           and 87 for another in the same 338-pixel menu. So each cross landed wherever
           its own path happened to end. alignRecentRows below levels the labels once the
           menu is up and its real widths are known. */
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, label, gap, forget);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        item.setOnAction(e -> {
            if (alreadyOpen) {
                return;
            }
            closeMenuBarMenu();
            openRecentWorkspace(root);
        });
        return item;
    }

    /**
     * Closes the whole File menu, through the menu rather than around it.
     *
     * <p>These rows do not hide on click, because the cross has to be pressable without
     * the menu closing under it - so opening a workspace has to close the menu itself.
     * Hiding the popup was the obvious way and the wrong one: the popup is the File menu's,
     * and hiding it directly leaves {@code Menu.showing} true. The menu bar tracks that
     * property to know which of its buttons is open, so it went on believing File was
     * still showing and ignored every click on it until another menu was opened and the
     * bar's idea of the world was corrected.
     *
     * <p>Hiding the top Menu instead sets that property, which is what the bar is
     * listening for.
     */
    private void closeMenuBarMenu() {
        Menu menu = recentWorkspacesMenu;
        while (menu.getParentMenu() != null) {
            menu = menu.getParentMenu();
        }
        menu.hide();
    }

    /**
     * Widens every recent row's label to the widest, so the crosses line up.
     *
     * <p>A menu does not stretch a CustomMenuItem's content to the popup width - each row
     * keeps its own preferred width - so a growing spacer has nothing to grow into and
     * every cross sits at the end of its own path instead of at the edge. Giving all the
     * labels the widest label's width makes the rows equal, which is what the spacer
     * needs.
     *
     * <p>Measured rather than computed: the width is read back after the popup is on
     * screen, when the labels have the fonts the stylesheet gave them. Guessing from
     * character counts gets the widest row wrong as soon as a path has different letters
     * in it.
     */
    private void alignRecentRows() {
        alignRows(recentWorkspacesMenu.getItems());
    }

    /** Takes the items rather than reading the field, so it can be exercised on its own. */
    static void alignRows(List<MenuItem> items) {
        List<Label> labels = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof CustomMenuItem custom
                    && custom.getContent() instanceof HBox row
                    && !row.getChildren().isEmpty()
                    && row.getChildren().get(0) instanceof Label label) {
                labels.add(label);
            }
        }
        double widest = 0;
        for (Label label : labels) {
            widest = Math.max(widest, label.getWidth());
        }
        if (widest <= 0) {
            return; // Not laid out yet; nothing useful to level against.
        }
        for (Label label : labels) {
            label.setMinWidth(widest);
        }
    }

    /**
     * Folder name first, then where it is - "MDViewer  —  C:\\Users\\...\\codes".
     *
     * <p>Several checkouts of the same project are the normal case, so the name alone is
     * ambiguous exactly when the list is most useful.
     */
    private static String recentLabel(Path root) {
        Path name = root.getFileName();
        Path parent = root.getParent();
        if (name == null) {
            return root.toString();
        }
        return parent == null ? name.toString() : name + "  \u2014  " + parent;
    }

    private void openRecentWorkspace(Path root) {
        if (!Files.isDirectory(root)) {
            // Gone since it was recorded: drop it rather than leaving a dead entry.
            workspaceHistory.remove(root);
            rebuildRecentWorkspaces();
            setTransientStatus("That folder no longer exists - removed from recent workspaces.");
            return;
        }
        WorkspaceView workspace = addWorkspace(root);
        if (workspace == null) {
            return; // addWorkspace already explained why, e.g. the workspace limit.
        }
        workspaceTabs.getSelectionModel().select(workspace.getTab());
        fileTreePanel.addWorkspaceRoot(root);
        onActiveDocumentChanged();
        setTransientStatus("Opened workspace " + root.getFileName() + ".");
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
        /* The transcript is a WebView too, with the preview's own stylesheet, so it needs
           telling as well - the panel's JavaFX chrome follows the .dark-theme class on the
           scene root and looked right, which made the white page inside it read as a bug
           in the panel rather than as the one part nobody had told. */
        if (aiPanel != null) {
            aiPanel.setDarkMode(darkMode);
        }
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

    /**
     * True while waiting to swallow the backspace character that Ctrl+H also produces.
     *
     * <p>Deliberately one-shot rather than a standing rule: Ctrl+Backspace - delete the
     * previous word - is a keystroke people actually use in the editor, and a filter that
     * simply dropped every control-modified backspace would take that away too.
     */
    private boolean swallowNextBackspaceChar = false;

    /**
     * Makes Ctrl+H open Find and Replace in the editor as well as the preview.
     *
     * <p>The menu accelerator alone does not get there. Ctrl+H is ASCII 0x08 - the
     * backspace character - so the keystroke reaches a focused text area twice: once as a
     * key press the control's own behaviour claims, and again as a typed backspace. The
     * accelerator never fired and the character was deleted instead, which is exactly what
     * "it works like backspace" means.
     *
     * <p>A filter on the root sees both before any control does. Consuming the press is
     * not enough on its own: unlike a browser, JavaFX still delivers the typed character
     * afterwards, so that has to be swallowed too or the deletion happens anyway.
     */
    private void installReplaceShortcut() {
        rootPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.H && event.isShortcutDown() && !event.isAltDown()) {
                swallowNextBackspaceChar = true;
                handleFindReplace();
                event.consume();
            }
        });
        rootPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!swallowNextBackspaceChar) {
                return;
            }
            // Cleared whatever arrives, so a Ctrl+H that produced no character cannot
            // leave this armed for some later, unrelated backspace.
            swallowNextBackspaceChar = false;
            if ("\b".equals(event.getCharacter())) {
                event.consume();
            }
        });
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

        // A chart's menu is built per right-click rather than once, because which forms
        // are on offer depends on the data in the chart under the pointer.
        ContextMenu chartMenu = new ContextMenu();

        webView.setOnContextMenuRequested(event -> {
            textMenu.hide();
            imageMenu.hide();
            codeMenu.hide();
            chartMenu.hide();
            // The page records what was under the pointer on mousedown, which fires
            // before this, so the choice of menu is already known.
            boolean onImage = !previewString("window.__mdImageInfo()").isEmpty();
            boolean onCode = !previewString("window.__mdCodeInfo()").isEmpty();
            String chart = previewString("window.__mdChartInfo()");
            ContextMenu menu;
            if (!chart.isEmpty()) {
                fillChartMenu(chartMenu, chart);
                menu = chartMenu;
            } else {
                menu = onImage ? imageMenu : onCode ? codeMenu : textMenu;
            }
            menu.show(webView, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        webView.setOnMousePressed(event -> {
            textMenu.hide();
            imageMenu.hide();
            codeMenu.hide();
            chartMenu.hide();
        });
    }

    /** Chart forms, in the order the picker offers them: fence value to display name. */
    private static final String[][] CHART_FORMS = {
            {"bar", "Bar"},
            {"column", "Column"},
            {"line", "Line"},
            {"area", "Area"},
            {"pie", "Pie"},
            {"donut", "Donut"},
            {"stat", "Stat"},
    };

    /**
     * Rebuilds the chart menu for the chart that was just right-clicked.
     *
     * <p>Forms the data cannot take are shown disabled with the reason beside them rather
     * than hidden. Hiding them would leave the reader to guess why a chart they have seen
     * elsewhere is not on offer here, and the reason - "needs one value per row", "shows
     * one number; this has 12" - is the part worth reading. The current form is disabled
     * too, for the plainer reason that it is already what you have.
     *
     * @param info {@code start,end,describe-output} as recorded on mousedown
     */
    private void fillChartMenu(ContextMenu menu, String info) {
        menu.getItems().clear();
        String[] parts = info.split(",", 3);
        Map<String, String> verdicts = new LinkedHashMap<>();
        if (parts.length > 2) {
            for (String line : parts[2].split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    verdicts.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                }
            }
        }
        String current = verdicts.getOrDefault("type", "");

        Menu change = new Menu("Change chart to");
        for (String[] form : CHART_FORMS) {
            String verdict = verdicts.get(form[0]);
            boolean isCurrent = form[0].equals(current);
            boolean fits = "ok".equals(verdict);
            MenuItem item = new MenuItem(isCurrent ? form[1] + "  (current)"
                    : fits ? form[1]
                    : form[1] + "  -  " + verdict);
            item.setDisable(isCurrent || !fits);
            item.setOnAction(e -> setChartType(form[0]));
            change.getItems().add(item);
        }

        MenuItem edit = new MenuItem("Edit chart data...");
        edit.setOnAction(e -> editChartFromMenu(info));

        menu.getItems().addAll(change, new SeparatorMenuItem(), edit, copyItem("Copy"));
    }

    /** Opens the chart editor on whichever chart was right-clicked. */
    private void editChartFromMenu(String info) {
        String[] parts = info.split(",", 3);
        int start;
        int end;
        try {
            start = Integer.parseInt(parts[0]);
            end = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        openChartEditor(start, end, previewString(
                "(function(){var el=window.__mdChartElement;"
                + "return el ? (el.getAttribute('data-mdc-src') || '') : '';})()"));
    }

    /**
     * The chart editor: a form over the fence, rather than the fence as text.
     *
     * <p>The grid comes from the renderer's own reading of the source - there is one
     * parser for this syntax and it is the one that draws the chart, so what the form
     * shows can never disagree with the picture it was opened from.
     *
     * <p>The write-back is guarded the same way an in-place block edit is: the offsets
     * came from a render, a render can be a moment behind the editor, and writing to a
     * range that has moved would overwrite whatever is in it now.
     */
    private void openChartEditor(int start, int end, String source) {
        DocumentView document = activeDocument();
        if (document == null) {
            return;
        }
        String text = document.getEditor().getText();
        if (start < 0 || end > text.length() || start >= end) {
            setTransientStatus("That chart could not be located in the source.");
            return;
        }
        String original = text.substring(start, end);
        String model = previewString("MdChart.model("
                + toJsStringLiteral(source == null ? "" : source) + ")");
        if (model.isEmpty()) {
            setTransientStatus("The chart could not be read for editing.");
            return;
        }

        String fence = ChartDialog.edit(primaryStage, ChartData.fromModel(model));
        if (fence == null) {
            return; // Cancelled.
        }

        String now = document.getEditor().getText();
        if (end > now.length() || !now.substring(start, end).equals(original)) {
            setTransientStatus("The document changed while the chart was open; "
                    + "the edit was not applied.");
            updatePreview();
            return;
        }
        applyEdit(document, new SourceEdits.Edit(
                start, end, fence, start, start + fence.length()));
        setTransientStatus("Chart updated.");
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

        /* Charts are laid out in pixels for the pane they are in, which is not the width
           of the paper. Letting the page scale them to fit would shrink their type along
           with their geometry, so they are drawn again for the page the job is actually
           going to - taken from the settings the dialog returned, since paper size and
           orientation are both still the user's to change in there. */
        prepareChartsForPrint(job.getJobSettings().getPageLayout());
        try {
            webView.getEngine().print(job);
        } finally {
            // In a finally block because a print that throws must not leave the reader
            // looking at charts laid out for A4 and every table view forced open.
            previewString("window.__mdChartsAfterPrint()");
        }
        job.endJob();
        setTransientStatus("Sent " + jobName + " to " + job.getPrinter().getName() + ".");
    }

    /**
     * Draws every chart again at the width of the page being printed, and opens the table
     * views so the numbers are on the paper.
     *
     * <p>The printable width comes back from the page layout in points - a seventy-second
     * of an inch - and CSS pixels are ninety-sixths of one, so the conversion is the ratio
     * of those two. Without it a chart drawn for a 430-pixel pane prints at 430 pixels on
     * a 720-pixel page, taking up three-fifths of the width it was given.
     *
     * <p>Undone by {@code __mdChartsAfterPrint} once the job has been handed over.
     */
    private void prepareChartsForPrint(PageLayout layout) {
        if (layout == null) {
            return;
        }
        int width = (int) Math.round(layout.getPrintableWidth() * 96.0 / 72.0);
        if (width < 320) {
            return; // Not a page anything readable fits on; leave the charts as they are.
        }
        previewString("window.__mdChartsForPrint(" + width + ")");
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
    /**
     * Ctrl+V with a picture on the clipboard writes the picture, not nothing.
     *
     * <p>A TextArea pastes text. Given an image it finds no text flavour and inserts
     * nothing at all - no error, no character, which reads as the paste key having failed.
     * Taking a screenshot and putting it in a document is one of the main things a markdown
     * editor is for, so the same thing that happens through <em>Insert image</em> happens
     * here: the bytes are written into assets/ beside the document and a reference to them
     * is inserted at the caret.
     *
     * <p>An event filter, so it runs before the control's own paste. Only images are taken;
     * text falls through untouched, which is the common case and must stay ordinary.
     *
     * <p>This covers the keystroke, not the context menu's Paste item - that goes straight
     * to the skin and cannot be intercepted here.
     */
    /**
     * Brings the on-screen keyboard up when the caret lands in this editor.
     *
     * <p>Tied to touch mode, because that is the switch that says a finger is driving. On a
     * desktop the keyboard is already under your hands and summoning one would be an
     * interruption.
     *
     * <p>JavaFX cannot do this the ordinary way - it takes no part in the input-method
     * protocol, so the compositor never learns that a text field has focus. See
     * {@link VirtualKeyboard}.
     */
    private void installKeyboardSummon(TextArea editor) {
        keyboardHide.setOnFinished(e -> virtualKeyboard.hide());
        editor.focusedProperty().addListener((observable, was, focused) -> {
            if (!touchScroll.isEnabled()) {
                return;
            }
            if (focused) {
                keyboardHide.stop();
                virtualKeyboard.show();
            } else {
                keyboardHide.playFromStart();
            }
        });
    }

    private void installImagePaste(DocumentView document) {
        javafx.scene.input.KeyCombination paste = new javafx.scene.input.KeyCodeCombination(
                javafx.scene.input.KeyCode.V, javafx.scene.input.KeyCombination.SHORTCUT_DOWN);
        document.getEditor().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (paste.match(event) && pasteImage(document, false)) {
                event.consume();
            }
        });
    }

    /**
     * @return true when the clipboard held an image and this handled it - including the
     *         cases where it could not, since falling through to a text paste after
     *         refusing an image would put something unexpected in the document
     */
    /**
     * Edit &gt; Paste Image.
     *
     * <p>The same work as Ctrl+V, reachable when the keystroke is not. A context menu's
     * Paste goes straight to the control's skin and cannot be intercepted from here, and on
     * a tablet the keystroke arrives from an on-screen keyboard that may or may not deliver
     * it - so the one route that is always available is a menu item.
     *
     * <p>Asked explicitly, it answers explicitly: if there is no picture to paste it says
     * what the clipboard does hold, in a dialog rather than the status bar. Somebody who
     * has just chosen "Paste Image" is owed a reason, and a line in the status bar is easy
     * to miss on a small screen.
     */
    @FXML
    private void handlePasteImage() {
        DocumentView document = activeDocument();
        if (document == null) {
            setTransientStatus("Open a document before pasting an image.");
            return;
        }
        document.getEditor().requestFocus();
        pasteImage(document, true);
    }

    /**
     * @param loud whether to explain in a dialog when there is nothing to paste, rather
     *             than falling through quietly to let an ordinary text paste happen
     */
    private boolean pasteImage(DocumentView document, boolean loud) {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        /*
         * Three shapes, because a screenshot tool decides which one you get and they are
         * not interchangeable. Spectacle's "copy image" puts pixels on the clipboard;
         * "copy location" and a file manager put a file; some tools offer only a file URI
         * as text. All three mean "the user wants this picture in the document".
         */
        boolean hasImage = clipboard.hasImage();
        Path pastedFile = firstImageFile(clipboard);

        /*
         * JavaFX and AWT reach the same X11 selection through different code, and on Linux
         * they disagree: hasImage() regularly answers false for a screenshot AWT reads
         * without complaint. Asked only after JavaFX has said no, so the ordinary path is
         * untouched and this costs nothing when it is not needed.
         */
        byte[] viaAwt = hasImage || pastedFile != null
                ? null
                : com.mdviewer.ui.ClipboardImage.fromSystemClipboard();

        /*
         * A backstop for compositors that do not bridge pictures to X11. KDE does - a
         * copied screenshot arrives on the X11 clipboard in forty formats, image/png among
         * them - so on that desktop neither this nor the AWT read above is ever reached,
         * and that is the intended shape: the ordinary path stays the ordinary path.
         */
        byte[] viaCommand = hasImage || pastedFile != null || viaAwt != null
                ? null
                : com.mdviewer.ui.ClipboardImage.fromCommand(clipboardImageCommand());

        if (!hasImage && pastedFile == null && viaAwt == null && viaCommand == null) {
            if (!loud && (clipboard.hasString() || clipboard.hasHtml())) {
                return false;      // ordinary text: leave the paste alone
            }
            /*
             * Nothing usable and nothing to type. Rather than the silence that made this
             * look like a broken key, say what the clipboard actually holds - the answer
             * is normally either "nothing" or a format worth adding here.
             */
            String formats = clipboard.getContentTypes().isEmpty()
                    ? "nothing at all"
                    : clipboard.getContentTypes().stream()
                            .map(Object::toString)
                            .reduce((a, b) -> a + ", " + b).orElse("nothing at all");
            if (loud) {
                /* Both views of the same clipboard, because they disagree often enough
                   that knowing which one saw what is the whole diagnosis. */
                String viaSystem = com.mdviewer.ui.ClipboardImage.systemClipboardFormats();
                String command = clipboardImageCommand();
                showAlert("No image to paste",
                        "JavaFX sees: " + formats + ".\n"
                        + "The system clipboard offers: "
                        + (viaSystem.isEmpty() ? "nothing at all" : viaSystem) + ".\n\n"
                        + (command.isBlank()
                                ? "If you have just taken a screenshot, the tool may have "
                                  + "copied its location rather than the picture."
                                : "No picture reached this application. If this desktop "
                                  + "does not share pictures with X11 programs, a helper "
                                  + "can read the clipboard directly:\n\n"
                                  + "    sudo apt install wl-clipboard\n\n"
                                  + "This tried: " + command));
            } else {
                setTransientStatus("Nothing to paste. The clipboard holds: " + formats);
            }
            return true;
        }

        Path baseDir = document.getBaseDir();
        if (baseDir == null) {
            /*
             * The image has to live somewhere, and it belongs beside the document rather
             * than in a temporary folder that a moved file would lose. An unsaved document
             * has no beside yet.
             *
             * This used to be a line in the status bar, which is how it stayed a mystery:
             * pasting text worked, pasting a picture did nothing visible, and the sentence
             * explaining why sat in eleven point grey at the bottom of the window. Ask
             * instead, and then do the thing that was asked for.
             */
            if (!askToSaveBeforePasting(document)) {
                return true;
            }
            baseDir = document.getBaseDir();
            if (baseDir == null) {
                return true;      // the save was cancelled or failed; it has said so
            }
        }

        try {
            Path assets = baseDir.resolve("assets");
            Files.createDirectories(assets);
            Path target;
            if (hasImage || viaAwt != null || viaCommand != null) {
                byte[] png = viaAwt != null ? viaAwt
                        : viaCommand != null ? viaCommand
                        : com.mdviewer.ui.ClipboardImage.png(clipboard.getImage());
                if (png == null) {
                    setTransientStatus("That image could not be read from the clipboard.");
                    return true;
                }
                String stamp = java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                target = uniqueTarget(assets, "pasted-" + stamp + ".png");
                Files.write(target, png);
            } else {
                target = uniqueTarget(assets, pastedFile.getFileName().toString());
                Files.copy(pastedFile, target);
            }

            String alt = stripExtension(target.getFileName().toString());
            String snippet = new ImageRef(alt, relativeAsset(document, target)).toMarkup();
            TextArea editor = document.getEditor();
            int caret = editor.getCaretPosition();
            editor.insertText(caret, snippet);
            editor.selectRange(caret + 2, caret + 2 + alt.length());
            previewDebounce.stop();
            updatePreview();
            setTransientStatus("Pasted into assets/" + target.getFileName());
        } catch (IOException e) {
            showAlert("Error", "Could not save the pasted image: " + e.getMessage());
        }
        return true;
    }

    /**
     * Offers to save an untitled document, so a pasted image has somewhere to go.
     *
     * @return whether the document now has a home on disk
     */
    private boolean askToSaveBeforePasting(DocumentView document) {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "This document has not been saved yet, so there is nowhere to put the "
                + "image.\n\nSave it now, and the picture will be stored in an assets "
                + "folder beside it.",
                ButtonType.CANCEL, ButtonType.OK);
        ask.setTitle("Save before pasting");
        ask.setHeaderText("Save this document first?");
        ask.initOwner(primaryStage);
        ask.getDialogPane().getStylesheets().setAll(primaryStage.getScene().getStylesheets());
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        handleSaveAs();
        return document.getBaseDir() != null;
    }

    /**
     * The command asked for the clipboard's picture when no Java API can see it.
     *
     * <p>Configurable in {@code ~/.mdviewer/ui.properties} for the same reason the
     * on-screen keyboard's command is: the right answer differs by desktop, and a line of
     * configuration beats a build. Empty switches it off.
     */
    private String clipboardImageCommand() {
        String stored = uiSettings.get("clipboardImageCommand");
        if (stored != null) {
            String trimmed = stored.trim();
            /* Empty means "unset", not "off" - see VirtualKeyboard for why that matters. */
            if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("none")) {
                return "";
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("linux") ? "wl-paste --type image/png" : "";
    }

    /** An image the clipboard is offering as a file, either directly or as a file: URI. */
    private static Path firstImageFile(Clipboard clipboard) {
        if (clipboard.hasFiles()) {
            for (File file : clipboard.getFiles()) {
                if (looksLikeAnImage(file.getName())) {
                    return file.toPath();
                }
            }
        }
        String url = clipboard.hasUrl() ? clipboard.getUrl()
                : clipboard.hasString() ? clipboard.getString() : null;
        if (url != null && url.startsWith("file:") && looksLikeAnImage(url)) {
            try {
                return Path.of(java.net.URI.create(url.trim()));
            } catch (RuntimeException notAPath) {
                return null;
            }
        }
        return null;
    }

    private static boolean looksLikeAnImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

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

    /**
     * Inserts a chart of the chosen form, with example data already in it.
     *
     * <p>The title is selected rather than the first number, because naming the chart is
     * the one thing every chart needs and the numbers are easier to find once it draws.
     */
    private void insertChart(String type) {
        DocumentView document = activeDocument();
        if (document == null) {
            setTransientStatus("Open a document before inserting a chart.");
            return;
        }
        applyEdit(document, SourceEdits.insertChart(
                document.getEditor().getText(),
                document.getEditor().getCaretPosition(), type));
        document.getEditor().requestFocus();
        setTransientStatus("Inserted a " + type + " chart - replace the example data.");
    }

    /**
     * Changes the form of the chart that was right-clicked.
     *
     * <p>Only the {@code type:} line is rewritten, so the data survives the change intact -
     * which is the whole point of offering this: seeing the same numbers as a column chart
     * and as a line is how you find out which one they wanted to be.
     */
    private void setChartType(String type) {
        DocumentView document = activeDocument();
        String info = previewString("window.__mdChartInfo()");
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
        SourceEdits.Edit edit = SourceEdits.setChartType(
                document.getEditor().getText(), start, end, type);
        if (edit == null) {
            setTransientStatus("That chart could not be located in the source.");
            return;
        }
        applyEdit(document, edit);
        setTransientStatus("Chart changed to " + type + ".");
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
                injectCharts();
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
                // Offered again rather than only on the first render: the cache outlives a
                // sign-in, so a diagram drawn before signing in would otherwise never go up.
                diagramUpload.offer(diagram.source(), cached);
                setDiagram(diagram.id(), cached, generation);
                continue;
            }
            diagramService.renderAsync(diagram.source()).thenAccept(svg -> {
                /* Drawn once, here, on the machine that was going to draw it anyway. The
                   browser cannot render PlantUML and the cloud server will not, so this is
                   where the picture it shows comes from. Best-effort and silent: a failed
                   upload costs a picture in the browser and nothing here. */
                diagramUpload.offer(diagram.source(), svg);
                Platform.runLater(() -> setDiagram(diagram.id(), svg, generation));
            });
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

        /**
         * Opens the chart editor on the fence at {@code [start, end)}.
         *
         * <p>The fence is taken apart by the renderer rather than here, so the form and
         * the picture can never disagree about what the source says; {@code source} is
         * the text the chart was compiled from, which the page already has.
         *
         * <p>Deferred like every other bridge call: this arrives inside a DOM event
         * handler, and opening a modal dialog from there would block the page's own event
         * loop while the reader fills it in.
         */
        public void editChart(int start, int end, String source) {
            Platform.runLater(() -> openChartEditor(start, end, source));
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

    /**
     * Loads the chart compiler into the preview page.
     *
     * <p>Like the highlighter and mermaid, it is evaluated into the shell once rather than
     * linked, because the page has no base URL to resolve a script tag against. If it is
     * missing the fence body stays on screen as text, which for a chart is a readable
     * table of numbers - a better failure than a blank plate.
     */
    private void injectCharts() {
        try (InputStream in = getClass().getResourceAsStream("/js/mdchart.js")) {
            if (in == null) {
                System.err.println("MDViewer: mdchart.js not on the classpath "
                        + "- chart blocks will stay as plain text.");
                return;
            }
            webView.getEngine().executeScript(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: mdchart unavailable - " + e);
        }
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
    /**
     * The preview's stylesheet, on its own so the assistant panel can borrow it.
     *
     * <p>An answer about a Markdown document that arrives as unformatted text is harder to
     * read than the document it is about - headings, tables and code blocks all flattened
     * into one grey wall. Sharing the sheet means the assistant's prose looks like the
     * preview's, down to the theme, without a second set of rules to keep in step.
     */
    /**
     * The chart library's own stylesheet, read from the classpath.
     *
     * <p>Vendored from ../mdchart by sync-mdchart.ps1 rather than copied by hand. It used
     * to be a block of rules inside the text block below, which meant two copies of the
     * same design - one here and one in the library - with nothing keeping them in step.
     *
     * <p>It is placed before this app's own rules so the block further down can map
     * MDViewer's palette onto the library's {@code --mdc-*} variables and win.
     */
    private static String chartCss() {
        try (InputStream in = MainController.class.getResourceAsStream("/css/mdchart.css")) {
            if (in == null) {
                System.err.println("MDViewer: mdchart.css not on the classpath "
                        + "- charts will render without colour.");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("MDViewer: chart stylesheet unavailable - " + e);
            return "";
        }
    }

    public static String previewCss() {
        return chartCss() + """
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
            body > pre.mermaid, body > .mdv-diagram-error, body > hr,
            body > .mdv-chart-out, body > pre.mdv-chart {
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
               will not read - but an unbroken URL still has to be allowed to break.

               overflow-wrap only, without word-break. word-break:break-word behaves like
               overflow-wrap:anywhere, and anywhere is not just permission to break a long
               word - it tells the layout that a column's narrowest possible width is one
               character. An auto table believes it: a "Question" column against a wide
               "Decision" one was squeezed to "QUE / STIO / N", a word per three lines,
               while the column beside it took the rest of the table. overflow-wrap breaks
               the URL that genuinely cannot fit and leaves the column widths alone. */
            th, td { overflow-wrap:break-word; }
            /* A floor under the first column, which is where a short label sits beside a
               paragraph and gets shaved to fit it.

               A plain length, not min(20ch, max-content): min() takes lengths and
               percentages, and an intrinsic keyword inside it makes the whole declaration
               invalid, so it is dropped and the column goes back to being squeezed. That
               failure is silent - the measured width was identical with 14ch and with
               24ch, which is what gave it away.

               In ch rather than pixels so it tracks the font, which is what decides how
               much a character is worth. Twenty of them holds a label like "What requires
               login" on one line, and is little enough that a column of single words does
               not read as mostly empty. */
            th:first-child, td:first-child { min-width:20ch; }
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
            /* A chart is double-clicked to open its editor, so it gets the pointer that
               says "this opens something" rather than a text caret over a picture. */
            .mdv-chart-out { cursor:default; }
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
            /* ---------------------------------------------------------- charts

               The rules themselves are mdchart.css, loaded above from the library's own
               repository. All that is left here is the join: MDViewer's palette mapped
               onto the library's --mdc-* variables, so a chart takes the document's rule
               colour, accent, plate shadow and typefaces and looks like it belongs on
               the page rather than like something pasted onto it.

               Declared on :root rather than on the figure, because the theme switch
               redefines --rule and the rest there - so these follow it with no rule of
               their own for dark mode. That is also why a chart repaints on a theme
               switch with no re-render, which mermaid and PlantUML cannot do: they bake
               their colours into the SVG and have to sit on a pinned white plate. */
            :root {
              --mdc-line:var(--rule);
              --mdc-accent:var(--accent);
              --mdc-shadow:var(--plate-shadow);
              --mdc-font:var(--body);
              --mdc-mono:var(--mono);
              --mdc-mark:var(--mark);
              --mdc-note-bg:var(--stripe);
              --mdc-error-ink:var(--err-fg);
              --mdc-error-bg:var(--err-bg);
              --mdc-error-line:var(--err-line);
            }

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
              body > pre.mermaid, body > .mdv-diagram-error, body > hr,
              body > .mdv-chart-out, body > pre.mdv-chart {
                width:100%; margin-left:0;
              }

              /* Anything that is one visual object moves to the next page whole
                 rather than being cut in half by a page break. */
              img, figure, .mdv-diagram, pre.mermaid, .mdv-diagram-error, .mdv-code {
                page-break-inside:avoid; break-inside:avoid;
              }

              /* Charts on paper are mdchart.css's own print block: kept whole, never
                 split across a fold, nothing allowed to scroll off an edge that cannot
                 be scrolled. Two things it cannot do from CSS are done in Java before
                 the job starts - laying every chart out again at the printable width,
                 and opening the table views - see __mdChartsForPrint.

                 The plate stays tinted rather than going white. It is a light surface
                 already, the palette was validated against it, and dropping it would put
                 the four lighter series hues on bare paper below the contrast they were
                 checked at. */

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
    }

    private String buildPreviewShell() {
        String css = previewCss();

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
              window.__mdRunCharts();
              window.__mdHighlight();
            };
            /* Charts compile synchronously into SVG, so unlike mermaid there is nothing
               to await and no failure to swallow quietly - a bad fence renders its own
               message in place, next to the source that caused it. */
            window.__mdRunCharts = function () {
              if (!window.MdChart) { return; }
              try { MdChart.renderAll(document); } catch (e) {}
            };

            /* Charts, made ready for paper.

               Two things are wrong at print time and neither is fixable in CSS. A chart
               is laid out in pixels for the pane it is in, and letting the page scale
               that to fit the paper takes the type down with it - the same bug that made
               11px labels render at 7px on screen, except on paper there is no zooming
               out of it. So every chart is drawn again at the printable width, which
               only Java knows, and drawn back afterwards.

               And the table view is a <details>, which prints exactly as it sits: closed.
               The table is the guarantee that no value is reachable only by hovering, and
               a printed page cannot hover at all, so on paper it is the only copy of the
               numbers. Opened here and closed again after, so the screen is as it was. */
            window.__mdChartsForPrint = function (width) {
              /* Which table views the reader had open, remembered before the redraw
                 rather than after: a redraw rebuilds every figure, so the details
                 elements standing afterwards are not the ones on screen now. */
              window.__mdcOpenTables = [];
              var before = document.querySelectorAll('.mdc-table');
              for (var i = 0; i < before.length; i++) {
                window.__mdcOpenTables.push(before[i].hasAttribute('open'));
              }
              var drawn = 0;
              if (window.MdChart) {
                try { drawn = MdChart.redrawAll(width); } catch (e) { drawn = 0; }
              }
              var tables = document.querySelectorAll('.mdc-table');
              for (var j = 0; j < tables.length; j++) {
                tables[j].setAttribute('open', '');
              }
              return drawn;
            };
            window.__mdChartsAfterPrint = function () {
              if (window.MdChart) {
                try { MdChart.redrawAll(0); } catch (e) {}
              }
              var was = window.__mdcOpenTables || [];
              var tables = document.querySelectorAll('.mdc-table');
              for (var i = 0; i < tables.length; i++) {
                if (was[i]) {
                  tables[i].setAttribute('open', '');
                } else {
                  tables[i].removeAttribute('open');
                }
              }
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

            /* The chart under the pointer, plus what its data could be drawn as.
               Captured on mousedown like the others, and the verdicts are asked for here
               rather than when the menu opens because the source is on this element and
               the menu is built on the other side of the bridge. */
            window.__mdChartBlock = '';
            document.addEventListener('mousedown', function (event) {
              var el = event.target;
              while (el && el !== document.body
                     && !(el.classList && el.classList.contains('mdv-chart-out'))) {
                el = el.parentElement;
              }
              if (el && el.classList && el.classList.contains('mdv-chart-out')
                  && el.getAttribute('data-md-start') && window.MdChart) {
                var source = el.getAttribute('data-mdc-src') || '';
                var verdicts = '';
                try { verdicts = MdChart.describe(source); } catch (e) { verdicts = ''; }
                window.__mdChartBlock = el.getAttribute('data-md-start') + ','
                    + el.getAttribute('data-md-end') + ',' + verdicts;
                /* Kept as well as its offsets, so "Edit chart data" can be handed the
                   source without going back to the document to find it again. */
                window.__mdChartElement = el;
              } else {
                window.__mdChartBlock = '';
                window.__mdChartElement = null;
              }
            });
            window.__mdChartInfo = function () { return window.__mdChartBlock; };

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
              /* A chart claims the double-click before anything else sees it, and opens a
                 form rather than its own source. Editing the fence as text worked and put
                 the reader one keystroke from a fence that no longer parses - a misplaced
                 pipe turns a chart into a paragraph, and the feedback for that is the
                 chart vanishing. A form cannot produce a fence that does not parse. */
              var chartHost = mdChartHost(event.target);
              if (chartHost) {
                if (window.mdvBridge) {
                  window.mdvBridge.editChart(
                      parseInt(chartHost.getAttribute('data-md-start'), 10),
                      parseInt(chartHost.getAttribute('data-md-end'), 10),
                      chartHost.getAttribute('data-mdc-src') || '');
                }
                return;
              }
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

            /** The compiled chart {@code node} sits inside, if it is inside one. */
            function mdChartHost(node) {
              var el = node && node.nodeType === 1 ? node : (node ? node.parentElement : null);
              while (el && el !== document.body) {
                if (el.classList && el.classList.contains('mdv-chart-out')
                    && el.getAttribute('data-md-start')) {
                  return el;
                }
                el = el.parentElement;
              }
              return null;
            }

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

              /* A chart is looked for first, and from anywhere inside it.

                 It has to come first because a chart contains a table - its table view -
                 and the rule below stops at the first table it meets, which would make
                 double-clicking the numbers underneath a chart do nothing. That table is
                 generated from the fence and is not in the document at all; the fence is,
                 and the fence is what opens.

                 An SVG has nothing to put a caret in, so unlike a paragraph there is no
                 in-place alternative: showing the source is the only way to edit a chart
                 from the preview, which is exactly what a code block already does. */
              /* A chart is never edited as a block: it opens a form of its own, handled
                 before this is ever reached. Refused here as well so that no other path
                 into the block editor can put a fence in a textarea by accident. */
              if (mdChartHost(el)) { return null; }

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
        diagramUpload.shutdown();
    }
}
