package com.mdviewer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the window shows before anything is open.
 *
 * <p>An editor with no document was an empty grey rectangle, a menu bar, and no indication
 * of what to do next. This is the same three things the File menu offers - a new document,
 * an existing one, a folder - said in the place someone is already looking, with the
 * folders they had open last underneath.
 *
 * <p>Deliberately plain. It is the first thing seen and the least often seen; anything
 * decorative here would be paid for on every launch and looked at for two seconds.
 */
public final class WelcomeView extends VBox {

    /** Rebuilt rather than updated: it is shown once and the list behind it is short. */
    private final VBox recentList = new VBox(2);
    /** The heading and list together, so an empty history can hide both. */
    private final VBox recentSection;
    private final Consumer<Path> onOpenRecent;

    public WelcomeView(String version,
                       Runnable onNew,
                       Runnable onOpenFile,
                       Runnable onOpenFolder,
                       Consumer<Path> onOpenRecent) {
        this.onOpenRecent = onOpenRecent;
        getStyleClass().add("welcome");

        Label title = new Label("MDViewer");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("Version " + version);
        subtitle.getStyleClass().add("welcome-subtitle");

        VBox heading = new VBox(2, title, subtitle);
        heading.setAlignment(Pos.CENTER);

        VBox actions = new VBox(2,
                action("New document", "Ctrl+N", onNew),
                action("Open file...", "Ctrl+O", onOpenFile),
                action("Open folder...", "Ctrl+Shift+O", onOpenFolder));
        actions.getStyleClass().add("welcome-actions");

        Label recentHeading = new Label("RECENT WORKSPACES");
        recentHeading.getStyleClass().add("welcome-section");
        recentSection = new VBox(6, recentHeading, recentList);
        recentSection.getStyleClass().add("welcome-recent");

        VBox card = new VBox(22, heading, actions, recentSection);
        card.getStyleClass().add("welcome-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(460);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        setAlignment(Pos.CENTER);
        setPadding(new Insets(40));
        getChildren().add(card);
    }

    /**
     * One row: what it does on the left, how to do it from the keyboard on the right.
     *
     * <p>A Hyperlink rather than a Button because these are choices, not commands - a
     * column of full-width buttons reads as a form to be filled in.
     */
    private static HBox action(String text, String shortcut, Runnable onChosen) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("welcome-action");
        link.setFocusTraversable(false);
        link.setOnAction(e -> onChosen.run());

        Label keys = new Label(shortcut);
        keys.getStyleClass().add("welcome-shortcut");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(12, link, gap, keys);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Fills the recent list, hiding the section when there is nothing to show.
     *
     * <p>An empty "Recent workspaces" heading on a first run is a promise the app has not
     * kept yet; better to say nothing until there is something to say.
     */
    public void setRecent(List<Path> roots) {
        recentList.getChildren().clear();
        boolean any = roots != null && !roots.isEmpty();
        recentSection.setVisible(any);
        recentSection.setManaged(any);
        if (!any) {
            return;
        }
        for (Path root : roots.subList(0, Math.min(roots.size(), 6))) {
            Hyperlink link = new Hyperlink(root.getFileName() == null
                    ? root.toString() : root.getFileName().toString());
            link.getStyleClass().add("welcome-recent-name");
            link.setFocusTraversable(false);
            link.setOnAction(e -> onOpenRecent.accept(root));

            Label where = new Label(root.getParent() == null ? "" : root.getParent().toString());
            where.getStyleClass().add("welcome-recent-path");
            /* The name gives way to nothing; the path gives way to everything. A deep temp
               directory was long enough to squeeze the name down to an ellipsis, leaving a
               row whose only clickable part read "...". The path is there to tell two
               checkouts apart, so losing its middle costs nothing. */
            link.setMinWidth(Region.USE_PREF_SIZE);
            where.setMinWidth(0);
            where.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(where, Priority.ALWAYS);

            HBox row = new HBox(10, link, where);
            row.setAlignment(Pos.BASELINE_LEFT);
            recentList.getChildren().add(row);
        }
    }
}
