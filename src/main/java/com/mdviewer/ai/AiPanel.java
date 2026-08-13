package com.mdviewer.ai;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The assistant panel: a conversation about the document currently open.
 *
 * <p>Phase 1. It can see the document and answer about it. It cannot yet read the files
 * the document references, and it cannot change anything - proposing a rewrite and
 * showing it as a diff is phase 2. Nothing here writes to the document or to disk.
 *
 * <p>The endpoint's host is shown in the header at all times, deliberately. This is the
 * only part of the app that sends anything anywhere, and which machine is receiving a
 * private document should never be something you have to go and look up.
 */
public final class AiPanel extends VBox {

    private final AiConfig config;
    private final ChatProvider provider;

    private final ComboBox<String> providerChoice = new ComboBox<>();
    private final Label hostLabel = new Label();
    private final VBox transcript = new VBox(10);
    private final ScrollPane transcriptScroll = new ScrollPane(transcript);
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send");
    private final Label status = new Label();

    /** Supplies the document in focus, so the panel never reaches into the controller. */
    private Supplier<String> documentSupplier = () -> "";
    private Supplier<String> documentNameSupplier = () -> "the document";

    private final List<ChatProvider.Message> history = new ArrayList<>();
    private boolean busy = false;

    public AiPanel(AiConfig config) {
        this.config = config;
        this.provider = new ChatProvider(config);
        getStyleClass().add("ai-panel");
        setMinWidth(0);

        Label title = new Label("ASSISTANT");
        title.getStyleClass().add("ai-panel-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        providerChoice.getItems().setAll(config.providerNames());
        providerChoice.setValue(config.defaultProvider());
        providerChoice.valueProperty().addListener((o, a, b) -> showHost());
        providerChoice.setFocusTraversable(false);

        HBox header = new HBox(8, title, headerSpacer, providerChoice);
        header.getStyleClass().add("ai-panel-header");
        header.setAlignment(Pos.CENTER_LEFT);

        hostLabel.getStyleClass().add("ai-host");
        hostLabel.setMaxWidth(Double.MAX_VALUE);

        transcript.getStyleClass().add("ai-transcript");
        transcript.setPadding(new Insets(10));
        transcriptScroll.setFitToWidth(true);
        transcriptScroll.getStyleClass().add("ai-transcript-scroll");
        VBox.setVgrow(transcriptScroll, Priority.ALWAYS);

        input.setPromptText("Ask about this document...");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.getStyleClass().add("ai-input");
        input.setOnKeyPressed(event -> {
            // Enter sends and Shift+Enter breaks the line, which is the convention every
            // chat box uses; a multi-line question is still perfectly possible.
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                sendCurrentInput();
            }
        });

        send.setOnAction(e -> sendCurrentInput());
        send.setDefaultButton(false);
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            history.clear();
            transcript.getChildren().clear();
            setStatus("");
        });
        Button test = new Button("Test connection");
        test.setOnAction(e -> testConnection());
        Button key = new Button("API key...");
        key.setOnAction(e -> promptForKey());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, clear, key, test, footerSpacer, send);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        status.getStyleClass().add("ai-status");
        status.setWrapText(true);

        VBox composer = new VBox(6, input, buttons, status);
        composer.setPadding(new Insets(8));
        composer.getStyleClass().add("ai-composer");

        getChildren().addAll(header, hostLabel, transcriptScroll, composer);
        showHost();
    }

    public void setDocumentSupplier(Supplier<String> supplier) {
        this.documentSupplier = supplier == null ? () -> "" : supplier;
    }

    public void setDocumentNameSupplier(Supplier<String> supplier) {
        this.documentNameSupplier = supplier == null ? () -> "the document" : supplier;
    }

    public TextArea getInput() {
        return input;
    }

    /** Shows which machine is about to receive the document, and whether that is allowed. */
    private void showHost() {
        AiConfig.Endpoint endpoint = currentEndpoint();
        if (endpoint == null || endpoint.baseUrl().isBlank()) {
            hostLabel.setText("No endpoint configured - see " + config.getFile());
            hostLabel.getStyleClass().setAll("ai-host", "ai-host-bad");
            return;
        }
        boolean allowed = config.isAllowed(endpoint.baseUrl());
        hostLabel.setText((allowed ? "sends to  " : "NOT ALLOWED  ")
                + endpoint.host() + "   ·   " + endpoint.model()
                + (endpoint.apiKey().isBlank() ? "   ·   no key set" : ""));
        hostLabel.getStyleClass().setAll("ai-host", allowed ? "ai-host-ok" : "ai-host-bad");
    }

    private AiConfig.Endpoint currentEndpoint() {
        String name = providerChoice.getValue();
        return name == null || name.isBlank() ? null : config.endpoint(name);
    }

    private void sendCurrentInput() {
        String question = input.getText().strip();
        if (question.isEmpty() || busy) {
            return;
        }
        AiConfig.Endpoint endpoint = currentEndpoint();
        if (endpoint == null || endpoint.baseUrl().isBlank()) {
            setStatus("No endpoint is configured. Edit " + config.getFile() + ".");
            return;
        }
        input.clear();
        addBubble("You", question, "ai-user");

        Label reply = addBubble(endpoint.model(), "", "ai-assistant");
        busy = true;
        send.setDisable(true);
        setStatus("Thinking...");

        List<ChatProvider.Message> messages = buildMessages(question);

        // Off the FX thread: this blocks for as long as the model takes to answer.
        Thread worker = new Thread(() -> {
            try {
                provider.stream(endpoint, messages, token ->
                        Platform.runLater(() -> {
                            reply.setText(reply.getText() + token);
                            transcriptScroll.setVvalue(1.0);
                        }));
                Platform.runLater(() -> {
                    history.add(new ChatProvider.Message("user", question));
                    history.add(new ChatProvider.Message("assistant", reply.getText()));
                    finish("");
                });
            } catch (ChatProvider.NotAllowedException e) {
                Platform.runLater(() -> finish(e.getMessage()));
            } catch (Exception e) {
                Platform.runLater(() -> finish("Could not reach " + endpoint.host()
                        + ": " + e.getMessage()));
            }
        }, "ai-chat");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Asks for the API key for the selected provider.
     *
     * <p>A {@link PasswordField}, so it is not shown on screen or left in a transcript,
     * and it is never echoed back into the status line either. By default the key is kept
     * in memory for this session only: typing one into a window should not write it to
     * disk as a side effect. Saving it is a separate tick-box, because that is a different
     * decision with different consequences.
     */
    private void promptForKey() {
        AiConfig.Endpoint endpoint = currentEndpoint();
        if (endpoint == null) {
            setStatus("Choose a provider first.");
            return;
        }
        PasswordField field = new PasswordField();
        field.setPromptText("Paste the key for " + endpoint.name());
        field.setPrefColumnCount(34);
        CheckBox remember = new CheckBox("Save it in " + config.getFile().getFileName()
                + " so it survives a restart");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(10);
        form.setPadding(new Insets(14));
        form.add(new Label("Key for " + endpoint.name()
                + "  (" + endpoint.host() + ")"), 0, 0);
        form.add(field, 0, 1);
        form.add(remember, 0, 2);
        Label note = new Label("Left unticked, the key is kept in memory for this session "
                + "only and nothing is written to disk.");
        note.setWrapText(true);
        note.setMaxWidth(360);
        note.getStyleClass().add("ai-status");
        form.add(note, 0, 3);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("API key");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(getScene().getStylesheets());
        }
        Platform.runLater(field::requestFocus);

        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            String entered = field.getText();
            if (entered == null || entered.isBlank()) {
                config.setRuntimeKey(endpoint.name(), null);
                setStatus("Key cleared for " + endpoint.name() + ".");
                return;
            }
            config.setRuntimeKey(endpoint.name(), entered);
            if (remember.isSelected()) {
                boolean saved = config.saveKey(endpoint.name(), entered);
                setStatus(saved
                        ? "Key set and saved to " + config.getFile() + "."
                        : "Key set for this session; it could not be written to "
                                + config.getFile() + ".");
            } else {
                setStatus("Key set for this session.");
            }
            showHost();
        });
    }

    /**
     * Checks the endpoint answers and accepts the key, sending no document content.
     *
     * <p>Separate from asking a question on purpose. The two things that go wrong first
     * are the host and the key, and finding that out should not require handing over a
     * document to do it.
     */
    private void testConnection() {
        AiConfig.Endpoint endpoint = currentEndpoint();
        if (endpoint == null || endpoint.baseUrl().isBlank()) {
            setStatus("No endpoint is configured. Edit " + config.getFile() + ".");
            return;
        }
        setStatus("Contacting " + endpoint.host() + "...");
        Thread worker = new Thread(() -> {
            String result = provider.testConnection(endpoint);
            Platform.runLater(() -> setStatus(result));
        }, "ai-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(String message) {
        busy = false;
        send.setDisable(false);
        setStatus(message);
    }

    /**
     * The conversation as the model sees it: instructions, the document, then the turns.
     *
     * <p>The document is re-sent each time rather than only at the start, because it is
     * being edited while the conversation goes on and an assistant answering about a stale
     * copy is worse than one that says it cannot see the file.
     */
    private List<ChatProvider.Message> buildMessages(String question) {
        List<ChatProvider.Message> messages = new ArrayList<>();
        messages.add(new ChatProvider.Message("system", """
                You are helping edit one Markdown document inside a Markdown editor.

                Answer about the document you are given. Be brief and concrete. Prefer the
                specific noun to the abstract one. Do not restate the question, do not
                summarise what you are about to say, and do not pad the answer with
                headings it does not need.

                Never invent file paths, commands, version numbers or API names. If the
                document does not say something and you were not given a source for it,
                say that you cannot tell from what you were given.
                """));
        String document = documentSupplier.get();
        messages.add(new ChatProvider.Message("system",
                "The document currently open is " + documentNameSupplier.get()
                        + ". Its full contents follow.\n\n" + document));
        messages.addAll(history);
        messages.add(new ChatProvider.Message("user", question));
        return messages;
    }

    private Label addBubble(String who, String text, String styleClass) {
        Label speaker = new Label(who);
        speaker.getStyleClass().add("ai-speaker");
        Label body = new Label(text);
        body.setWrapText(true);
        body.getStyleClass().addAll("ai-bubble", styleClass);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox group = new VBox(2, speaker, body);
        transcript.getChildren().add(group);
        transcriptScroll.setVvalue(1.0);
        return body;
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisible(!message.isBlank());
        status.setManaged(!message.isBlank());
    }
}
