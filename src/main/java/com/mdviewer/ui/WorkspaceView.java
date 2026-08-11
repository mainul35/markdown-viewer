package com.mdviewer.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A workspace: a root directory plus the documents opened from it.
 *
 * <p>The workspace owns a {@link TabPane} of file tabs, which becomes the content of the
 * workspace's own tab. That nesting is what keeps many open files legible - documents are
 * grouped under the workspace they came from instead of forming one long tab strip.
 */
public final class WorkspaceView {

    private final Path root;
    private final Tab tab = new Tab();
    private final TabPane documentTabs = new TabPane();
    private final List<DocumentView> documents = new ArrayList<>();

    /** @param root workspace directory, or null for the scratch workspace holding unsaved files */
    public WorkspaceView(Path root) {
        this.root = root;
        tab.setContent(documentTabs);
        tab.setUserData(this);
        tab.setText(getDisplayName());
        tab.setTooltip(new javafx.scene.control.Tooltip(
                root == null ? "Documents not yet saved to a folder" : root.toString()));
        documentTabs.getStyleClass().add("document-tabs");
        documentTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    }

    public Path getRoot() {
        return root;
    }

    public Tab getTab() {
        return tab;
    }

    public TabPane getDocumentTabs() {
        return documentTabs;
    }

    public List<DocumentView> getDocuments() {
        return documents;
    }

    public String getDisplayName() {
        if (root == null) {
            return "Unsaved";
        }
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    /** True if {@code file} lives inside this workspace. */
    public boolean contains(Path file) {
        return root != null && file != null && file.toAbsolutePath().normalize().startsWith(root);
    }

    public DocumentView findDocument(Path file) {
        if (file == null) {
            return null;
        }
        Path target = file.toAbsolutePath().normalize();
        for (DocumentView doc : documents) {
            if (doc.getPath() != null && doc.getPath().equals(target)) {
                return doc;
            }
        }
        return null;
    }

    public void add(DocumentView document) {
        documents.add(document);
        documentTabs.getTabs().add(document.getTab());
    }

    public void remove(DocumentView document) {
        documents.remove(document);
        documentTabs.getTabs().remove(document.getTab());
    }
}
