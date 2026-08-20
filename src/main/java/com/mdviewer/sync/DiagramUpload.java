package com.mdviewer.sync;

import com.mdviewer.service.DiagramKey;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends a diagram this machine has drawn to the cloud, so a browser can show it.
 *
 * <p>The browser cannot render PlantUML and the server will not: layout is CPU-bound and the
 * server has other work. But this machine has just drawn the picture in order to show it in
 * the preview, and until now threw it away a moment later. This keeps it.
 *
 * <p>Entirely best-effort. Signed out, offline, or refused - the preview is unaffected and
 * nothing is said, because the reader did not ask for this and cannot act on it failing. The
 * only visible consequence of it not happening is a diagram in the browser that shows its
 * source instead of its picture.
 */
public final class DiagramUpload {

    /** Recorded with each upload, so a version that draws something wrong can be found. */
    private static final String RENDERER = "plantuml-mit/1.2026.6";

    /**
     * What has already gone up this session.
     *
     * <p>The preview re-renders on every pause in typing, and most of those renders contain
     * the same diagrams as the last one. Without this, editing a paragraph under a diagram
     * would upload that diagram again every second or two.
     */
    private final Set<String> sent = Collections.synchronizedSet(new HashSet<>());

    private final ExecutorService uploads = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mdviewer-diagram-upload");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Offers a drawing. Returns immediately; the upload happens on its own thread.
     *
     * @param source the fence body as written in the document - the browser hashes the same
     *               text, so anything else here would file the picture under a name the
     *               browser cannot work out
     * @param svg    what PlantUML drew
     */
    public void offer(String source, String svg) {
        if (svg == null || !svg.stripLeading().startsWith("<svg")) {
            /*
             * PlantUML answers a diagram it cannot draw with an error plate rather than an
             * exception, and that plate is what arrives here. Uploading it would cache a
             * failure and show it in the browser for as long as the document lives - long
             * after the diagram was fixed.
             */
            return;
        }

        String key = DiagramKey.of(source);
        if (!sent.add(key)) {
            return;
        }

        uploads.submit(() -> {
            try {
                CloudConfig config = new CloudConfig();
                if (!config.isEnabled()) {
                    sent.remove(key);   // Signing in later should send it after all.
                    return;
                }
                config.client().putDiagram(key, svg, RENDERER);
            } catch (Exception e) {
                // Quiet, and forgotten, so the next render tries again.
                sent.remove(key);
            }
        });
    }

    public void shutdown() {
        uploads.shutdownNow();
    }
}
