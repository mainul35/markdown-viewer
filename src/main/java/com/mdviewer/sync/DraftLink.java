package com.mdviewer.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sends the document being written to the cloud, about once a minute.
 *
 * <p>Not a sync. A sync moves documents somebody has decided to keep; this carries what is
 * on screen and not yet saved, so that an hour of writing is never further than a minute
 * from safety. The server holds one draft per document and overwrites it, which is why this
 * can afford to send the whole thing every time rather than working out what changed.
 *
 * <p>A connection held open rather than a request a minute. Sixty handshakes an hour is
 * real cost for something that runs as long as somebody is working, and a connection is
 * also the only way the server can say something back without being asked.
 *
 * <p><strong>Never in the way.</strong> Everything here happens on its own thread, failures
 * are reported once and quietly, and nothing about writing or saving depends on any of it.
 * A protection that interrupts the work it protects is not one anybody keeps switched on.
 */
public final class DraftLink implements AutoCloseable {

    /** Often enough to matter, rarely enough that nobody notices it happening. */
    private static final Duration EVERY = Duration.ofMinutes(1);

    /** The first retry waits this long; each further one waits twice as long, up to a cap. */
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    private static final Duration SLOWEST_RETRY = Duration.ofMinutes(5);

    /** What the link is doing, for an indicator the reader can glance at. */
    public enum State { OFF, CONNECTING, PROTECTED, UNPROTECTED }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cloud-drafts");
        thread.setDaemon(true);
        return thread;
    });

    private final String base;
    private final CloudClient.Authorization authorization;
    private final DeviceIdentity device;
    private final Consumer<State> onState;

    private final AtomicBoolean sending = new AtomicBoolean(false);

    private volatile WebSocket socket;
    private volatile String workspaceId;
    private volatile String path;
    private volatile Supplier<String> text;
    private volatile String lastSent;
    private volatile Duration retryIn = FIRST_RETRY;
    private ScheduledFuture<?> ticking;

    public DraftLink(String endpoint, CloudClient.Authorization authorization,
                     Consumer<State> onState) {
        this.base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.authorization = authorization;
        this.device = new DeviceIdentity();
        this.onState = onState == null ? state -> { } : onState;
    }

    /**
     * Starts protecting a document.
     *
     * <p>Called when one is opened for editing, and again when the reader moves to another:
     * the link follows whatever they are actually writing, because that is the only document
     * whose unsaved state is worth anything.
     *
     * @param current how to read the text as it stands - called on a background thread, so
     *                it must be safe to call from one
     */
    public synchronized void protect(String workspaceId, String path, Supplier<String> current) {
        this.workspaceId = workspaceId;
        this.path = path;
        this.text = current;
        this.lastSent = null;

        if (ticking == null) {
            ticking = clock.scheduleWithFixedDelay(this::sendIfChanged,
                    EVERY.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }
        connect();
    }

    /**
     * Sends whatever is unsent and stops.
     *
     * <p>Once more on the way out, because the most valuable minute is the one that has not
     * been sent yet - closing an editor is exactly when it would otherwise be lost.
     */
    public synchronized void release() {
        sendIfChanged();
        this.workspaceId = null;
        this.path = null;
        this.text = null;
        onState.accept(State.OFF);
    }

    @Override
    public void close() {
        release();
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
        WebSocket open = socket;
        if (open != null) {
            open.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
        clock.shutdownNow();
    }

    // ------------------------------------------------------------------ the connection

    private void connect() {
        if (workspaceId == null) {
            return;
        }
        onState.accept(State.CONNECTING);
        try {
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + authorization.token())
                    // The same header every request carries, so a machine that has been shut
                    // out cannot keep pushing what somebody types on it.
                    .header("X-MDViewer-Device", device.id())
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(base.replaceFirst("^http", "ws") + "/ws/drafts"),
                            new Listener())
                    .whenComplete((open, failure) -> {
                        if (failure != null) {
                            unprotected();
                            return;
                        }
                        socket = open;
                        retryIn = FIRST_RETRY;
                        onState.accept(State.PROTECTED);
                    });
        } catch (Exception e) {
            // Includes not being signed in, which is an ordinary state and not an error.
            unprotected();
        }
    }

    /**
     * Says the writing is not being protected, and arranges to try again.
     *
     * <p>Backing off rather than retrying tightly: a cloud that cannot be reached is normal
     * on a laptop, and a client that hammers it every second while somebody works on a train
     * is worse than one that waits.
     */
    private void unprotected() {
        socket = null;
        onState.accept(State.UNPROTECTED);
        Duration wait = retryIn;
        retryIn = wait.multipliedBy(2).compareTo(SLOWEST_RETRY) > 0 ? SLOWEST_RETRY : wait.multipliedBy(2);
        clock.schedule(this::connect, wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ one push

    private void sendIfChanged() {
        String workspace = this.workspaceId;
        String document = this.path;
        Supplier<String> reader = this.text;
        WebSocket open = this.socket;

        if (workspace == null || document == null || reader == null || open == null) {
            return;
        }
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        try {
            String content = reader.get();
            if (content == null || content.equals(lastSent)) {
                // Nothing has been typed since the last push. Sending it again would cost a
                // round trip to tell the server what it already holds.
                return;
            }
            open.sendText(frame(workspace, document, content), true);
            lastSent = content;
        } catch (Exception e) {
            unprotected();
        } finally {
            sending.set(false);
        }
    }

    private static String frame(String workspace, String path, String content) {
        return "{\"workspace\":" + CloudClient.quote(workspace)
               + ",\"path\":" + CloudClient.quote(path)
               + ",\"content\":" + CloudClient.quote(content) + "}";
    }

    /** Reads what the server says. Little of it needs acting on; one part of it does. */
    private final class Listener implements WebSocket.Listener {

        private final StringBuilder message = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String body = message.toString();
                message.setLength(0);
                if (body.contains("\"type\":\"refused\"")) {
                    /*
                     * The document is too large to hold as a draft. Saving and syncing still
                     * work, and the reader is told so - believing you are protected when you
                     * are not is worse than knowing you are not.
                     */
                    onState.accept(State.UNPROTECTED);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            unprotected();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            unprotected();
            return null;
        }
    }

    /** Bytes rather than characters, matching what the server measures. */
    static long sizeOf(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }
}
