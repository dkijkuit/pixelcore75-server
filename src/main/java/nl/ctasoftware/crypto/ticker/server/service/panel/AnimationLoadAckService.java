package nl.ctasoftware.crypto.ticker.server.service.panel;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Panel -> server feedback channel for animation uploads/plays: the panel publishes an "ANIL" ack
 * (magic + slot u8 + echoed uploadId u32, all LE) on {@code <serial>/anim/loaded} when a staged
 * upload lands in its slot file or when playback starts, so the rotation slot can count from
 * playback start instead of server-side publish time. Acks carrying a stale uploadId are ignored,
 * so a late ack from a previous animation can never complete the waiter of the current one.
 */
@Slf4j
@Service
public class AnimationLoadAckService {

    static final String ANIM_LOADED_TOPIC_FILTER = "+/anim/loaded";
    static final String ANIM_LOADED_SUFFIX = "/anim/loaded";
    static final String ANIM_LOADED_MAGIC = "ANIL";
    static final int ANIM_LOADED_PAYLOAD = 9; // magic + slot + uploadId

    private record PendingAck(long uploadId, CompletableFuture<Long> loadedAt) {}

    private final Map<String, PendingAck> waiters = new ConcurrentHashMap<>();
    private final AtomicLong uploadIds = new AtomicLong();

    public AnimationLoadAckService(final IMqttClient mqttClient) throws MqttException {
        mqttClient.subscribe(ANIM_LOADED_TOPIC_FILTER, (topic, message) -> {
            final byte[] payload = message.getPayload();
            if (payload.length != ANIM_LOADED_PAYLOAD
                    || !ANIM_LOADED_MAGIC.equals(new String(payload, 0, 4, StandardCharsets.US_ASCII))) {
                log.warn("Ignoring unknown anim/loaded payload on {}: {} bytes", topic, payload.length);
                return;
            }

            final String serial = topic.substring(0, topic.length() - ANIM_LOADED_SUFFIX.length());
            final long uploadId = readU32Le(payload, 5);

            final PendingAck pending = waiters.get(serial);
            if (pending == null) {
                log.debug("Ignoring anim/loaded ack for panel {} (no waiter)", serial);
            } else if (pending.uploadId != uploadId) {
                log.debug("Ignoring stale anim/loaded ack for panel {} (got uploadId {}, waiting for {})",
                        serial, uploadId, pending.uploadId);
            } else {
                pending.loadedAt().complete(System.nanoTime());
            }
        });
    }

    /** Arms an ack waiter with a fresh uploadId for a new upload; the id must be embedded in the payload. */
    public long arm(final String serial) {
        final long uploadId = uploadIds.incrementAndGet();
        return arm(serial, uploadId);
    }

    /**
     * Arms an ack waiter for a known uploadId — used to await the play ack of an animation that
     * was uploaded earlier (staged or cached in a panel slot), where the id must match the one
     * the panel still remembers.
     */
    public long arm(final String serial, final long uploadId) {
        waiters.put(serial, new PendingAck(uploadId, new CompletableFuture<>()));
        return uploadId;
    }

    public boolean awaitLoaded(final String serial, final long uploadId, final Duration timeout) {
        final PendingAck pending = waiters.get(serial);
        if (pending == null || pending.uploadId() != uploadId) {
            return false;
        }

        try {
            pending.loadedAt().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            log.error("Failed waiting for anim/loaded ack for panel {}", serial, e);
            return false;
        } finally {
            waiters.remove(serial, pending);
        }
    }

    private static long readU32Le(final byte[] payload, final int offset) {
        return (payload[offset] & 0xFFL)
                | (payload[offset + 1] & 0xFFL) << 8
                | (payload[offset + 2] & 0xFFL) << 16
                | (payload[offset + 3] & 0xFFL) << 24;
    }
}
