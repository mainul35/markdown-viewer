package com.mdviewer.sync;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps a workspace in step with the cloud without being asked each time.
 *
 * <p>Runs when a workspace is opened and then on a timer. What it will and will not do on
 * its own is {@link AutoSync}'s decision, not this class's - here is only when to ask.
 *
 * <p>Every run is skipped rather than queued if one is already going. A sync that takes
 * longer than the interval would otherwise start overlapping itself, and two syncs of one
 * workspace at once is how a plan gets applied against a scan that is no longer true.
 */
public final class AutoSyncService {

    /** Long enough that a slow sync finishes; short enough that another machine's work arrives. */
    private static final long EVERY_MINUTES = 5;

    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cloud-auto-sync");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Consumer<String> say;

    private ScheduledFuture<?> scheduled;
    private Path root;

    /**
     * @param say how to tell the reader something happened - given only sentences worth
     *            interrupting them for, since this runs while they are working
     */
    public AutoSyncService(Consumer<String> say) {
        this.say = say == null ? message -> { } : say;
    }

    /**
     * Watches this workspace, and syncs it now.
     *
     * <p>Immediately as well as on the timer, because the moment a workspace is opened is
     * exactly when another machine's work is most likely to be waiting.
     */
    public synchronized void watch(Path workspace) {
        stop();
        this.root = workspace;
        if (workspace == null) {
            return;
        }
        scheduled = clock.scheduleWithFixedDelay(this::runOnce, 0, EVERY_MINUTES, TimeUnit.MINUTES);
    }

    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    public void shutdown() {
        stop();
        clock.shutdownNow();
    }

    // ------------------------------------------------------------------ one round

    private void runOnce() {
        Path workspace = this.root;
        if (workspace == null || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            CloudConfig config = new CloudConfig();
            if (!config.isEnabled() || !config.autoSync()) {
                return;
            }

            SyncState state = SyncState.forWorkspace(workspace);
            if (!state.isEnrolled()) {
                // Not linked to anything yet. Choosing which cloud workspace a folder
                // belongs to is the one question this cannot answer on somebody's behalf.
                return;
            }

            SyncRunner runner = new SyncRunner(workspace, config.client(), state, message -> { });
            SyncRunner.Proposal proposal = runner.plan();
            AutoSync.Decision decision = AutoSync.decide(proposal);

            if (!decision.apply()) {
                if (!decision.reason().isEmpty()) {
                    say.accept(decision.reason());
                }
                return;
            }
            runner.apply(proposal);
            say.accept(decision.reason());

        } catch (CloudSession.NotSignedIn e) {
            /*
             * Silent on purpose. This runs every few minutes, and somebody who has not signed
             * in has not asked to be reminded of it on a schedule.
             */
        } catch (Exception e) {
            // Also quiet. A cloud that cannot be reached is a normal condition on a laptop,
            // and an alert every few minutes about it is an alert people learn to ignore.
            System.err.println("MDViewer: automatic sync did not run - " + e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
