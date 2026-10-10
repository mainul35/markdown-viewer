package com.mdviewer.ai;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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

    /**
     * One provider's row.
     *
     * <p>The labels are held, not just the answers: configuring a provider can change its
     * host and whether it has a key, and a row that still shows the old ones after you
     * have just changed them reads as an edit that did not take.
     */
    private static final class Row {
        private final String name;
        private final CheckBox show;
        private final CheckBox allow;
        private final Label model;
        private final Label host;
        private final Label key;
        private String hostName;
        /** True when the host was already allowed before this dialog opened. */
        private boolean wasAllowed;

        private Row(String name, CheckBox show, CheckBox allow,
                    Label model, Label host, Label key) {
            this.name = name;
            this.show = show;
            this.allow = allow;
            this.model = model;
            this.host = host;
            this.key = key;
        }
    }

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

        // Set when a Configure dialog wrote something, so Cancel here does not claim
        // nothing happened - that edit is already on disk.
        boolean[] edited = {false};
        List<Row> rows = new ArrayList<>();
        int[] nextLine = {1};
        for (String name : all) {
            addRow(grid, nextLine, rows, owner, config, edited, name, enabled.contains(name));
        }

        /*
         * Any OpenAI-compatible address can already be a provider - the config file finds
         * them by scanning for <name>.baseUrl, and has done all along. What was missing was
         * a way in: adding one meant knowing the file existed, knowing the three key names,
         * and editing it by hand, which is the same wall the host list had before this
         * dialog took it over.
         */
        Button addGateway = new Button("Add gateway...");
        addGateway.getStyleClass().add("provider-configure");
        addGateway.setTooltip(new Tooltip(
                "Add any OpenAI-compatible endpoint - a self-hosted gateway, a proxy, "
                        + "or a provider not listed here"));
        addGateway.setOnAction(e -> {
            String created = configure(owner, config, null);
            if (created != null) {
                edited[0] = true;
                // Ticked: adding one and then having to find it and turn it on reads as
                // the add not having worked.
                addRow(grid, nextLine, rows, owner, config, edited, created, true);
            }
        });

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

        HBox addRowBar = new HBox(addGateway);
        addRowBar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, scroll, addRowBar, note);
        content.setPadding(new Insets(6));
        /* Wide enough for six columns. At 600 the Configure column pushed everything into
           ellipses - "SH...", "ALLOW H...", "Configu..." - and a row of truncated headings
           is a table you have to guess at. */
        content.setPrefWidth(820);
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
            // Cancel abandons the ticks, not the edits: a Configure dialog answered OK has
            // already written, and reporting no change would leave the panel showing a
            // provider that is no longer what it says.
            return edited[0];
        }

        // Configuring writes as it goes, so the dialog may already have changed something
        // by the time OK is pressed.
        boolean changed = edited[0];
        for (Row row : rows) {
            // Newly ticked only: wasAllowed marks the ones that were already on.
            if (row.allow.isSelected() && !row.wasAllowed
                    && row.hostName != null && !row.hostName.isEmpty()) {
                config.saveAllowedHost(row.hostName);
                changed = true;
            }
        }

        List<String> show = new ArrayList<>();
        for (Row row : rows) {
            if (row.show.isSelected()) {
                show.add(row.name);
            }
        }
        if (!show.equals(enabled)) {
            config.saveEnabledProviders(show);
            changed = true;
        }
        return changed;
    }

    /**
     * Builds one provider's row and appends it to the grid.
     *
     * <p>A method rather than a loop body because a gateway added while the dialog is open
     * has to arrive the same way the nine shipped ones did. Two routes to the same row is
     * how one of them ends up subtly different from the other.
     */
    private static void addRow(GridPane grid, int[] nextLine, List<Row> rows, Window owner,
                               AiConfig config, boolean[] edited, String name, boolean shown) {
        CheckBox show = new CheckBox();
        show.setSelected(shown);

        CheckBox allow = new CheckBox();

        Label model = new Label();
        model.getStyleClass().add("provider-name");
        model.setMinWidth(Region.USE_PREF_SIZE);
        Label hostLabel = new Label();
        hostLabel.getStyleClass().add("provider-host");
        Label key = new Label();
        key.getStyleClass().add("provider-host");
        key.setMinWidth(Region.USE_PREF_SIZE);

        Row row = new Row(name, show, allow, model, hostLabel, key);
        describe(row, config);
        // What the dialog found, so the OK handler can tell an answer from a fact.
        row.wasAllowed = allow.isSelected();

        Button configure = new Button("Configure...");
        configure.getStyleClass().add("provider-configure");
        configure.setFocusTraversable(false);
        configure.setTooltip(new Tooltip("Address, model and key for " + name));
        configure.setMinWidth(Region.USE_PREF_SIZE);
        configure.setOnAction(e -> {
            if (configure(owner, config, name) != null) {
                edited[0] = true;
                // Re-read rather than patch: the edit may have moved the host, which
                // changes whether it is allowed and whether that box is still ours to
                // tick. One place decides what a row says, and this is it.
                describe(row, config);
                row.wasAllowed = row.allow.isSelected() && row.allow.isDisable();
            }
        });

        int line = nextLine[0]++;
        grid.add(show, 0, line);
        grid.add(model, 1, line);
        grid.add(hostLabel, 2, line);
        grid.add(allow, 3, line);
        grid.add(key, 4, line);
        grid.add(configure, 5, line);
        GridPane.setHalignment(show, javafx.geometry.HPos.CENTER);
        GridPane.setHalignment(allow, javafx.geometry.HPos.CENTER);

        rows.add(row);
    }

    /** Fills a row from the config as it stands now. */
    private static void describe(Row row, AiConfig config) {
        AiConfig.Endpoint endpoint = config.endpoint(row.name);
        String host = AiConfig.hostOf(endpoint.baseUrl());
        boolean allowed = config.isAllowed(endpoint.baseUrl());

        row.hostName = host;
        row.model.setText(row.name + "   "
                + (endpoint.model().isBlank() ? "(no model)" : endpoint.model()));
        row.host.setText(host.isEmpty() ? "(no base URL)" : host);
        // The one column allowed to shorten, so the whole address stays reachable.
        row.host.setTooltip(new Tooltip(endpoint.baseUrl().isBlank()
                ? "No base URL set" : endpoint.baseUrl()));

        boolean localhost = host.equals("localhost") || host.equals("127.0.0.1");
        row.key.setText(!endpoint.apiKey().isBlank() ? "set"
                : localhost ? "not needed" : "none");

        row.allow.setSelected(allowed);
        /* Already-allowed hosts are not un-allowed here. Removing one is a decision about
           trust, and a dialog full of ticked boxes is the wrong place to make it by
           accident; ai.properties remains where that is done. */
        row.allow.setDisable(allowed);
    }

    /**
     * One provider's address, model and key.
     *
     * <p>Everything needed to make a provider usable, in the place that lists them. It was
     * spread over three: the base URL and model in ai.properties, the key behind a button
     * in the panel that only ever offered the provider already selected, and the host in a
     * fourth dialog again.
     *
     * <p>The key is the one field that is not simply written. It is a secret, so it
     * follows the same rule as the panel's own key dialog: kept for the session unless
     * asked for otherwise, and never shown back.
     *
     * <p>With {@code name} null this is instead "add a gateway", and the only difference
     * is a name field at the top. The rest of a custom endpoint is the same three facts
     * every shipped provider is made of, so it would be a second form saying the same
     * things, free to drift from this one.
     *
     * @param name the provider to edit, or null to create one
     * @return the provider's name if anything was written, otherwise null
     */
    private static String configure(Window owner, AiConfig config, String name) {
        boolean creating = name == null;
        AiConfig.Endpoint endpoint = creating
                ? new AiConfig.Endpoint("", "", "", "") : config.endpoint(name);

        TextField gatewayName = new TextField();
        gatewayName.setPromptText("my-gateway");

        TextField baseUrl = new TextField(endpoint.baseUrl());
        baseUrl.setPromptText("https://api.example.com/v1");
        baseUrl.setPrefColumnCount(34);

        TextField model = new TextField(endpoint.model());
        model.setPromptText("model name");

        PasswordField key = new PasswordField();
        key.setPromptText(endpoint.apiKey().isBlank()
                ? "no key set" : "a key is set - type to replace it");
        CheckBox remember = new CheckBox("Save the key in " + config.getFile().getFileName());
        CheckBox allowHost = new CheckBox("Allow this host to receive documents");
        allowHost.setSelected(config.isAllowed(endpoint.baseUrl()));
        allowHost.setDisable(config.isAllowed(endpoint.baseUrl()));

        Label nameProblem = new Label();
        nameProblem.getStyleClass().add("ai-status");
        nameProblem.setWrapText(true);
        nameProblem.setMaxWidth(430);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(4));
        int at = 0;
        if (creating) {
            form.add(new Label("Name"), 0, at);
            form.add(gatewayName, 1, at++);
            form.add(nameProblem, 1, at++);
        }
        form.add(new Label("Base URL"), 0, at);
        form.add(baseUrl, 1, at++);
        form.add(new Label("Model"), 0, at);
        form.add(model, 1, at++);
        form.add(new Label("API key"), 0, at);
        form.add(key, 1, at++);
        form.add(remember, 1, at++);
        form.add(allowHost, 1, at);

        Label note = new Label("The base URL is the OpenAI-compatible root, ending in /v1 "
                + "for most providers. Leave the key empty to keep the one already set, or "
                + "for a local model that needs none. Unticked, a key is kept for this "
                + "session only and nothing is written to disk.");
        note.setWrapText(true);
        note.setMaxWidth(430);
        note.getStyleClass().add("ai-status");

        VBox content = new VBox(12, form, note);
        content.setPadding(new Insets(6));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(creating ? "Add gateway" : "Configure " + name);
        dialog.initOwner(owner);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }

        /* Checked as it is typed and OK held shut until it passes, rather than refused
           after the fact: the name becomes a key prefix in a properties file, so the rules
           are real, and finding out which one you broke by pressing OK is the worse half
           of the same conversation. */
        if (creating) {
            Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            Runnable revalidate = () -> {
                String problem = nameProblem(gatewayName.getText(), config);
                nameProblem.setText(problem == null ? "" : problem);
                ok.setDisable(problem != null);
            };
            gatewayName.textProperty().addListener((o, a, b) -> revalidate.run());
            revalidate.run();
            javafx.application.Platform.runLater(gatewayName::requestFocus);
        }

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }
        if (creating) {
            name = gatewayName.getText().strip();
        }

        boolean wrote = false;
        String url = baseUrl.getText() == null ? "" : baseUrl.getText().strip();
        String chosen = model.getText() == null ? "" : model.getText().strip();
        // A new gateway is written even when both are empty: the row has to exist before
        // it can be filled in, and an add that silently added nothing is worse than a
        // half-finished row you can see and come back to.
        if (creating || !url.equals(endpoint.baseUrl()) || !chosen.equals(endpoint.model())) {
            wrote = config.saveEndpoint(name, url, chosen);
        }

        String entered = key.getText();
        if (entered != null && !entered.isBlank()) {
            // Session by default, disk only when asked - the same rule the panel's key
            // dialog follows, because it is the same secret.
            config.setRuntimeKey(name, entered);
            if (remember.isSelected()) {
                config.saveKey(name, entered);
            }
            wrote = true;
        }

        if (allowHost.isSelected() && !allowHost.isDisable()) {
            String host = AiConfig.hostOf(url);
            if (!host.isEmpty()) {
                config.saveAllowedHost(host);
                wrote = true;
            }
        }
        return wrote ? name : null;
    }

    /**
     * Why this gateway name cannot be used, or null when it can.
     *
     * <p>The name becomes the prefix of three keys in a properties file, and the file is
     * read back by splitting each key at its first dot. So a dot in the name would make a
     * provider called something else, and a blank or a duplicate would quietly land on top
     * of an endpoint that already exists.
     */
    private static String nameProblem(String candidate, AiConfig config) {
        String name = candidate == null ? "" : candidate.strip();
        if (name.isEmpty()) {
            return "Give the gateway a short name - it labels the row and names its "
                    + "settings in the file.";
        }
        if (!name.matches("[A-Za-z0-9_-]+")) {
            return "Letters, digits, hyphen and underscore only. No dots or spaces: the "
                    + "name becomes a key in " + config.getFile().getFileName() + ".";
        }
        if (RESERVED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return "\"" + name + "\" is used for MDViewer's own settings. Pick another.";
        }
        for (String existing : config.providerNames()) {
            if (existing.equalsIgnoreCase(name)) {
                return "There is already a provider called \"" + existing
                        + "\". Configure that one instead, or pick another name.";
            }
        }
        return null;
    }

    /** First key segments the config file already uses for things that are not providers. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "allowedhosts", "context", "provider", "providers", "scan");

    /* Nothing here shortens itself. A heading or a state that reads "not nee..." has lost
       the word that carried the meaning, and these are all short to begin with - the
       column that may give way is the host, which has a tooltip. */
    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("provider-heading");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
