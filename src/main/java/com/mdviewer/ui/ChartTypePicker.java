package com.mdviewer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;

import java.util.function.Consumer;

/**
 * Picks the form a chart should take, and says what each form is for.
 *
 * <p>A list rather than a grid, because unlike a table size these choices are not
 * interchangeable: a pie and a line chart answer different questions, and picking between
 * them is a decision rather than a measurement. Each row carries the sentence that makes
 * the decision - "parts of a whole", "change over time" - so the choice can be made
 * without knowing the vocabulary first.
 *
 * <p>The glyphs are drawn from shapes for the same reason the toolbar's are: JavaFX
 * renders symbol glyphs inconsistently across platforms, and shapes take their colour from
 * CSS, so these follow the theme.
 */
public final class ChartTypePicker {

    /** One offered form: the fence's {@code type:} value, its name, and what it is for. */
    private record Form(String type, String name, String purpose) { }

    private static final Form[] FORMS = {
            new Form("bar", "Bar", "Compare named things - long labels stay readable"),
            new Form("column", "Column", "Compare across a few steps or dates"),
            new Form("line", "Line", "Change over time"),
            new Form("area", "Area", "Change over time, with the total emphasised"),
            new Form("pie", "Pie", "Parts of a whole, up to six"),
            new Form("donut", "Donut", "Parts of a whole, with room for a total"),
            new Form("stat", "Stat", "One number that matters, with its change"),
    };

    private final Popup popup = new Popup();
    private Consumer<String> onPick = t -> { };

    public ChartTypePicker() {
        VBox content = new VBox(2);
        content.getStyleClass().add("chart-picker");
        content.setPadding(new Insets(8));

        Label heading = new Label("Insert chart");
        heading.getStyleClass().add("chart-picker-heading");
        content.getChildren().add(heading);

        for (Form form : FORMS) {
            content.getChildren().add(row(form));
        }

        content.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            }
        });

        popup.getContent().add(content);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    public void setOnPick(Consumer<String> onPick) {
        this.onPick = onPick == null ? t -> { } : onPick;
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
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 2);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ---------------------------------------------------------------- internals

    private HBox row(Form form) {
        Label name = new Label(form.name());
        name.getStyleClass().add("chart-picker-name");
        name.setMinWidth(58);

        Label purpose = new Label(form.purpose());
        purpose.getStyleClass().add("chart-picker-purpose");

        HBox row = new HBox(10, glyphFor(form.type()), name, purpose);
        row.getStyleClass().add("chart-picker-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 8, 5, 8));
        row.setOnMouseEntered(e -> row.pseudoClassStateChanged(ON, true));
        row.setOnMouseExited(e -> row.pseudoClassStateChanged(ON, false));
        row.setOnMouseClicked(e -> {
            popup.hide();
            onPick.accept(form.type());
        });
        return row;
    }

    // ------------------------------------------------------------------ glyphs

    /** A 22 x 16 sketch of the form, drawn the way the chart itself would draw it. */
    private static Node glyphFor(String type) {
        return switch (type) {
            case "bar" -> icon(bar(0, 1, 16), bar(0, 6, 21), bar(0, 11, 9));
            case "column" -> icon(column(1, 6), column(7, 13), column(13, 9), column(19, 16));
            case "line" -> icon(stroke(0, 12, 6, 7), stroke(6, 7, 13, 10), stroke(13, 10, 21, 2),
                    dot(6, 7), dot(13, 10));
            case "area" -> icon(fill(new Polygon(0, 16, 0, 12, 7, 6, 14, 9, 21, 2, 21, 16)),
                    stroke(0, 12, 7, 6), stroke(7, 6, 14, 9), stroke(14, 9, 21, 2));
            case "pie" -> icon(slice(0, 250), slice(250, 110));
            case "donut" -> icon(ring(0, 250), ring(250, 110));
            case "stat" -> icon(bigMark(2, 1, 12, 8), bigMark(2, 12, 7, 3));
            default -> icon(bar(0, 6, 16));
        };
    }

    private static Group icon(Node... parts) {
        Group group = new Group(parts);
        group.getStyleClass().add("chart-picker-glyph");
        return group;
    }

    private static Rectangle bar(double x, double y, double width) {
        Rectangle rect = new Rectangle(x, y, width, 3.4);
        rect.setArcWidth(3);
        rect.setArcHeight(3);
        rect.getStyleClass().add("toolbar-icon-fill");
        return rect;
    }

    private static Rectangle column(double x, double height) {
        Rectangle rect = new Rectangle(x, 16 - height, 3.4, height);
        rect.setArcWidth(3);
        rect.setArcHeight(3);
        rect.getStyleClass().add("toolbar-icon-fill");
        return rect;
    }

    private static Rectangle bigMark(double x, double y, double width, double height) {
        Rectangle rect = new Rectangle(x, y, width, height);
        rect.setArcWidth(2);
        rect.setArcHeight(2);
        rect.getStyleClass().add("toolbar-icon-fill");
        return rect;
    }

    private static Line stroke(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("toolbar-icon-stroke");
        return line;
    }

    private static Circle dot(double x, double y) {
        Circle circle = new Circle(x, y, 1.8);
        circle.getStyleClass().add("toolbar-icon-fill");
        return circle;
    }

    private static Node fill(Polygon polygon) {
        polygon.getStyleClass().add("chart-picker-wash");
        return polygon;
    }

    private static Arc slice(double from, double length) {
        Arc arc = new Arc(9, 8, 8, 8, from, length);
        arc.setType(ArcType.ROUND);
        arc.getStyleClass().add(from == 0 ? "toolbar-icon-fill" : "chart-picker-wash");
        return arc;
    }

    /** The same two arcs as a pie, hollowed out - a stroked arc rather than a filled one. */
    private static Arc ring(double from, double length) {
        Arc arc = new Arc(9, 8, 6, 6, from, length);
        arc.setType(ArcType.OPEN);
        arc.setFill(Color.TRANSPARENT);
        arc.setStrokeWidth(4);
        arc.getStyleClass().add(from == 0 ? "chart-picker-arc" : "chart-picker-arc-soft");
        return arc;
    }

    private static final javafx.css.PseudoClass ON =
            javafx.css.PseudoClass.getPseudoClass("on");
}
