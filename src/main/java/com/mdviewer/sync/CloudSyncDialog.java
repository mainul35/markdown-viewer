package com.mdviewer.sync;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Sync a workspace: see what would change, then decide.
 *
 * <p>Two stages on one screen, and never one. The plan is shown - every path, what would
 * happen to it, and why - and nothing moves until Apply is pressed. A sync button that
 * starts writing to documents on the first click is a button nobody can press with
 * confidence, and confidence is the whole point of a feature that sends your files
 * somewhere.
 */
public final class CloudSyncDialog {

    private final Stage dialog = new Stage();
    private final Label headline = new Label();
    private final Label detail = new Label();
    private final VBox changes = new VBox(2);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button apply = new Button("Apply");
    private final Button close = new Button("Close");

    private final Path root;
    private final CloudConfig config;
    private SyncRunner runner;
    private SyncRunner.Proposal proposal;

    private CloudSyncDialog(Path root, CloudConfig config) {
        this.root = root;
        this.config = config;
    }

    /** Opens on {@code root}, plans immediately, and waits. */
    public static void open(Stage owner, Path root, CloudConfig config) {
        CloudSyncDialog view = new CloudSyncDialog(root, config);
        view.build(owner);
        view.startPlan();
        view.dialog.showAndWait();
    }

    private void build(Stage owner) {
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Sync " + root.getFileName());

        headline.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        detail.setWrapText(true);
        detail.getStyleClass().add("cloud-sync-detail");

        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);

        ScrollPane scroller = new ScrollPane(changes);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(280);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        apply.setDefaultButton(true);
        apply.setDisable(true);
        apply.setOnAction(e -> startApply());
        close.setOnAction(e -> dialog.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spinner, spacer, close, apply);

        // The endpoint, on screen. This is the only part of the application that sends a
        // document anywhere, and which machine is receiving it should never be something
        // you have to go and look up.
        Label where = new Label("Syncing with " + config.endpoint());
        where.setStyle("-fx-font-size: 11px;");
        where.getStyleClass().add("cloud-sync-detail");

        VBox content = new VBox(10, headline, detail, new Separator(), scroller, where,
                new Separator(), buttons);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("cloud-sync");

