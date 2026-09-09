package nl.ctasoftware.crypto.ticker.server.service.image;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ImageBroadcasterService {

    private static final long HEARTBEAT_MS = Duration.ofSeconds(15).toMillis();
    private static final long CLIENT_RECONNECT_MS = 3000; // client hint for EventSource retry

    private final Map<String, Set<SseEmitter>> emittersByPanel = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<byte[]>> latestPngByPanel = new ConcurrentHashMap<>();

    /** Track a heartbeat task per-emitter so we can cancel on cleanup. */
    private final ConcurrentMap<SseEmitter, ScheduledFuture<?>> heartbeatByEmitter = new ConcurrentHashMap<>();

    /**
     * Latest-wins coalescing per emitter (plan §7 perf 2): the 10 Hz preview tick of every
     * panel submits one frame per viewer; a slow viewer must never accumulate a queue of
     * stale frames. A queued send only swaps the payload reference; the drain loop always
     * picks up whatever is newest when it actually gets to run.
     */
    private static final class PendingSend {
        final AtomicReference<String> payload = new AtomicReference<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
    }

    private final ConcurrentMap<SseEmitter, PendingSend> pendingByEmitter = new ConcurrentHashMap<>();

    /** Dedicated executors: one for sending, one for scheduling heartbeats. */
    private final ExecutorService sseExec;
    private final ScheduledExecutorService scheduler;

    public ImageBroadcasterService(@Qualifier("sseExecutor") ExecutorService sseExecutor,
                                   @Qualifier("sseScheduler") ScheduledExecutorService scheduler) {
        this.sseExec = sseExecutor;
        this.scheduler = scheduler;
    }

    /** Call this whenever you produce a new image for a panel. */
    public void updateLatest(String serial, BufferedImage img) {
        byte[] png = toPng(img);
        latestPngByPanel.computeIfAbsent(serial, k -> new AtomicReference<>()).set(png);
        broadcast(serial, png);
    }

    /** Register a subscriber and immediately send the latest frame if we have one. */
    public SseEmitter register(String serial) {
        final SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        final Set<SseEmitter> set = emittersByPanel.computeIfAbsent(serial, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);

        final Runnable cleanup = () -> unregisterInternal(serial, emitter, set);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        // schedule heartbeat comments to keep the connection alive through proxies/CDNs
        ScheduledFuture<?> hb = scheduler.scheduleAtFixedRate(
                () -> safeHeartbeat(emitter, set),
                HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        heartbeatByEmitter.put(emitter, hb);

        // Send the latest frame right away (if available)
        byte[] current = latestPngByPanel.getOrDefault(serial, new AtomicReference<>()).get();
        if (current != null) {
            // precompute base64 once (used by all emitters for this send)
            final String b64 = Base64.getEncoder().encodeToString(current);
            final PendingSend pending = pendingByEmitter.computeIfAbsent(emitter, k -> new PendingSend());
            pending.payload.set(b64);
            if (pending.scheduled.compareAndSet(false, true)) {
                sseExec.execute(() -> drain(emitter, pending, set));
            }
        } else {
            // still send a small initial event with reconnect hint so client gets 'open'
            sseExec.execute(() -> safeSendComment(emitter, "init", set));
        }
        return emitter;
    }

    /** Optional external unregister hook if you need to force-close from controller/service. */
    public void unregister(String serial, SseEmitter emitter) {
        Set<SseEmitter> set = emittersByPanel.get(serial);
        if (set != null) unregisterInternal(serial, emitter, set);
        try { emitter.complete(); } catch (Exception ignore) {}
    }

    /** Delete-path cleanup: the per-serial latest-frame cache must not leak on panel delete. */
    public void removeSerial(String serial) {
        latestPngByPanel.remove(serial);
    }

    private void unregisterInternal(String serial, SseEmitter emitter, Set<SseEmitter> ownerSet) {
        ownerSet.remove(emitter);
        if (ownerSet.isEmpty()) emittersByPanel.remove(serial);
        ScheduledFuture<?> hb = heartbeatByEmitter.remove(emitter);
        if (hb != null) hb.cancel(true);
        pendingByEmitter.remove(emitter);
    }

    private void broadcast(String panelKey, byte[] png) {
        var set = emittersByPanel.get(panelKey);
        if (set == null || set.isEmpty()) return;

        // compute Base64 once per broadcast (avoid repeating per emitter)
        final String b64 = Base64.getEncoder().encodeToString(png);
        for (var emitter : List.copyOf(set)) {
            enqueueFrame(emitter, b64, set);
        }
    }

    /** Coalescing enqueue: a queued (or draining) emitter just swaps in the newest frame. */
    private void enqueueFrame(SseEmitter emitter, String b64, Set<SseEmitter> ownerSet) {
        final PendingSend pending = pendingByEmitter.computeIfAbsent(emitter, k -> new PendingSend());
        pending.payload.set(b64);
        if (pending.scheduled.compareAndSet(false, true)) {
            sseExec.execute(() -> drain(emitter, pending, ownerSet));
        }
    }

    /** Sends frames until the queue is empty, always taking the newest payload. */
    private void drain(SseEmitter emitter, PendingSend pending, Set<SseEmitter> ownerSet) {
        try {
            String b64;
            while ((b64 = pending.payload.getAndSet(null)) != null) {
                safeSendB64(emitter, b64, ownerSet);
            }
        } finally {
            pending.scheduled.set(false);
        }
        // A frame may have been swapped in between the final getAndSet(null) and the flag
        // reset: re-queue exactly once so it is not lost (still latest-wins, never a queue).
        if (pending.payload.get() != null && pending.scheduled.compareAndSet(false, true)) {
            sseExec.execute(() -> drain(emitter, pending, ownerSet));
        }
    }

    private void safeSendB64(SseEmitter emitter, String b64, Set<SseEmitter> ownerSet) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name("frame")
                    .id(String.valueOf(System.currentTimeMillis()))
                    .data(b64)
                    .reconnectTime(CLIENT_RECONNECT_MS); // hint client retry time
            sendGuarded(emitter, event);
        } catch (Exception e) {
            // IOException = dead connection, IllegalStateException = emitter completed concurrently;
            // unregister on anything so neither frames nor the periodic heartbeat task keep dying on it.
            unregisterEmitterOnly(emitter, ownerSet);
        }
    }

    private void safeHeartbeat(SseEmitter emitter, Set<SseEmitter> ownerSet) {
        try {
            sendGuarded(emitter, SseEmitter.event().comment("heartbeat").reconnectTime(CLIENT_RECONNECT_MS));
        } catch (Exception e) {
            unregisterEmitterOnly(emitter, ownerSet);
        }
    }

    private void safeSendComment(SseEmitter emitter, String text, Set<SseEmitter> ownerSet) {
        try {
            sendGuarded(emitter, SseEmitter.event().comment(text).reconnectTime(CLIENT_RECONNECT_MS));
        } catch (Exception e) {
            unregisterEmitterOnly(emitter, ownerSet);
        }
    }

    /**
     * SseEmitter.send is not thread-safe: concurrent sends (heartbeat from the scheduler thread
     * vs frames from sseExec) interleave their writes and corrupt the event stream, after which
     * the client's parser can wait forever on a malformed event. Serialize sends per emitter.
     */
    private void sendGuarded(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        synchronized (emitter) {
            emitter.send(event);
        }
    }

    private void unregisterEmitterOnly(SseEmitter emitter, Set<SseEmitter> ownerSet) {
        ownerSet.remove(emitter);
        ScheduledFuture<?> hb = heartbeatByEmitter.remove(emitter);
        if (hb != null) hb.cancel(true);
        pendingByEmitter.remove(emitter);
        try { emitter.complete(); } catch (Exception ignore) {}
    }

    private static byte[] toPng(BufferedImage img) {
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
