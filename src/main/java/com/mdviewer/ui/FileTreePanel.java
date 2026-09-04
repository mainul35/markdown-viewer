package com.mdviewer.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The workspace explorer: a lazy file tree over one or more workspace roots, with a
 * "reveal current file" target button in its header.
 */
public final class FileTreePanel extends VBox {

    private final TreeView<Path> treeView = new TreeView<>();
    private final TreeItem<Path> hiddenRoot = new TreeItem<>(null);
    private final Button revealButton = new Button();
    private final ContextMenu contextMenu = new ContextMenu();
    private java.util.function.Consumer<Path> onSyncRequested;

    private Consumer<Path> onFileActivated = p -> { };

    /**
     * Whether one tap opens a file, rather than two.
     *
     * <p>A double click is the desktop convention and it does not survive a finger. Two
     * taps only count as one double click if they land within a few pixels of each other
     * and inside the system's interval; a fingertip drifts on both counts, so the second
     * tap is read as another first tap and nothing opens. Pressing harder does not help,
     * which is what makes it feel like a hardware fault rather than a gesture that cannot
     * be performed.
     */
    private boolean openOnSingleClick;
    private FileActions fileActions;
    private Runnable onRefreshRequested;

    /**
     * File-system operations the explorer offers. The panel decides what to show for the
     * clicked row; the controller carries them out, because it owns the dialogs and knows
     * which documents are open.
     */
    public interface FileActions {
        void createFile(Path parentDirectory);

        void createFolder(Path parentDirectory);

        void rename(Path target);

        void delete(Path target);
    }

