package com.mdviewer.ai;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Which LLM providers the assistant offers, and which hosts it may send documents to.
 *
 * <p>Nine providers ship configured so that using one is a matter of allowing it rather
 * than looking up a base URL. That left two problems this answers: a picker listing nine
 * when two of them work is mostly a list of things that will refuse you, and the only way
 * to permit a host was to find ai.properties and edit it.
 *
 * <p>The two columns are deliberately separate. Showing a provider is a preference; letting
 * it receive the document you have open is not, and a single tick doing both would make
 * the second one an accident of the first.
 */
public final class ProviderSettings {

    private ProviderSettings() {
    }

    /** One provider's row, kept so the answers can be read back when the dialog closes. */
    private record Row(String name, CheckBox show, CheckBox allow, String host) {}

    /**
     * Shows the dialog and applies what was chosen.
     *
     * @return true if anything changed, so the caller knows to refresh the panel
     */
    public static boolean show(Window owner, AiConfig config) {
        List<String> all = config.providerNames();
        List<String> enabled = config.enabledProviderNames();

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        grid.setPadding(new Insets(4, 4, 4, 4));

        grid.add(heading("SHOW"), 0, 0);
        grid.add(heading("PROVIDER"), 1, 0);
        grid.add(heading("HOST"), 2, 0);
        grid.add(heading("ALLOW HOST"), 3, 0);
        grid.add(heading("KEY"), 4, 0);

        List<Row> rows = new ArrayList<>();
        int line = 1;
        for (String name : all) {
            AiConfig.Endpoint endpoint = config.endpoint(name);
            String host = AiConfig.hostOf(endpoint.baseUrl());
            boolean allowed = config.isAllowed(endpoint.baseUrl());

            CheckBox show = new CheckBox();
            show.setSelected(enabled.contains(name));

            CheckBox allow = new CheckBox();
            allow.setSelected(allowed);
            // Already-allowed hosts are not un-allowed here. Removing one is a decision
            // about trust, and a dialog full of ticked boxes is the wrong place to make it
            // by accident; ai.properties remains where that is done.
            allow.setDisable(allowed);

            Label model = new Label(name + "   " + endpoint.model());
            model.getStyleClass().add("provider-name");

            Label hostLabel = new Label(host.isEmpty() ? "(no base URL)" : host);
            hostLabel.getStyleClass().add("provider-host");

            boolean localhost = host.equals("localhost") || host.equals("127.0.0.1");
            Label key = new Label(!endpoint.apiKey().isBlank() ? "set"
                    : localhost ? "not needed" : "none");
            key.getStyleClass().add("provider-host");

            grid.add(show, 0, line);
            grid.add(model, 1, line);
            grid.add(hostLabel, 2, line);
            grid.add(allow, 3, line);
            grid.add(key, 4, line);
            GridPane.setHalignment(show, javafx.geometry.HPos.CENTER);
            GridPane.setHalignment(allow, javafx.geometry.HPos.CENTER);

            rows.add(new Row(name, show, allow, host));
            line++;
        }

        Label note = new Label("Allowing a host lets MDViewer send it your questions, the "
                + "open document, and every file a scan reads. Only allow one you are "
                + "willing to send this work to. A key is read from " + config.getFile()
                + " or from the environment.");
        note.setWrapText(true);
        note.setMaxWidth(560);
        note.getStyleClass().add("ai-status");

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(280);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(12, scroll, note);
        content.setPadding(new Insets(6));
        content.setPrefWidth(600);
        content.setMaxHeight(Region.USE_PREF_SIZE);
        content.setAlignment(Pos.TOP_LEFT);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("AI providers");
        dialog.initOwner(owner);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }

        ButtonType answer = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (answer != ButtonType.OK) {
            return false;
        }

        boolean changed = false;
        for (Row row : rows) {
            // Newly ticked only: allow.isDisable() marks the ones that were already on.
            if (row.allow().isSelected() && !row.allow().isDisable() && !row.host().isEmpty()) {
                config.saveAllowedHost(row.host());
                changed = true;
            }
        }

        List<String> show = new ArrayList<>();
        for (Row row : rows) {
            if (row.show().isSelected()) {
                show.add(row.name());
            }
        }
        if (!show.equals(enabled)) {
            config.saveEnabledProviders(show);
            changed = true;
        }
        return changed;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("provider-heading");
        return label;
    }
}
