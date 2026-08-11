package com.mdviewer.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.util.function.Consumer;

/**
 * The workspace explorer: a lazy file tree over one or more workspace roots, with a
 * "reveal current file" target button in its header.
 */
public final class FileTreePanel extends VBox {

    private final TreeView<Path> treeView = new TreeView<>();
    private final TreeItem<Path> hiddenRoot = new TreeItem<>(null);
    private final Button revealButton = new Button();

    private Consumer<Path> onFileActivated = p -> { };

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

        treeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
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