    public FileTreePanel() {
        getStyleClass().add("file-panel");

        Label title = new Label("WORKSPACES");
        title.getStyleClass().add("file-panel-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        revealButton.setGraphic(buildTargetIcon());
        revealButton.getStyleClass().add("reveal-button");
        revealButton.setTooltip(new Tooltip("Reveal the current file in the tree"));
        revealButton.setFocusTraversable(false);

        HBox header = new HBox(title, spacer, revealButton);
        header.getStyleClass().add("file-panel-header");
        header.setAlignment(Pos.CENTER_LEFT);

        treeView.setRoot(hiddenRoot);
        treeView.setShowRoot(false);
        treeView.getStyleClass().add("file-tree");
        treeView.setCellFactory(tv -> new PathCell());
        VBox.setVgrow(treeView, Priority.ALWAYS);

        treeView.setOnContextMenuRequested(event -> {
            // Reached only when the click missed every cell, since cells consume their own.
            showContextMenu(null, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        treeView.setOnMouseClicked(event -> {
            if (event.getClickCount() >= (openOnSingleClick ? 1 : 2)) {
                TreeItem<Path> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected instanceof PathTreeItem item && !item.isDirectory()) {
                    onFileActivated.accept(item.getValue());
                }
            }
        });

        getChildren().addAll(header, treeView);
        setMinWidth(0);
    }

    /**
     * One tap opens a file instead of two. See {@link #openOnSingleClick}.
     *
     * <p>Driven by touch mode rather than by display size: this is about how the machine is
     * being pointed at, not how much room it has.
     */
    public void setOpenOnSingleClick(boolean openOnSingleClick) {
        this.openOnSingleClick = openOnSingleClick;
    }

    /**
     * Puts something along the bottom of the panel, under the tree.
     *
     * <p>The panel does not know or care what it is. The account bar is the one thing that
     * goes here today, and an explorer that had to know about sign-in state to lay itself
     * out would be an explorer that changes whenever the account does.
     */
    public void setFooter(javafx.scene.Node footer) {
        getChildren().removeIf(child -> Boolean.TRUE.equals(child.getProperties().get("footer")));
        if (footer != null) {
            footer.getProperties().put("footer", Boolean.TRUE);
            getChildren().add(footer);
        }
    }

    /**
     * A crosshair drawn from shapes rather than an icon font or emoji: JavaFX's text stack
     * renders symbol glyphs inconsistently across platforms, and shapes also pick up the
     * theme colour straight from CSS.
     */
    private static Group buildTargetIcon() {
        Circle ring = new Circle(6);
        ring.getStyleClass().add("reveal-icon-ring");
        Circle dot = new Circle(1.6);
        dot.getStyleClass().add("reveal-icon-dot");

        Group group = new Group(ring, dot,
                tick(0, -9, 0, -5), tick(0, 5, 0, 9),
                tick(-9, 0, -5, 0), tick(5, 0, 9, 0));
        group.getStyleClass().add("reveal-icon");
        return group;
    }

    private static Line tick(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("reveal-icon-tick");
        return line;
    }

    public TreeView<Path> getTreeView() {
        return treeView;
    }

    public Button getRevealButton() {
        return revealButton;
    }

    public void setOnFileActivated(Consumer<Path> handler) {
        this.onFileActivated = handler == null ? p -> { } : handler;
    }

    public void setFileActions(FileActions fileActions) {
        this.fileActions = fileActions;
    }

    /**
     * Opens the menu for one row, built for that row.
     *
     * <p>Shown from the cell rather than handed to {@code TreeView.setContextMenu}: JavaFX
     * skips showing a control's context menu while it has no items, so a menu that is
     * filled in on the way up never appears at all. Doing it here also fixes the target -
     * a right click does not move the selection, so a menu built from the selected row
     * would act on whatever was clicked previously.
     */
    void showContextMenu(TreeItem<Path> row, double screenX, double screenY) {
        contextMenu.hide();
        contextMenu.getItems().clear();
        if (fileActions == null) {
            return;
        }

        Path target = row == null ? null : row.getValue();
        boolean isDirectory = row instanceof PathTreeItem item && item.isDirectory();
        boolean isWorkspaceRoot = row != null && row.getParent() == hiddenRoot;
        // A file contributes its folder, so creating a sibling works without first
        // pointing at the folder.
        Path parent = target == null ? firstRoot() : (isDirectory ? target : target.getParent());

        if (parent != null) {
            contextMenu.getItems().addAll(
                    item("New file...", () -> fileActions.createFile(parent)),
                    item("New folder...", () -> fileActions.createFolder(parent)));
        }
        if (target != null) {
            separate();
            if (!isDirectory) {
                contextMenu.getItems().add(item("Open", () -> onFileActivated.accept(target)));
            }
            // A workspace root is the workspace itself; renaming or binning it from here
            // would leave the open workspace pointing at nothing. Close it from the File
            // menu instead.
            if (!isWorkspaceRoot) {
                contextMenu.getItems().addAll(
                        item("Rename...", () -> fileActions.rename(target)),
                        item("Delete", () -> fileActions.delete(target)));
            }
        }
        if (target != null && onSyncRequested != null) {
            separate();
            /* One label for both. Which of the two happens is decided by what was clicked,
               and the row under the pointer already says which that is - naming it again in
               the menu would be the same sentence twice. */
            contextMenu.getItems().add(
                    item(isDirectory ? "Sync Folder to Cloud" : "Sync to Cloud",
                            () -> onSyncRequested.accept(target)));
        }
        if (onRefreshRequested != null) {
            separate();
            // Offered on the empty-space menu too, which is the one a user reaches for
            // when the tree looks wrong and there is no particular row to blame.
            contextMenu.getItems().add(item("Refresh workspaces", onRefreshRequested));
        }
        if (!contextMenu.getItems().isEmpty()) {
            contextMenu.show(this, screenX, screenY);
        }
    }

    public void setOnRefreshRequested(Runnable handler) {
        this.onRefreshRequested = handler;
    }

    /**
     * What to do when somebody asks for a file or folder to go to the cloud.
     *
     * <p>Offered from the tree because that is where the decision is made. Syncing a whole
     * workspace is a commitment to everything in it; pointing at one folder is how somebody
     * says "this part, not my whole notes directory".
     */
    public void setOnSyncRequested(java.util.function.Consumer<Path> handler) {
        this.onSyncRequested = handler;
    }

    /**
     * Starts a new group in the menu, if there is anything to separate it from.
     *
     * <p>Not just "is the menu empty": a group can turn out to have no items at all - a
     * workspace root offers neither Rename nor Delete - and the separator that opened it
     * would then be left with nothing behind it, so the next group's separator drew a
     * second rule immediately after the first.
     */
    private void separate() {
        List<MenuItem> items = contextMenu.getItems();
        if (items.isEmpty() || items.get(items.size() - 1) instanceof SeparatorMenuItem) {
            return;
        }
        items.add(new SeparatorMenuItem());
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem menuItem = new MenuItem(label);
        menuItem.setOnAction(e -> action.run());
        return menuItem;
    }

    private Path firstRoot() {
        return hiddenRoot.getChildren().isEmpty() ? null
                : hiddenRoot.getChildren().get(0).getValue();
    }

    /**
     * Re-reads a directory after it changes on disk. The tree caches listings, so a file
     * created or removed here would otherwise not appear until the folder was collapsed
     * and expanded again.
     */
    public void refresh(Path directory) {
        if (directory == null) {
            return;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            PathTreeItem found = findItem(rootItem, normalized);
            if (found != null) {
                boolean wasExpanded = found.isExpanded();
                found.invalidate();
                found.setExpanded(wasExpanded);
                // Touching children forces the re-read while the node stays expanded.
                found.getChildren();
                return;
            }
        }
    }

    /**
     * The directories whose listings are cached and could therefore be out of date.
     *
     * <p>Handed to a background thread, which reads each one with
     * {@link PathTreeItem#readEntries} and passes the result back to
     * {@link #applyListings}. Split in two because that read is far too slow for the FX
     * thread - a single folder can cost thousands of directory entries - while the merge
     * touches live scene-graph nodes and so can only happen on it.
     */
    public List<Path> loadedDirectories() {
        List<Path> directories = new ArrayList<>();
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            if (rootItem instanceof PathTreeItem item) {
                item.collectLoadedDirectories(directories);
            }
        }
        return directories;
    }

    /**
     * Merges freshly read listings into the tree, keeping the user's place.
     *
     * <p>Expansion state rides on the {@code TreeItem}s themselves, which the merge
     * preserves; selection is by item and does not survive a {@code setAll}, so it is
     * captured by path and restored afterwards.
     *
     * @return true if any file or folder appeared or disappeared
     */
    public boolean applyListings(Map<Path, List<Path>> listings) {
        TreeItem<Path> selected = treeView.getSelectionModel().getSelectedItem();
        Path selectedPath = selected == null ? null : selected.getValue();

        boolean changed = false;
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            if (rootItem instanceof PathTreeItem item && item.applyListings(listings)) {
                changed = true;
            }
        }

        if (changed && selectedPath != null) {
            for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
                PathTreeItem again = findItem(rootItem, selectedPath);
                if (again != null) {
                    treeView.getSelectionModel().select(again);
                    break;
                }
            }
        }
        return changed;
    }

