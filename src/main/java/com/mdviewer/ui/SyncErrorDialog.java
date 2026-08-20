package com.mdviewer.ui;

import com.mdviewer.sync.CloudClient;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * What to show when a sync does not finish.
 *
 * <p>A line in the status bar was not enough. It is gone by the time somebody looks up, it
 * cannot be selected, and it has no room for the two things that decide what to do next: the
 * server's own sentence, and the code it refused with. A 409 means plan again; a 413 says how
 * many bytes short you are; an <em>insufficient_scope</em> means grant a permission. Those are
 * different problems with the same shrug of a status line.
 *
 * <p>So this says the message plainly, names the code, and puts the whole of it in a text box
 * that can be selected and copied - which is the difference between somebody being able to
 * report a fault and having to describe it from memory.
 */
public final class SyncErrorDialog {

    private SyncErrorDialog() {
    }

    /**
     * @param owner   the window it belongs to, so it cannot be lost behind the editor
     * @param doing   what was being attempted, in the reader's terms: "Sending notes/2026"
     * @param failure what went wrong
     */
    public static void show(Window owner, String doing, Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("Sync failed");
        alert.setHeaderText(doing + " did not finish.");
        alert.getDialogPane().setMinWidth(520);

        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        String code = codeOf(failure);
        alert.setContentText(message);

        /*
         * The detail behind the sentence, in a box that can be selected. Collapsed, because
         * most of the time the message alone is the answer and a wall of stack trace on
         * opening buries it.
         */
        TextArea details = new TextArea(detail(doing, message, code, failure));
        details.setEditable(false);
        details.setWrapText(false);
        details.setMaxWidth(Double.MAX_VALUE);
        details.setMaxHeight(Double.MAX_VALUE);
        details.setPrefRowCount(12);
        GridPane.setVgrow(details, Priority.ALWAYS);
        GridPane.setHgrow(details, Priority.ALWAYS);

        GridPane holder = new GridPane();
        holder.setMaxWidth(Double.MAX_VALUE);
        holder.add(new Label("Everything about this failure, ready to copy:"), 0, 0);
        holder.add(details, 0, 1);

        alert.getDialogPane().setExpandableContent(holder);
        alert.showAndWait();
    }

    /**
     * The code the server refused with, or a stand-in when there is none.
     *
     * <p>Not being able to reach the host at all has no code, and saying so is better than an
     * empty field the reader has to interpret.
     */
    public static String codeOf(Throwable failure) {
        if (failure instanceof CloudClient.SyncException refusal) {
            String code = refusal.code == null || refusal.code.isBlank() ? "none" : refusal.code;
            return code + " (HTTP " + refusal.status + ")";
        }
        if (failure instanceof java.net.UnknownHostException
                || failure instanceof java.net.ConnectException) {
            return "unreachable (the server did not answer)";
        }
        return failure.getClass().getSimpleName();
    }

    private static String detail(String doing, String message, String code, Throwable failure) {
        StringWriter out = new StringWriter();
        out.append("What: ").append(doing).append(System.lineSeparator());
        out.append("Code: ").append(code).append(System.lineSeparator());
        out.append("Message: ").append(message).append(System.lineSeparator());
        out.append(System.lineSeparator());
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
