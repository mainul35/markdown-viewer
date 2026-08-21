package com.mdviewer.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;

import java.util.function.Consumer;

/**
 * Who is signed in, at the foot of the explorer.
 *
 * <p>The account had no home in this window. Signing in and out lived under Settings,
 * beside the auto-sync tick and the machine list, which is where somebody looks for a
 * preference rather than for themselves - and nothing on screen said which account the
 * documents were syncing to. Two accounts and one laptop is an ordinary arrangement, and
 * this application could not answer the first question either of them would ask.
 *
 * <p>At the bottom of the explorer because that is where the web client puts it and where
 * every editor with an account puts it, and because the alternative is a header already
 * carrying the workspace name. It is the least interesting thing on the screen until the
 * moment it is the only interesting thing.
 */
public final class AccountBar extends HBox {

    /** What the menu offers, carried out by whoever owns the dialogs. */
    public interface Actions {
        void signIn();

        void signOut();

        void upgrade();
    }

    private final Label name = new Label();
    private final ContextMenu menu = new ContextMenu();
    private final Label planName = new Label();
    private final Label planUsage = new Label();
    private final Region planFill = new Region();
    private final StackPane planTrack = new StackPane(planFill);
    private final MenuItem signOut = new MenuItem("Sign out");
    private final MenuItem upgrade = new MenuItem("Upgrade…");

    private Actions actions;
    private boolean signedIn;

    public AccountBar() {
        getStyleClass().add("account-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        name.getStyleClass().add("account-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        getChildren().addAll(avatar(), name, caret());

        buildMenu();

        setOnMouseClicked(event -> {
            if (!signedIn) {
                /* Nothing to show about an account there is not one of. The one thing
                   somebody can do here is sign in, so the click does it. */
                if (actions != null) {
                    actions.signIn();
                }
                return;
            }
            if (menu.isShowing()) {
                menu.hide();
                return;
            }
            /*
             * Above, which is the whole reason this is a menu shown by hand rather than a
             * context menu left to place itself. This sits on the bottom edge of the window:
             * anything opening downwards from it opens off the screen, and JavaFX would
             * answer that by flipping it somewhere unrelated.
             */
            menu.show(this, Side.TOP, 0, 0);
            event.consume();
        });

        setSignedOut();
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    /** Shows the account, with its plan and how much of it is used. */
    public void setSignedIn(String account, String tier, long usedBytes, long limitBytes) {
        signedIn = true;
        name.setText(account == null || account.isBlank() ? "Signed in" : account);
        setTooltip(name.getText());

        planName.setText(tier == null || tier.isBlank() ? "FREE" : tier.toUpperCase(java.util.Locale.ROOT));
        planUsage.setText(limitBytes > 0
                ? bytes(usedBytes) + " of " + bytes(limitBytes)
                : bytes(usedBytes) + " used");

        /*
         * Bound to the track rather than set to a width, because the menu has not been laid
         * out when this runs and the track's width is zero until it has. A bar drawn from a
         * measurement taken too early is a bar that is always empty.
         */
        double share = limitBytes > 0
                ? Math.max(0, Math.min(1, (double) usedBytes / limitBytes))
                : 0;
        planFill.prefWidthProperty().bind(planTrack.widthProperty().multiply(share));

        signOut.setDisable(false);
        upgrade.setDisable(false);
        getStyleClass().remove("account-bar-out");
    }

    /** Shows an invitation instead, because there is nothing else true to show. */
    public void setSignedOut() {
        signedIn = false;
        menu.hide();
        name.setText("Sign in to cloud");
        setTooltip("Sync these documents to your MDViewer account");
        if (!getStyleClass().contains("account-bar-out")) {
            getStyleClass().add("account-bar-out");
        }
    }

    /**
     * The plan, once it has been asked for.
     *
     * <p>Separate from the name because they arrive at different times: the account is known
     * the moment a token is read, and the quota is a request to the server. Waiting for the
     * second before showing the first would leave the bar blank for the length of a round
     * trip, on a window that has just opened.
     */
    public void setPlan(String tier, long usedBytes, long limitBytes) {
        if (signedIn) {
            setSignedIn(name.getText(), tier, usedBytes, limitBytes);
        }
    }

    private void setTooltip(String text) {
        Tooltip.install(this, new Tooltip(text));
    }

    private void buildMenu() {
        menu.getStyleClass().add("account-menu");

        Label heading = new Label();
        heading.getStyleClass().add("account-menu-name");
        heading.textProperty().bind(name.textProperty());
        CustomMenuItem who = new CustomMenuItem(heading, false);
        who.getStyleClass().add("account-menu-static");

        planName.getStyleClass().add("account-plan");
        planUsage.getStyleClass().add("account-usage");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        planFill.getStyleClass().add("account-meter-fill");
        planTrack.getStyleClass().add("account-meter");
        planTrack.setAlignment(Pos.CENTER_LEFT);
        planTrack.setMinWidth(200);

        VBox plan = new VBox(6, new HBox(planName, gap, planUsage), planTrack);
        plan.getStyleClass().add("account-menu-plan");
        CustomMenuItem quota = new CustomMenuItem(plan, false);
        quota.getStyleClass().add("account-menu-static");

        upgrade.setOnAction(e -> {
            if (actions != null) {
                actions.upgrade();
            }
        });
        signOut.setOnAction(e -> {
            if (actions != null) {
                actions.signOut();
            }
        });

        menu.getItems().addAll(who, quota, new SeparatorMenuItem(), upgrade,
                new SeparatorMenuItem(), signOut);
    }

    /* Drawn rather than a glyph, for the reason the reveal icon is: JavaFX renders symbol
       characters differently on each platform, and a shape takes its colour from CSS. */
    private static Group avatar() {
        Circle head = new Circle(3.1);
        head.getStyleClass().add("account-avatar-mark");
        head.setCenterX(7);
        head.setCenterY(5.4);

        Circle shoulders = new Circle(5.4);
        shoulders.getStyleClass().add("account-avatar-mark");
        shoulders.setCenterX(7);
        shoulders.setCenterY(14.4);

        Circle ring = new Circle(7.6);
        ring.getStyleClass().add("account-avatar-ring");
        ring.setCenterX(7);
        ring.setCenterY(7.6);

        /* Clipped to the ring, so the shoulders read as a head and shoulders rather than as
           a second circle hanging below the badge. */
        Circle clip = new Circle(7.6);
        clip.setCenterX(7);
        clip.setCenterY(7.6);
        Group inside = new Group(head, shoulders);
        inside.setClip(clip);

        return new Group(ring, inside);
    }

    private static Polygon caret() {
        /* Pointing up, because that is where the menu opens. A caret that points the other
           way is a small lie told on every window. */
        Polygon mark = new Polygon(0, 4, 8, 4, 4, 0);
        mark.getStyleClass().add("account-caret");
        return mark;
    }

    /**
     * The same units and the same precision as the web client's formatBytes.
     *
     * <p>Copied rather than approximated, down to the two decimals on gigabytes. This is one
     * account seen from two applications, and the point of the second one agreeing is lost
     * if the same quota reads as 597.8 KB in a browser and 598 KB here - somebody comparing
     * them has to work out which is rounding, and neither screen says.
     */
    static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0);
        }
        if (value < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", value / 1024.0 / 1024);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", value / 1024.0 / 1024 / 1024);
    }

    /** Runs {@code work} on the UI thread, whichever thread noticed the change. */
    public static void onUi(Runnable work) {
        if (Platform.isFxApplicationThread()) {
            work.run();
        } else {
            Platform.runLater(work);
        }
    }
}
