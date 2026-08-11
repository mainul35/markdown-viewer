package com.mdviewer.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Find and replace over the raw Markdown editor.
 *
 * <p>Operates on whichever {@link TextArea} is currently attached, so it follows the active
 * document rather than owning one. Matching is plain text, not regex: Markdown is full of
 * characters that are regex metacharacters, and typing a literal bracket or asterisk into a
 * search box should find that character.
 */
public final class FindBar extends VBox {

    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final Label matchLabel = new Label();
    private final ToggleButton matchCase = new ToggleButton("Aa");
    private final HBox replaceRow;

    private TextArea target;
    private Runnable onClose = () -> { };

    public FindBar() {
        getStyleClass().add("find-bar");
        setVisible(false);
        setManaged(false);

        findField.setPromptText("Find");
        findField.getStyleClass().add("find-field");
        HBox.setHgrow(findField, Priority.ALWAYS);
        findField.textProperty().addListener((o, a, b) -> {
            updateMatchCount();
            findFrom(0, true, false); // Re-search from the top as the query is typed.
        });
        findField.addEventFilter(KeyEvent.KEY_PRESSED, this::onFieldKey);

        replaceField.setPromptText("Replace with");
        replaceField.getStyleClass().add("find-field");
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        replaceField.addEventFilter(KeyEvent.KEY_PRESSED, this::onFieldKey);

        matchCase.setTooltip(new Tooltip("Match case"));
        matchCase.getStyleClass().add("find-toggle");
        matchCase.setOnAction(e -> {
            updateMatchCount();
            findFrom(0, true, false);
        });

        matchLabel.getStyleClass().add("find-count");
        matchLabel.setMinWidth(90);

        Button previous = iconButton("↑", "Previous match (Shift+Enter)");
        previous.setOnAction(e -> findNext(false));
        Button next = iconButton("↓", "Next match (Enter)");
        next.setOnAction(e -> findNext(true));
        Button close = iconButton("✕", "Close (Esc)");
        close.setOnAction(e -> hideBar());

        HBox findRow = new HBox(8, new Label("Find"), findField, matchCase,
                matchLabel, previous, next, close);
        findRow.setAlignment(Pos.CENTER_LEFT);
        findRow.getStyleClass().add("find-row");

        Button replaceOne = new Button("Replace");
        replaceOne.setOnAction(e -> replaceCurrent());
        Button replaceAll = new Button("Replace All");
        replaceAll.setOnAction(e -> replaceAll());
        Region spacer = new Region();
        spacer.setMinWidth(0);

        replaceRow = new HBox(8, new Label("Replace"), replaceField, replaceOne, replaceAll, spacer);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        replaceRow.getStyleClass().add("find-row");
        replaceRow.setVisible(false);
        replaceRow.setManaged(false);

        getChildren().addAll(findRow, replaceRow);
    }

    private Button iconButton(String glyph, String tip) {
        Button button = new Button(glyph);
        button.getStyleClass().add("find-icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        return button;
    }

    private void onFieldKey(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                findNext(!event.isShiftDown());
                event.consume();
            }
            case ESCAPE -> {
                hideBar();
                event.consume();
            }
            default -> { }
        }
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> { } : onClose;
    }

    /** Points the bar at the editor of the active document. */
    public void setTarget(TextArea target) {
        this.target = target;
        updateMatchCount();
    }

    public TextField getFindField() {
        return findField;
    }

    public boolean isReplaceShown() {
        return replaceRow.isVisible();
    }

    /**
     * Opens the bar, seeding the query from the editor's selection when there is one -
     * selecting a word and hitting Ctrl+F should search for that word.
     */
    public void show(boolean withReplace) {
        replaceRow.setVisible(withReplace);
        replaceRow.setManaged(withReplace);
        setVisible(true);
        setManaged(true);

        if (target != null) {
            String selection = target.getSelectedText();
            if (selection != null && !selection.isBlank() && !selection.contains("\n")) {
                findField.setText(selection);
            }
        }
        updateMatchCount();
        findField.requestFocus();
        findField.selectAll();
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        if (target != null) {
            target.requestFocus();
        }
        onClose.run();
    }

    // ----------------------------------------------------------------- search

    private String haystack() {
        return target == null ? "" : target.getText();
    }

    private String normalise(String s) {
        return matchCase.isSelected() ? s : s.toLowerCase(Locale.ROOT);
    }

    /** Moves to the next or previous match relative to the caret, wrapping around. */
    public void findNext(boolean forward) {
        String needle = findField.getText();
        if (target == null || needle == null || needle.isEmpty()) {
            return;
        }
        int from = forward
                ? Math.max(target.getSelection().getStart() + 1, 0)
                : Math.max(target.getSelection().getStart(), 0);
        findFrom(from, forward, true);
    }

    private void findFrom(int from, boolean forward, boolean wrap) {
        String needle = findField.getText();
        if (target == null || needle == null || needle.isEmpty()) {
            return;
        }
        String text = normalise(haystack());
        String query = normalise(needle);

        int index = forward
                ? text.indexOf(query, Math.min(from, text.length()))
                : text.lastIndexOf(query, Math.max(from - query.length() - 1, -1));

        if (index < 0 && wrap) {
            index = forward ? text.indexOf(query) : text.lastIndexOf(query);
        }
        if (index < 0) {
            updateMatchCount();
            return;
        }
        target.selectRange(index, index + needle.length());
        updateMatchCount();
    }

    private void updateMatchCount() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            matchLabel.setText("");
            return;
        }
        String text = normalise(haystack());
        String query = normalise(needle);

        int total = 0;
        int at = text.indexOf(query);
        int currentIndex = 0;
        int selectionStart = target == null ? -1 : target.getSelection().getStart();
        while (at >= 0) {
            total++;
            if (at == selectionStart) {
                currentIndex = total;
            }
            at = text.indexOf(query, at + query.length());
        }
        matchLabel.setText(total == 0 ? "No results"
                : (currentIndex > 0 ? currentIndex + " of " + total : total + " matches"));
    }

    // ---------------------------------------------------------------- replace

    /** Replaces the current match, then advances - the usual step-through behaviour. */
    public void replaceCurrent() {
        String needle = findField.getText();
        if (target == null || needle == null || needle.isEmpty()) {
            return;
        }
        String selected = target.getSelectedText();
        if (selected != null && normalise(selected).equals(normalise(needle))) {
            int start = target.getSelection().getStart();
            target.replaceText(start, start + selected.length(), replaceField.getText());
            target.selectRange(start + replaceField.getText().length(),
                    start + replaceField.getText().length());
        }
        findNext(true);
    }

    /** One edit rather than N, so a bulk replace is a single undo step. */
    public void replaceAll() {
        String needle = findField.getText();
        if (target == null || needle == null || needle.isEmpty()) {
            return;
        }
        String text = haystack();
        String hay = normalise(text);
        String query = normalise(needle);
        String replacement = replaceField.getText();

        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        int count = 0;
        int found = hay.indexOf(query);
        while (found >= 0) {
            out.append(text, at, found).append(replacement);
            at = found + query.length();
            count++;
            found = hay.indexOf(query, at);
        }
        if (count == 0) {
            matchLabel.setText("No results");
            return;
        }
        out.append(text.substring(at));

        int caret = target.getCaretPosition();
        target.replaceText(0, text.length(), out.toString());
        target.positionCaret(Math.min(caret, out.length()));
        matchLabel.setText("Replaced " + count);
    }

    /** Escape closes the bar from anywhere inside it. */
    public void installEscapeHandler() {
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hideBar();
                event.consume();
            }
        });
    }
}