    private PathTreeItem findItem(TreeItem<Path> node, Path target) {
        if (!(node instanceof PathTreeItem item)) {
            return null;
        }
        // Equality before the directory test: callers look for files too, and a file that
        // is its own match must not be rejected for having no children to descend into.
        if (item.getValue().equals(target)) {
            return item;
        }
        if (!item.isDirectory() || !target.startsWith(item.getValue())) {
            return null;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            PathTreeItem found = findItem(child, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public void setOnReveal(Runnable handler) {
        revealButton.setOnAction(e -> handler.run());
    }

    // ------------------------------------------------------------- workspaces

    public void addWorkspaceRoot(Path root) {
        if (root == null || findRootItem(root) != null) {
            return;
        }
        PathTreeItem item = new PathTreeItem(root);
        item.setExpanded(true);
        hiddenRoot.getChildren().add(item);
    }

    public void removeWorkspaceRoot(Path root) {
        TreeItem<Path> item = findRootItem(root);
        if (item != null) {
            hiddenRoot.getChildren().remove(item);
        }
    }

    public TreeItem<Path> getHiddenRoot() {
        return hiddenRoot;
    }

    private TreeItem<Path> findRootItem(Path root) {
        for (TreeItem<Path> child : hiddenRoot.getChildren()) {
            if (child.getValue().equals(root)) {
                return child;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- reveal

    /**
     * Expands the tree down to {@code file}, then selects and scrolls to it. Expanding a
     * node is what loads its children, so this walks the path one segment at a time.
     *
     * @return true if the file was found and selected
     */
    public boolean reveal(Path file) {
        if (file == null) {
            return false;
        }
        Path target = file.toAbsolutePath().normalize();

        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            Path root = rootItem.getValue();
            if (!target.startsWith(root)) {
                continue;
            }
            TreeItem<Path> current = rootItem;
            current.setExpanded(true);

            for (Path segment : root.relativize(target)) {
                if (!(current instanceof PathTreeItem item)) {
                    break;
                }
                // ensureChild rather than a plain lookup: the path may run through a folder
                // the listing filter hides, and a file the user opened should still be shown.
                TreeItem<Path> next = item.ensureChild(current.getValue().resolve(segment));
                if (next == null) {
                    break; // Segment does not exist on disk.
                }
                current = next;
                if (!current.getValue().equals(target)) {
                    current.setExpanded(true);
                }
            }

            if (!current.getValue().equals(target)) {
                return false;
            }
            treeView.getSelectionModel().select(current);
            int row = treeView.getRow(current);
            if (row >= 0) {
                treeView.scrollTo(row);
            }
            return true;
        }
        return false;
    }

    /** Renders the file name; workspace roots are marked so CSS can emphasise them. */
    private final class PathCell extends TreeCell<Path> {

        PathCell() {
            setOnContextMenuRequested(event -> {
                TreeItem<Path> row = isEmpty() ? null : getTreeItem();
                if (row != null) {
                    // Right-clicking does not move the selection by itself, and the menu
                    // should act on the row under the pointer.
                    treeView.getSelectionModel().select(row);
                }
                showContextMenu(row, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("workspace-root-cell");
            if (empty || item == null) {
                setText(null);
                return;
            }
            Path name = item.getFileName();
            setText(name == null ? item.toString() : name.toString());
            if (getTreeItem() != null && getTreeItem().getParent() == hiddenRoot) {
                getStyleClass().add("workspace-root-cell");
            }
        }
    }
}
