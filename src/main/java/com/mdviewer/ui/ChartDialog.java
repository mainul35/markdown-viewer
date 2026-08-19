package com.mdviewer.ui;

import com.mdviewer.service.ChartData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.shape.Line;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits a chart as a form: its settings, and a grid of numbers with buttons to add rows
 * and columns.
 *
 * <p>The alternative was editing the fence as text in the preview, which is what this
 * replaced. That worked, and it put the reader one keystroke away from a fence that no
 * longer parses - a misplaced pipe or a lost {@code ---} turns a chart into a paragraph,
 * and the feedback for it is the chart disappearing. A form cannot produce a fence that
 * does not parse, because it never writes one by hand.
 *
 * <p>The grid is the whole data model in one shape: rows have a label and one value per
 * column. How many columns there are decides what the chart means, and the dialog says so
 * rather than hiding it - one column is a list of items, several is a series per row
 * measured across the columns.
 */
public final class ChartDialog {

    /** Offered forms: the fence's {@code type:} value and the name a person would use. */
    private static final String[][] TYPES = {
            {"bar", "Bar"},
            {"column", "Column"},
            {"line", "Line"},
            {"area", "Area"},
            {"pie", "Pie"},
            {"donut", "Donut"},
            {"stat", "Stat"},
    };

    private final Stage dialog = new Stage();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final TextField titleField = new TextField();
    private final TextField unitField = new TextField();
    private final TextField deltaField = new TextField();
    private final Label deltaLabel = new Label("Change");
    private final GridPane grid = new GridPane();
    private final Label shapeNote = new Label();

    /** The live grid. Read out of the fields before every rebuild, and on apply. */
    private final List<String> headers = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<List<String>> values = new ArrayList<>();

    private final List<TextField> headerFields = new ArrayList<>();
    private final List<TextField> labelFields = new ArrayList<>();
    private final List<List<TextField>> valueFields = new ArrayList<>();

    private String result;

    private ChartDialog() {
    }

    /**
     * Opens the editor on {@code data}.
     *
     * @return the chart fence to put in the document, or null if it was cancelled
     */
    public static String edit(Stage owner, ChartData data) {
        ChartDialog editor = new ChartDialog();
        editor.load(data);
        editor.build(owner);
        editor.dialog.showAndWait();
        return editor.result;
    }

    // ------------------------------------------------------------------- state

    private void load(ChartData data) {
        headers.addAll(data.categories());
        for (ChartData.Row row : data.rows()) {
            labels.add(row.label());
            values.add(new ArrayList<>(row.values()));
        }
        if (labels.isEmpty()) {
            labels.add("");
            values.add(new ArrayList<>(List.of("")));
        }
        // A grid has to be rectangular to be shown as one, even when the fence was ragged.
        int columns = Math.max(1, Math.max(headers.size(), widestRow()));
        for (List<String> row : values) {
            while (row.size() < columns) {
                row.add("");
            }
        }
        while (headers.size() < columns) {
            headers.add(columns == 1 ? "" : String.valueOf(headers.size() + 1));
        }

        typeBox.setValue(displayName(data.type()));
        titleField.setText(data.title().trim());
        unitField.setText(data.unit().trim());
        deltaField.setText(data.delta().trim());
    }

    private int widestRow() {
        int widest = 0;
        for (List<String> row : values) {
            widest = Math.max(widest, row.size());
        }
        return widest;
    }

    /** Pulls what is on screen back into the model, so a rebuild never loses a keystroke. */
    private void harvest() {
        for (int c = 0; c < headerFields.size() && c < headers.size(); c++) {
            headers.set(c, headerFields.get(c).getText());
        }
        for (int r = 0; r < labelFields.size() && r < labels.size(); r++) {
            labels.set(r, labelFields.get(r).getText());
        }
        for (int r = 0; r < valueFields.size() && r < values.size(); r++) {
            List<TextField> fields = valueFields.get(r);
            for (int c = 0; c < fields.size() && c < values.get(r).size(); c++) {
                values.get(r).set(c, fields.get(c).getText());
            }
        }
    }

