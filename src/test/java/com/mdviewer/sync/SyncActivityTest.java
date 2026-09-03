package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is anything talking to the cloud?
 *
 * <p>One wrong answer here is worse than the other. A spinner that fails to appear costs a
 * moment of not knowing; a spinner that never stops makes the reader distrust everything else
 * the window tells them, and there is nothing they can do about it. The tests below are mostly
 * about the second.
 */
class SyncActivityTest {

    @Test
    @DisplayName("it is busy from the first start until the last finish")
    void countsOverlappingWork() throws Exception {
        SyncActivity activity = new SyncActivity();
        List<Boolean> seen = new ArrayList<>();
        activity.addListener((busy, what) -> seen.add(busy));

        AutoCloseable first = activity.begin("Syncing notes");
        assertTrue(activity.isBusy());

        // A second sync starting while the first runs is not a second appearance, and the
        // first one finishing is not the end of the work.
        AutoCloseable second = activity.begin("Syncing settings");
        first.close();
        assertTrue(activity.isBusy(), "one of two finishing does not mean idle");

        second.close();
        assertFalse(activity.isBusy());
        assertEquals(List.of(true, false), seen, "the change is reported once each way");
    }

    @Test
    @DisplayName("work that throws still ends")
    void endsWhenTheWorkFails() {
        SyncActivity activity = new SyncActivity();
        try (AutoCloseable ignored = activity.begin("Syncing")) {
            throw new IllegalStateException("the server said no");
        } catch (Exception expected) {
            // The point is what try-with-resources did on the way out.
        }
        assertFalse(activity.isBusy(), "a failed sync must not leave the spinner running");
    }

    /**
     * A listener that throws is usually a bug in the drawing, and the sync it was told about
     * has already happened. Letting it escape here would abort a real sync on its way out of
     * a try-with-resources - the drawing breaking the work it was only supposed to describe.
     */
    @Test
    @DisplayName("a listener that throws does not take the sync down with it")
    void survivesABadListener() throws Exception {
        SyncActivity activity = new SyncActivity();
        activity.addListener((busy, what) -> {
            throw new RuntimeException("something in the window went wrong");
        });

        try (AutoCloseable ignored = activity.begin("Syncing")) {
            assertTrue(activity.isBusy());
        }
        assertFalse(activity.isBusy());
    }

    @Test
    @DisplayName("several threads starting and finishing together leave it idle")
    void survivesConcurrency() throws Exception {
        SyncActivity activity = new SyncActivity();
        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try (AutoCloseable ignored = activity.begin("Syncing")) {
                    go.await();
                } catch (Exception e) {
                    // Counted either way; the assertion is about the count.
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertFalse(activity.isBusy(), "the count did not come back to zero");
    }
}
