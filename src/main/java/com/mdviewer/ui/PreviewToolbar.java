package com.mdviewer.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Formatting toolbar above the preview, so styling can be applied without knowing the
 * Markdown syntax for it.
 *
 * <p>Letterform buttons for bold, italic and strikethrough - a bold "B" is the convention
 * and reads faster than any icon. Everything else is drawn from shapes rather than an icon
 * font or emoji, for the same reason the explorer's crosshair is: JavaFX renders symbol
 * glyphs inconsistently across platforms, and shapes take their colour from CSS.
 */
public final class PreviewToolbar extends HBox {

    /** What the toolbar asks the controller to do; the controller owns the source edit. */
    public enum Action {
        BOLD, ITALIC, STRIKETHROUGH, CODE,
        HEADING_1, HEADING_2, HEADING_3,
        BULLET_LIST, ORDERED_LIST, QUOTE, LINK,
        IMAGE_INSERT,
        /** Alignment applies to the selected image, or to the selected text if there is none. */
        ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT,
        IMAGE_WIDTH_25, IMAGE_WIDTH_50, IMAGE_WIDTH_75, IMAGE_WIDTH_100,
        IMAGE_CAPTION, IMAGE_REPLACE, IMAGE_CROP, IMAGE_COPY_PATH, IMAGE_REMOVE
    }

    private final List<Node> imageControls = new ArrayList<>();
    private final Label imageHint = new Label("Select an image to position or resize it");
    private Consumer<Action> onAction = a -> { };

    public PreviewToolbar() {
        getStyleClass().add("preview-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(2);

        getChildren().addAll(
                letterButton("B", "Bold", "toolbar-bold", Action.BOLD),
                letterButton("I", "Italic", "toolbar-italic", Action.ITALIC),
                letterButton("S", "Strikethrough", "toolbar-strike", Action.STRIKETHROUGH),
                iconButton(codeIcon(), "Inline code", Action.CODE),
                divider(),
                letterButton("H1", "Heading 1", "toolbar-heading", Action.HEADING_1),
                letterButton("H2", "Heading 2", "toolbar-heading", Action.HEADING_2),
                letterButton("H3", "Heading 3", "toolbar-heading", Action.HEADING_3),
                divider(),
                iconButton(listIcon(false), "Bullet list", Action.BULLET_LIST),
                iconButton(listIcon(true), "Numbered list", Action.ORDERED_LIST),
                iconButton(quoteIcon(), "Block quote", Action.QUOTE),
                iconButton(linkIcon(), "Link", Action.LINK),
                divider(),
                iconButton(imageIcon(), "Insert image", Action.IMAGE_INSERT));

        // Alignment applies to whatever is selected - an image, or otherwise the text -
        // so unlike the width controls it is always available.
        getChildren().addAll(
                iconButton(alignIcon(Align.LEFT), "Align left", Action.ALIGN_LEFT),
                iconButton(alignIcon(Align.CENTER), "Centre", Action.ALIGN_CENTER),
                iconButton(alignIcon(Align.RIGHT), "Align right", Action.ALIGN_RIGHT));

        // Width only means something for an image, so those stay disabled until one is
        // selected rather than offering an action that would silently do nothing.
        addImageControl(widthButton("25%", Action.IMAGE_WIDTH_25));
        addImageControl(widthButton("50%", Action.IMAGE_WIDTH_50));
        addImageControl(widthButton("75%", Action.IMAGE_WIDTH_75));
        addImageControl(widthButton("100%", Action.IMAGE_WIDTH_100));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        imageHint.getStyleClass().add("toolbar-hint");
        imageHint.setMinWidth(0); // The hint is what truncates when space runs out, not the buttons.
        getChildren().addAll(spacer, imageHint);

        setImageSelected(false);
    }

    private void addImageControl(Button button) {
        imageControls.add(button);
        getChildren().add(button);
    }

    public void setOnAction(Consumer<Action> onAction) {
        this.onAction = onAction == null ? a -> { } : onAction;
    }

    /** Enables the positioning and sizing controls, which only apply to a chosen image. */
    public void setImageSelected(boolean selected) {
        for (Node control : imageControls) {
            control.setDisable(!selected);
        }
        imageHint.setText(selected
                ? "Image selected - size and position apply to it"
                : "Select an image to resize it; alignment applies to the selection");
    }

    // ----------------------------------------------------------------- pieces

    private Button letterButton(String text, String tip, String styleClass, Action action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("toolbar-button", styleClass);
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        // Without this the HBox shrinks labelled buttons below their text and they
        // collapse to an ellipsis, which is worse than no label at all.
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> onAction.accept(action));
        return button;
    }

