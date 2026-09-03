package com.mdviewer;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Does the window still load?
 *
 * <p>Every {@code onAction="#handler"} in the FXML is resolved by name at load time, against
 * methods that have to carry {@code @FXML}. Nothing the compiler does checks that: a handler
 * renamed, removed, or quietly stripped of its annotation compiles perfectly and every other
 * test passes, and the application then fails to start at all.
 *
 * <p>Which is exactly what happened - an edit landed between an {@code @FXML} and the method
 * under it, moving the annotation onto something else, and the whole window stopped loading
 * over one line in a file no test read. This is that line's test.
 */
class MainFxmlTest {

    @Test
    @DisplayName("the main window's FXML loads, with every handler it names")
    void loads() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    FXMLLoader loader =
                            new FXMLLoader(MainApp.class.getResource("/fxml/main.fxml"));
                    loader.load();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
        } catch (IllegalStateException alreadyRunning) {
            Platform.runLater(() -> {
                try {
                    new FXMLLoader(MainApp.class.getResource("/fxml/main.fxml")).load();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
        } catch (UnsupportedOperationException noToolkit) {
            /*
             * No display. Skipped rather than failed, and skipped loudly: a machine that
             * cannot start JavaFX cannot answer this question either way, and a test that
             * fails there would train somebody to ignore it.
             */
            assumeTrue(false, "no JavaFX toolkit on this machine: " + noToolkit.getMessage());
            return;
        }

        assertTrue(done.await(60, TimeUnit.SECONDS), "the toolkit never finished loading it");

        Throwable thrown = failure.get();
        if (thrown != null) {
            // The message names the handler, which is the whole value of this test.
            assertNull(thrown, "the main window would not load: " + thrown.getMessage());
        }
    }
}
