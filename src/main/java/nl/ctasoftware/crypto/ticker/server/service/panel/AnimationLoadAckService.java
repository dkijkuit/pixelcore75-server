package nl.ctasoftware.crypto.ticker.server.service.panel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Panel -> server feedback channel for animation uploads/plays: the panel publishes an "ANIL" ack
 * (magic + slot u8 + echoed uploadId u32, all LE) on {@code <serial>/anim/loaded} when a staged
 * upload lands in its slot file or when playback starts, so the rotation slot can count from
 * playback start instead of server-side publish time. Acks carrying a stale uploadId are ignored,
 * so a late ack from a previous animation can never complete the waiter of the current one.
 *
 * <p>Every correlated ack also records {@code (serial, slot) -> uploadId} in a Caffeine cache
 * (lost on restart — worst case one redundant upload per slot, which is safe). Since uploadIds
 * are content hashes, the map lets {@code AnimationTransport} recognize that a panel slot still
 * holds the exact content it last acked and skip the ANIM/ANIF upload entirely (ANIP-only commit).
 * Per-entry expiry + size-bounded eviction replace the old wholesale-clear-on-overflow maps.
 */
@Slf4j
@Service
public class AnimationLoadAckService {

    static final String ANIM_LOADED_TOPIC_FILTER = "+/anim/loaded";
    static final String ANIM_LOADED_SUFFIX = "/anim/loaded";
    static final String ANIM_LOADED_MAGIC = "ANIL";
    static final int ANIM_LOADED_PAYLOAD = 9; // magic + slot + uploadId

    /**
     * Bound for the acked-slot cache (32 slots per panel, so this covers well over a hundred
     * panels): Caffeine evicts the oldest entries beyond it — the cost is one redundant upload
     * per evicted slot until its next ack repopulates it. Entries also expire after two hours
     * (a slot whose content hasn't changed for that long re-uploads once).
     */
    static final int MAX_ACKED_SLOT_ENTRIES = 4096;
    static final Duration ACKED_SLOT_EXPIRY = Duration.ofHours(2);

    /**
     * How long a panel stays on codec-0 RAW animation uploads after a v2 (PAL_RLE) upload
     * went un-acked, before the server re-probes v2: old/mixed-fleet firmware silently drops
     * length-mismatched v2 ANIFs, so the upload-ack timeout is the only signal — but a lost
     * ack must not wedge a v2-capable panel on RAW forever, and a genuinely old panel should
     * not be re-probed every cycle. Per-entry expiry = the remaining window.
     */
    static final Duration V2_DOWNGRADE_WINDOW = Duration.ofHours(1);

    private record PendingAck(long uploadId, CompletableFuture<Long> loadedAt) {}

    private record AckedSlot(String serial, int slot) {}

    private final Map<String, PendingAck> waiters = new java.util.concurrent.ConcurrentHashMap<>();
    private final Cache<AckedSlot, Long> ackedUploadIds = Caffeine.newBuilder()
            .maximumSize(MAX_ACKED_SLOT_ENTRIES)
            .expireAfterWrite(ACKED_SLOT_EXPIRY)
            .build();
    private final Cache<String, Long> v2DowngradeDeadlines = Caffeine.newBuilder()
            .maximumSize(MAX_ACKED_SLOT_ENTRIES)
            .expireAfter(new Expiry<String, Long>() {
                @Override
                public long expireAfterCreate(final String serial, final Long deadlineNanos, final long now) {
                    return Math.max(1, deadlineNanos - now); // the remaining downgrade window
                }

                @Override
                public long expireAfterUpdate(final String serial, final Long deadlineNanos, final long now, final long currentDuration) {
                    return Math.max(1, deadlineNanos - now);
                }

                @Override
                public long expireAfterRead(final String serial, final Long deadlineNanos, final long now, final long currentDuration) {
                    return currentDuration; // reads don't extend the window
                }
            })
            .build();

    public AnimationLoadAckService(final MqttTransport mqttTransport) {
        mqttTransport.subscribe(ANIM_LOADED_TOPIC_FILTER, (topic, payload) -> {
            if (payload.length != ANIM_LOADED_PAYLOAD
                    || !ANIM_LOADED_MAGIC.equals(new String(payload, 0, 4, StandardCharsets.US_ASCII))) {
                log.warn("Ignoring unknown anim/loaded payload on {}: {} bytes", topic, payload.length);
                return;
            }

            final String serial = topic.substring(0, topic.length() - ANIM_LOADED_SUFFIX.length());
            final int slot = payload[4] & 0xFF;
            final long uploadId = readU32Le(payload, 5);

            final PendingAck pending = waiters.get(serial);
            if (pending == null) {
                log.debug("Ignoring anim/loaded ack for panel {} (no waiter)", serial);
            } else if (pending.uploadId != uploadId) {
                log.debug("Ignoring stale anim/loaded ack for panel {} (got uploadId {}, waiting for {})",
                        serial, uploadId, pending.uploadId);
            } else {
                rememberAckedSlot(serial, slot, uploadId);
                pending.loadedAt().complete(System.nanoTime());
            }
        });
    }

    /**
     * Last uploadId the panel acked for {@code (serial, slot)} — i.e. the content that slot
     * still holds, for the content-hash upload skip. Empty when unknown (server restart, no
     * ack yet, or after an eviction).
     */
    public OptionalLong ackedUploadId(final String serial, final int slot) {
        final Long uploadId = ackedUploadIds.getIfPresent(new AckedSlot(serial, slot));
        return uploadId == null ? OptionalLong.empty() : OptionalLong.of(uploadId);
    }

    private void rememberAckedSlot(final String serial, final int slot, final long uploadId) {
        ackedUploadIds.put(new AckedSlot(serial, slot), uploadId);
    }

    /**
     * Whether animation uploads to this panel should go out as codec-0 RAW right now (both
     * the staged and the inline boundary path honor this). True until the panel's v2
     * downgrade deadline passes; panels never seen downgrading re-probe v2 every upload.
     */
    public boolean prefersRaw(final String serial) {
        final Long deadline = v2DowngradeDeadlines.getIfPresent(serial);
        return deadline != null && deadline - System.nanoTime() > 0;
    }

    public void markDowngraded(final String serial) {
        markDowngraded(serial, V2_DOWNGRADE_WINDOW);
    }

    void markDowngraded(final String serial, final Duration window) {
        v2DowngradeDeadlines.put(serial, System.nanoTime() + window.toNanos());
    }

    /**
     * Arms an ack waiter for a known uploadId — the id must match the one embedded in the
     * upload/play payloads (the content hash from {@code UploadIdHasher}) and the one the
     * panel still remembers for that slot.
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
        } catch (java.util.concurrent.TimeoutException e) {
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

    /* Test hooks: Caffeine's estimatedSize is only meaningful after maintenance ran. */

    long ackedUploadIdCacheSizeForTests() {
        ackedUploadIds.cleanUp();
        return ackedUploadIds.estimatedSize();
    }

    long downgradeCacheSizeForTests() {
        v2DowngradeDeadlines.cleanUp();
        return v2DowngradeDeadlines.estimatedSize();
    }
}