    // -------------------------------------------------------------------- view

    private void build(Stage owner) {
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Chart");

        for (String[] type : TYPES) {
            typeBox.getItems().add(type[1]);
        }
        if (typeBox.getValue() == null) {
            typeBox.setValue("Bar");
        }
        typeBox.valueProperty().addListener((o, was, now) -> syncDelta());
        typeBox.setPrefWidth(140);

        titleField.setPromptText("What the chart is about");
        unitField.setPromptText("req/s, ms, files");
        deltaField.setPromptText("+12% vs last week");

        GridPane settings = new GridPane();
        settings.setHgap(10);
        settings.setVgap(8);
        settings.add(new Label("Type"), 0, 0);
        settings.add(typeBox, 1, 0);
        settings.add(new Label("Title"), 2, 0);
        settings.add(titleField, 3, 0);
        settings.add(new Label("Unit"), 0, 1);
        settings.add(unitField, 1, 1);
        settings.add(deltaLabel, 2, 1);
        settings.add(deltaField, 3, 1);
        GridPane.setHgrow(titleField, Priority.ALWAYS);

        grid.setHgap(6);
        grid.setVgap(6);
        ScrollPane scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(260);
        scroller.getStyleClass().add("chart-dialog-grid");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        Button addRow = new Button("+ Row");
        addRow.setOnAction(e -> {
            harvest();
            labels.add("");
            List<String> row = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                row.add("");
            }
            values.add(row);
            rebuild();
        });
        Button addColumn = new Button("+ Column");
        addColumn.setOnAction(e -> {
            harvest();
            headers.add(String.valueOf(headers.size() + 1));
            for (List<String> row : values) {
                row.add("");
            }
            rebuild();
        });

        shapeNote.getStyleClass().add("chart-dialog-note");
        shapeNote.setWrapText(true);

