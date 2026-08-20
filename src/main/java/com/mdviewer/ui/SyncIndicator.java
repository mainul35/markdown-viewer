package com.mdviewer.ui;

import com.mdviewer.sync.SyncActivity;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * The spinner in the status bar, on while something is talking to the cloud.
 *
 * <p>Syncing already happens quietly in the background, which is right - it should not
 * interrupt anybody - but quiet and invisible are different things. Without this the only
 * evidence a sync ran was a sentence in the status bar after it finished, so a slow one
 * looked like nothing happening at all, and the honest question "is it stuck, or is it
 * working" had no answer on screen.
 *
 * <p>Two pieces of timing, and they matter more than the drawing does.
 *
 * <p><strong>It waits before appearing.</strong> Most rounds finish in a few hundred
 * milliseconds and a spinner that flashes every five minutes is a distraction that says
 * nothing; only work slow enough to be wondered about is worth showing.
 *
 * <p><strong>Once shown, it stays a moment.</strong> A spinner that appears and vanishes in
 * the same instant reads as a glitch rather than as progress, and a reader who catches it out
 * of the corner of their eye is left unsure whether anything happened.
 */
public final class SyncIndicator extends HBox {

    /** Below this, the work is not worth mentioning. */
    private static final Duration BEFORE_SHOWING = Duration.millis(400);

    /** Once it is up, this is the least time it stays. */
    private static final Duration AT_LEAST = Duration.millis(700);

    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label caption = new Label();

    private final PauseTransition waitToShow = new PauseTransition(BEFORE_SHOWING);
    private final PauseTransition holdOnScreen = new PauseTransition(AT_LEAST);

    /** Set when work finished while the indicator was still serving its minimum time. */
    private boolean hideWhenHoldEnds;

    public SyncIndicator() {
        getStyleClass().add("sync-indicator");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);

        spinner.getStyleClass().add("sync-spinner");
        spinner.setPrefSize(12, 12);
        spinner.setMinSize(12, 12);
        spinner.setMaxSize(12, 12);
        caption.getStyleClass().add("sync-caption");

        getChildren().addAll(spinner, caption);
        hideNow();

        waitToShow.setOnFinished(event -> showNow());
        holdOnScreen.setOnFinished(event -> {
            if (hideWhenHoldEnds) {
                hideNow();
            }
        });
    }

    /**
     * Follows an activity counter for as long as this indicator exists.
     *
     * <p>The counter reports from whichever background thread did the work, so everything
     * this does is bounced onto the FX thread here rather than at each of the call sites -
     * there is one place to get that right, and this is it.
     */
    public void watch(SyncActivity activity) {
        activity.addListener((busy, what) -> Platform.runLater(() -> {
            if (busy) {
                caption.setText(what);
                start();
            } else {
                stop();
            }
        }));
    }

    private void start() {
        hideWhenHoldEnds = false;
        if (isVisible()) {
            return;   // Already up: a second sync starting is not a new appearance.
        }
        waitToShow.playFromStart();
    }

    private void stop() {
        // Finished before it was worth showing. Nothing was ever drawn, so nothing is undone.
        waitToShow.stop();

        if (!isVisible()) {
            hideNow();
            return;
        }
        if (holdOnScreen.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            hideWhenHoldEnds = true;   // Taken down when its minimum time is served.
            return;
        }
        hideNow();
    }

    private void showNow() {
        setVisible(true);
        setManaged(true);
        hideWhenHoldEnds = false;
        holdOnScreen.playFromStart();
    }

    private void hideNow() {
        setVisible(false);
        setManaged(false);
        hideWhenHoldEnds = false;
    }
}
