package com.mdviewer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.function.BiConsumer;

/**
 * Picks a table size: drag across the grid for the common case, or type exact numbers for
 * a table bigger than the grid offers.
 *
 * <p>Both, rather than one or the other. A grid alone caps you at its own size, and
 * spinners alone make the ordinary 3x3 a four-step chore. The grid is the fast path and
 * the spinners are the escape hatch, which is the split every editor that does this well
 * has landed on.
 *
 * <p>Sizes count the header row, so 3 x 4 means a header plus two body rows.
 */
public final class TableSizePicker {

    /** As far as the hover grid goes; larger tables go through the spinners. */
    private static final int GRID_ROWS = 8;
    private static final int GRID_COLUMNS = 10;
    private static final int CELL = 15;
    private static final int GAP = 3;

    /** A one-column table is a list and a one-row table has no body; both start at 2/1. */
    private static final int MIN_ROWS = 2;
    private static final int MIN_COLUMNS = 1;
    private static final int MAX_ROWS = 100;
    private static final int MAX_COLUMNS = 30;

    private final Popup popup = new Popup();
    private final Label readout = new Label();
    private final Region[][] cells = new Region[GRID_ROWS][GRID_COLUMNS];
    private final Spinner<Integer> rowSpinner = new Spinner<>(MIN_ROWS, MAX_ROWS, 3);
    private final Spinner<Integer> columnSpinner = new Spinner<>(MIN_COLUMNS, MAX_COLUMNS, 3);

    private BiConsumer<Integer, Integer> onPick = (r, c) -> { };
    private int hoverRows = 0;
    private int hoverColumns = 0;

    public TableSizePicker() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("table-picker-grid");
        grid.setHgap(GAP);
        grid.setVgap(GAP);

        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLUMNS; c++) {
                Region cell = new Region();
                cell.getStyleClass().add("table-picker-cell");
                cell.setPrefSize(CELL, CELL);
                cell.setMinSize(CELL, CELL);
                final int rows = r + 1;
                final int columns = c + 1;
                // Hover rather than press-drag: a popup that needs a drag to work is a
                // popup people click once and give up on.
                cell.setOnMouseEntered(e -> highlight(rows, columns));
                cell.setOnMouseClicked(e -> commit(rows, columns));
                cells[r][c] = cell;
                grid.add(cell, c, r);
            }
        }
        // Leaving the grid entirely should not strand a highlight that no longer tracks
        // the pointer.
        grid.setOnMouseExited(e -> highlight(hoverRows, hoverColumns));

        readout.getStyleClass().add("table-picker-readout");

        rowSpinner.setEditable(true);
        columnSpinner.setEditable(true);
        rowSpinner.setPrefWidth(72);
        columnSpinner.setPrefWidth(72);
        rowSpinner.valueProperty().addListener((o, a, b) -> syncFromSpinners());
        columnSpinner.valueProperty().addListener((o, a, b) -> syncFromSpinners());

        Button insert = new Button("Insert");
        insert.setDefaultButton(true);
        insert.setOnAction(e -> commit(rowSpinner.getValue(), columnSpinner.getValue()));

        HBox exact = new HBox(8, new Label("Rows"), rowSpinner,
                new Label("Columns"), columnSpinner, insert);
        exact.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, readout, grid, new Separator(), exact);
        content.getStyleClass().add("table-picker");
        content.setPadding(new Insets(12));

        content.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            }
        });

        popup.getContent().add(content);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        highlight(3, 3);
    }

    public void setOnPick(BiConsumer<Integer, Integer> onPick) {
        this.onPick = onPick == null ? (r, c) -> { } : onPick;
    }

    /** Opens under {@code anchor}, aligned to its left edge. */
    public void showUnder(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        PopupTheme.matchTo(popup, anchor);
        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        syncFromSpinners();
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 2);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ---------------------------------------------------------------- internals

    /**
     * True while {@link #highlight} is pushing values into the spinners.
     *
     * <p>Without it the two directions fight. Setting the row spinner fires its listener,
     * which calls back into highlight() with the row it just set and the column spinner's
     * *old* value - overwriting hoverColumns before the outer call has updated the column
     * spinner, which then sees the stale value already in place and skips its own update.
     * The result was a grid that tracked rows and ignored columns: hovering 4 x 6 lit 24
     * cells but read "4 x 3".
     */
    private boolean syncing;

    private void syncFromSpinners() {
        if (syncing) {
            return;
        }
        Integer rows = rowSpinner.getValue();
        Integer columns = columnSpinner.getValue();
        if (rows != null && columns != null) {
            highlight(rows, columns);
        }
    }

    private void highlight(int rows, int columns) {
        hoverRows = Math.max(MIN_ROWS, rows);
        hoverColumns = Math.max(MIN_COLUMNS, columns);
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLUMNS; c++) {
                boolean on = r < hoverRows && c < hoverColumns;
                cells[r][c].pseudoClassStateChanged(ON, on);
            }
        }
        readout.setText(hoverRows + " x " + hoverColumns + "  -  header + "
                + (hoverRows - 1) + (hoverRows == 2 ? " row" : " rows"));

        syncing = true;
        try {
            if (hoverRows <= MAX_ROWS && !rowSpinner.getValue().equals(hoverRows)) {
                rowSpinner.getValueFactory().setValue(hoverRows);
            }
            if (hoverColumns <= MAX_COLUMNS && !columnSpinner.getValue().equals(hoverColumns)) {
                columnSpinner.getValueFactory().setValue(hoverColumns);
            }
        } finally {
            syncing = false;
        }
    }

    private void commit(int rows, int columns) {
        popup.hide();
        onPick.accept(Math.max(MIN_ROWS, rows), Math.max(MIN_COLUMNS, columns));
    }

    private static final javafx.css.PseudoClass ON =
            javafx.css.PseudoClass.getPseudoClass("on");
}