        HBox adders = new HBox(8, addRow, addColumn);
        adders.setAlignment(Pos.CENTER_LEFT);

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());
        Button apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> commit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, apply);

        VBox content = new VBox(12, settings, new Separator(), adders, scroller,
                shapeNote, new Separator(), buttons);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("chart-dialog");

        syncDelta();
        rebuild();

        Scene scene = new Scene(content, 720, 520);
        // Light or dark, whichever the window behind it is in. A dialog is its own window
        // and inherits neither the stylesheet nor the theme class by itself.
        PopupTheme.matchTo(scene, owner == null ? null : owner.getScene().getRoot());
        dialog.setScene(scene);
    }

    /** Only a stat tile has a change line, so the field is only offered for one. */
    private void syncDelta() {
        boolean stat = "Stat".equals(typeBox.getValue());
        deltaLabel.setVisible(stat);
        deltaField.setVisible(stat);
        deltaField.setManaged(stat);
        deltaLabel.setManaged(stat);
    }

    private void rebuild() {
        grid.getChildren().clear();
        headerFields.clear();
        labelFields.clear();
        valueFields.clear();

        boolean multi = headers.size() > 1;

        Label corner = new Label(multi ? "Series" : "Item");
        corner.getStyleClass().add("chart-dialog-heading");
        grid.add(corner, 0, 0);

        for (int c = 0; c < headers.size(); c++) {
            final int column = c;
            if (multi) {
                TextField header = new TextField(headers.get(c));
                header.setPromptText("Column " + (c + 1));
                header.getStyleClass().add("chart-dialog-header");
                header.setPrefWidth(110);
                headerFields.add(header);
                Button drop = removeButton("Remove this column", () -> {
                    harvest();
                    headers.remove(column);
                    for (List<String> row : values) {
                        if (column < row.size()) {
                            row.remove(column);
                        }
                    }
                    rebuild();
                });
                grid.add(new HBox(2, header, drop), c + 1, 0);
            } else {
                // With one column there is no category to name: the row labels are the
                // categories, and inventing a heading here would suggest otherwise.
                Label header = new Label("Value");
                header.getStyleClass().add("chart-dialog-heading");
                headerFields.add(new TextField(""));
                grid.add(header, c + 1, 0);
            }
        }

        for (int r = 0; r < labels.size(); r++) {
            final int row = r;
            TextField label = new TextField(labels.get(r));
            label.setPromptText(multi ? "Series name" : "Item");
            label.setPrefWidth(150);
            labelFields.add(label);

            Button drop = removeButton("Remove this row", () -> {
                harvest();
                if (labels.size() > 1) {
                    labels.remove(row);
                    values.remove(row);
                    rebuild();
                }
            });
            drop.setDisable(labels.size() <= 1);
            grid.add(new HBox(2, label, drop), 0, r + 1);

            List<TextField> fields = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                String value = c < values.get(r).size() ? values.get(r).get(c) : "";
                TextField field = new TextField(value);
                field.setPrefWidth(110);
                field.getStyleClass().add("chart-dialog-value");
                // A number that is not a number is worth pointing at, and not worth
                // blocking on: a blank cell is a legitimate gap in a series.
                field.textProperty().addListener((o, was, now) ->
                        field.pseudoClassStateChanged(BAD, !isNumeric(now)));
                field.pseudoClassStateChanged(BAD, !isNumeric(value));
                fields.add(field);
                grid.add(field, c + 1, r + 1);
            }
            valueFields.add(fields);
        }

        shapeNote.setText(multi
                ? "Each row is a series measured across the columns. The column names "
                  + "become the axis."
                : "Each row is one item with one value. Add a column to measure every row "
                  + "across several points instead.");
    }

    /**
     * A cross drawn from two lines rather than the character ✕.
     *
     * <p>Same reason the toolbar's icons are shapes: JavaFX renders symbol glyphs
     * inconsistently across platforms, and this one came out as a pair of faint dashes.
     * Lines also take their colour from CSS, so the cross reddens on hover with the rest
     * of the button.
     */
    private Button removeButton(String tip, Runnable action) {
        Line down = new Line(0, 0, 7, 7);
        Line up = new Line(7, 0, 0, 7);
        down.getStyleClass().add("chart-dialog-cross");
        up.getStyleClass().add("chart-dialog-cross");

        Button button = new Button();
        button.setGraphic(new Group(down, up));
        button.getStyleClass().add("chart-dialog-remove");
        button.setTooltip(new javafx.scene.control.Tooltip(tip));
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isBlank()) {
            return true; // A gap in a series is a value too.
        }
        String cleaned = text.replace(",", "").replace("%", "").trim();
        try {
            Double.parseDouble(cleaned);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void commit() {
        harvest();
        List<ChartData.Row> rows = new ArrayList<>();
        for (int r = 0; r < labels.size(); r++) {
            // A row with no label and no numbers is a leftover blank; writing it out would
            // put an empty line in the fence for the renderer to skip.
            List<String> row = values.get(r);
            boolean empty = labels.get(r).isBlank();
            for (String value : row) {
                empty = empty && value.isBlank();
            }
            if (!empty) {
                rows.add(new ChartData.Row(labels.get(r), row));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new ChartData.Row(labels.get(0), values.get(0)));
        }
        result = ChartData.toFence(fenceType(), titleField.getText(), unitField.getText(),
                deltaField.getText(), headers, rows);
        dialog.close();
    }

    private String fenceType() {
        for (String[] type : TYPES) {
            if (type[1].equals(typeBox.getValue())) {
                return type[0];
            }
        }
        return "bar";
    }

    private static String displayName(String fenceType) {
        for (String[] type : TYPES) {
            if (type[0].equalsIgnoreCase(fenceType)) {
                return type[1];
            }
        }
        return null;
    }

    private static final javafx.css.PseudoClass BAD =
            javafx.css.PseudoClass.getPseudoClass("bad");
}