    private Button widthButton(String text, Action action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("toolbar-button", "toolbar-width");
        button.setTooltip(new Tooltip("Set image width to " + text));
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> onAction.accept(action));
        return button;
    }

    private Button iconButton(Node icon, String tip, Action action) {
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().addAll("toolbar-button", "toolbar-icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> onAction.accept(action));
        return button;
    }

    private Separator divider() {
        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator.getStyleClass().add("toolbar-divider");
        return separator;
    }

    // ------------------------------------------------------------------ icons

    private static Line stroke(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("toolbar-icon-stroke");
        return line;
    }

    private static Group icon(Node... parts) {
        Group group = new Group(parts);
        group.getStyleClass().add("toolbar-icon");
        return group;
    }

    private static Group listIcon(boolean ordered) {
        List<Node> parts = new ArrayList<>(List.of(
                stroke(5, 1, 13, 1), stroke(5, 6, 13, 6), stroke(5, 11, 13, 11)));
        if (ordered) {
            parts.addAll(List.of(stroke(0, 2, 2, 2), stroke(1, 0, 1, 2),
                    stroke(0, 7, 2, 7), stroke(0, 12, 2, 12)));
        } else {
            for (double y : new double[] {1, 6, 11}) {
                Circle dot = new Circle(1, y, 1.4);
                dot.getStyleClass().add("toolbar-icon-fill");
                parts.add(dot);
            }
        }
        return icon(parts.toArray(new Node[0]));
    }

    private static Group quoteIcon() {
        return icon(stroke(1, 0, 1, 12), stroke(5, 2, 13, 2),
                stroke(5, 6, 13, 6), stroke(5, 10, 10, 10));
    }

    /** A diagonal bar with a bracket at each end - reads as a link at 14px. */
    private static Group linkIcon() {
        return icon(stroke(3.5, 9.5, 10, 3),
                stroke(8, 1.5, 12.5, 1.5), stroke(12.5, 1.5, 12.5, 6),
                stroke(1, 7, 1, 11.5), stroke(1, 11.5, 5.5, 11.5));
    }

    private static Group imageIcon() {
        Rectangle frame = new Rectangle(0, 1, 14, 11);
        frame.getStyleClass().add("toolbar-icon-stroke");
        frame.setFill(javafx.scene.paint.Color.TRANSPARENT);
        Circle sun = new Circle(4, 4.5, 1.3);
        sun.getStyleClass().add("toolbar-icon-fill");
        return icon(frame, sun, stroke(1, 11, 5.5, 7), stroke(5.5, 7, 8, 9.2),
                stroke(8, 9.2, 10.5, 6.5), stroke(10.5, 6.5, 13.5, 11));
    }

    private static Group codeIcon() {
        return icon(stroke(4, 1, 1, 6), stroke(1, 6, 4, 11),
                stroke(10, 1, 13, 6), stroke(13, 6, 10, 11));
    }

    private enum Align { LEFT, CENTER, RIGHT }

    private static Group alignIcon(Align align) {
        List<Node> parts = new ArrayList<>();
        parts.add(stroke(0, 0, 14, 0));
        parts.add(stroke(0, 12, 14, 12));
        Rectangle block = switch (align) {
            case LEFT -> new Rectangle(0, 3, 7, 6);
            case CENTER -> new Rectangle(3.5, 3, 7, 6);
            case RIGHT -> new Rectangle(7, 3, 7, 6);
        };
        block.getStyleClass().add("toolbar-icon-fill");
        parts.add(block);
        return icon(parts.toArray(new Node[0]));
    }
}