        Scene scene = new Scene(content, 640, 480);
        // A dialog is its own window and inherits neither the stylesheet nor the theme
        // class, so without this it opens in default JavaFX grey beside a themed app.
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
            scene.getRoot().getStyleClass().setAll(owner.getScene().getRoot().getStyleClass());
        }
        dialog.setScene(scene);
    }

    // ------------------------------------------------------------------ stages

    private void startPlan() {
        headline.setText("Working out what would change...");
        detail.setText("");
        busy(true);
        Thread worker = new Thread(() -> {
            try {
                SyncState state = SyncState.forWorkspace(root);
                runner = new SyncRunner(root, config.client(), state, this::status);
                SyncRunner.Proposal planned = runner.plan();
                Platform.runLater(() -> showPlan(planned));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> failed("Could not plan the sync", e));
            }
        }, "cloud-sync-plan");
        worker.setDaemon(true);
        worker.start();
    }

    private void showPlan(SyncRunner.Proposal planned) {
        this.proposal = planned;
        busy(false);
        changes.getChildren().clear();

        if (planned.isUpToDate()) {
            headline.setText("Already in sync");
            detail.setText("Nothing has changed on either side since the last sync.");
            apply.setDisable(true);
            return;
        }

        int uploads = planned.of("UPLOAD").size();
        int downloads = planned.of("DOWNLOAD").size();
        int removals = planned.of("DELETE_LOCAL").size() + planned.of("DELETE_REMOTE").size();
        int conflicts = planned.conflicts().size();

        headline.setText(summary(uploads, downloads, removals, conflicts));

        StringBuilder note = new StringBuilder();
        if (conflicts > 0) {
            // Said before anything happens, because it is the one outcome that leaves the
            // reader with work to do.
            note.append(conflicts == 1 ? "One document was" : conflicts + " documents were")
                    .append(" changed in both places. Both versions will be kept - the cloud's ")
                    .append("copy arrives beside yours with .conflict- in its name, and nothing ")
                    .append("is merged. ");
        }
        if (!planned.plan().fitsInQuota()) {
            note.append("This will not fit: ")
                    .append(mb(planned.plan().bytesToUpload())).append(" to send, ")
                    .append(mb(planned.plan().limitBytes() - planned.plan().usedBytes()))
                    .append(" free on the ").append(planned.plan().tier()).append(" tier. ");
            apply.setDisable(true);
        }
        if (!planned.scan().skipped().isEmpty()) {
            note.append(planned.scan().skipped().size())
                    .append(" file(s) were left out of the scan. ");
        }
        detail.setText(note.toString());

        for (String action : List.of("CONFLICT", "UPLOAD", "DOWNLOAD", "DELETE_LOCAL", "DELETE_REMOTE")) {
            for (CloudClient.Change change : planned.of(action)) {
                changes.getChildren().add(row(change));
            }
        }
        for (String left : planned.scan().skipped()) {
            Label label = new Label("not scanned   " + left);
            label.getStyleClass().add("cloud-sync-detail");
            changes.getChildren().add(label);
        }

        if (planned.plan().fitsInQuota()) {
            apply.setDisable(false);
        }
    }

    private void startApply() {
        apply.setDisable(true);
        close.setDisable(true);
        busy(true);
        SyncRunner.Proposal toApply = proposal;
        Thread worker = new Thread(() -> {
            try {
                SyncRunner.Outcome outcome = runner.apply(toApply);
                Platform.runLater(() -> done(outcome));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> failed("The sync did not finish", e));
            }
        }, "cloud-sync-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private void done(SyncRunner.Outcome outcome) {
        busy(false);
        close.setDisable(false);
        headline.setText("Synced - now at revision " + outcome.revision());
        StringBuilder said = new StringBuilder();
        said.append(outcome.uploaded()).append(" sent, ")
                .append(outcome.downloaded()).append(" received");
        if (outcome.deletedLocally() > 0) {
            said.append(", ").append(outcome.deletedLocally())
                    .append(" moved to the recycle bin");
        }
        said.append('.');
        if (!outcome.conflictFiles().isEmpty()) {
            said.append("\n\nBoth versions were kept for:\n");
            outcome.conflictFiles().forEach(path -> said.append("  ").append(path).append('\n'));
            said.append("Open them, keep what you want, and delete the other.");
        }
        detail.setText(said.toString());
        changes.getChildren().clear();
    }

    private void failed(String what, Throwable e) {
        busy(false);
        close.setDisable(false);
        headline.setText(what);
        // The server's own message, kept. Its refusals say what to do next, and
        // "sync failed" throws away the only part that is actionable.
        detail.setText(e.getMessage() == null ? e.toString() : e.getMessage());
        apply.setDisable(true);
    }

    // ----------------------------------------------------------------- pieces

    private HBox row(CloudClient.Change change) {
        Label action = new Label(label(change.action()));
        action.setMinWidth(96);
        action.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        if (change.action().equals("CONFLICT")) {
            action.getStyleClass().add("cloud-sync-conflict");
        }

        Label path = new Label(change.path());
        Label reason = new Label(change.reason());
        reason.getStyleClass().add("cloud-sync-detail");
        reason.setStyle("-fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, action, path, spacer, reason);
        row.setPadding(new Insets(2, 4, 2, 4));
        return row;
    }

    private static String label(String action) {
        return switch (action) {
            case "UPLOAD" -> "send";
            case "DOWNLOAD" -> "receive";
            case "DELETE_LOCAL" -> "remove here";
            case "DELETE_REMOTE" -> "remove there";
            case "CONFLICT" -> "keep both";
            default -> action.toLowerCase();
        };
    }

    private static String summary(int uploads, int downloads, int removals, int conflicts) {
        StringBuilder out = new StringBuilder();
        if (uploads > 0) {
            out.append(uploads).append(" to send");
        }
        if (downloads > 0) {
            out.append(out.isEmpty() ? "" : ", ").append(downloads).append(" to receive");
        }
        if (removals > 0) {
            out.append(out.isEmpty() ? "" : ", ").append(removals).append(" to remove");
        }
        if (conflicts > 0) {
            out.append(out.isEmpty() ? "" : ", ").append(conflicts).append(" needing a decision");
        }
        return out.toString();
    }

    private static String mb(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return (bytes / 1024 / 1024) + " MB";
    }

    private void status(String message) {
        Platform.runLater(() -> headline.setText(message));
    }

    private void busy(boolean working) {
        spinner.setVisible(working);
    }
}
