package com.mdviewer.sync;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether anything is talking to the cloud right now, and what.
 *
 * <p>Syncing happens on background threads, from several places that do not know about each
 * other: the five-minute timer, a folder somebody sent from the tree, a settings push. The
 * reader wants one answer to "is it doing something", so the counting lives here and the
 * indicator in the status bar simply watches it.
 *
 * <p>Deliberately free of JavaFX. This is counting, not drawing, and keeping the toolkit out
 * means the background code that reports work does not have to know which thread it is on.
 */
public final class SyncActivity {

    /** Told when the answer changes. Called on whichever thread caused the change. */
    public interface Listener {
        void changed(boolean busy, String what);
    }

    private static final SyncActivity SHARED = new SyncActivity();

    public static SyncActivity shared() {
        return SHARED;
    }

    private final AtomicInteger inFlight = new AtomicInteger();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String what = "";

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Records that something has started, and returns the thing that says it has finished.
     *
     * <p>An {@link AutoCloseable} rather than a matching {@code end()} call, so the caller
     * writes try-with-resources and cannot leave the indicator spinning by returning early or
     * throwing. A spinner that never stops is worse than no spinner: it makes the reader
     * distrust every other thing the window tells them.
     */
    public AutoCloseable begin(String description) {
        what = description == null ? "Syncing" : description;
        int now = inFlight.incrementAndGet();
        if (now == 1) {
            tell(true);
        }
        return () -> {
            if (inFlight.decrementAndGet() == 0) {
                tell(false);
            }
        };
    }

    public boolean isBusy() {
        return inFlight.get() > 0;
    }

    public String what() {
        return what;
    }

    private void tell(boolean busy) {
        for (Listener listener : listeners) {
            try {
                listener.changed(busy, what);
            } catch (RuntimeException e) {
                // A listener that throws is not allowed to stop the sync that told it.
            }
        }
    }
}
