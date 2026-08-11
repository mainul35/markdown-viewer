package com.mdviewer.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;

import java.nio.file.Path;

/**
 * One open document: its editor, its file tab, and whether it has unsaved edits.
 *
 * <p>Each document owns a {@link TextArea} rather than sharing one, so undo history and
 * caret position stay with the document when you switch tabs.
 */
public final class DocumentView {

    private final TextArea editor = new TextArea();
    private final Tab tab = new Tab();

    private Path path;
    private final String untitledName;
    private boolean modified;

    public DocumentView(Path path, String untitledName) {
        this.path = path;
        this.untitledName = untitledName;
        editor.setPromptText("Write your Markdown here...");
        editor.setWrapText(false);
        editor.getStyleClass().add("markdown-editor");
        tab.setClosable(true);
        tab.setUserData(this);
        updateTabLabel();
    }

    public TextArea getEditor() {
        return editor;
    }

    public Tab getTab() {
        return tab;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
        updateTabLabel();
    }

    /** Directory used to resolve relative image paths; null for a document never saved. */
    public Path getBaseDir() {
        return path == null ? null : path.toAbsolutePath().getParent();
    }

    public String getDisplayName() {
        if (path == null) {
            return untitledName;
        }
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        if (this.modified != modified) {
            this.modified = modified;
            updateTabLabel();
        }
    }

    private void updateTabLabel() {
        tab.setText(getDisplayName() + (modified ? " *" : ""));
        tab.setTooltip(new javafx.scene.control.Tooltip(
                path == null ? getDisplayName() : path.toString()));
    }
}
