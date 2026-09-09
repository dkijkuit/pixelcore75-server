package nl.ctasoftware.crypto.ticker.server.service.panel;

import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnimationLoadAckServiceTests {

    private static final String SERIAL = "TESTPANEL1";

    private MqttTransport mqttTransport;
    private AnimationLoadAckService ackService;
    private MqttTransport.MqttMessageListener loadedListener;

    @BeforeEach
    void setUp() {
        mqttTransport = mock(MqttTransport.class);
        ackService = new AnimationLoadAckService(mqttTransport);

        final ArgumentCaptor<MqttTransport.MqttMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(MqttTransport.MqttMessageListener.class);
        verify(mqttTransport).subscribe(eq(AnimationLoadAckService.ANIM_LOADED_TOPIC_FILTER), listenerCaptor.capture());
        loadedListener = listenerCaptor.getValue();
    }

    private void deliverLoaded(final String serial, final int slot, final long uploadId) {
        loadedListener.messageArrived(serial + "/anim/loaded", new byte[]{
                'A', 'N', 'I', 'L',
                (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)
        });
    }

    @Test
    void correlatedAckRecordsSlotMappingAndCompletesWaiter() {
        ackService.arm(SERIAL, 42L);
        deliverLoaded(SERIAL, 3, 42L);

        assertTrue(ackService.awaitLoaded(SERIAL, 42L, Duration.ofMillis(100)));
        assertEquals(OptionalLong.of(42L), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void ackWithoutWaiterIsNotRecorded() {
        deliverLoaded(SERIAL, 3, 42L);

        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void staleAckIsNotRecorded() {
        ackService.arm(SERIAL, 7L);
        deliverLoaded(SERIAL, 3, 42L);

        assertFalse(ackService.awaitLoaded(SERIAL, 7L, Duration.ofMillis(50)));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void slotsAreRecordedIndependently() {
        ackService.arm(SERIAL, 5L);
        deliverLoaded(SERIAL, 2, 5L);
        assertTrue(ackService.awaitLoaded(SERIAL, 5L, Duration.ofMillis(100)));

        assertEquals(OptionalLong.of(5L), ackService.ackedUploadId(SERIAL, 2));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId("OTHERPANEL", 2));
    }

    @Test
    void ackedSlotCacheIsSizeBoundedInsteadOfClearingWholesale() {
        // Fill beyond the bound: Caffeine evicts entries (oldest first) instead of the old
        // wholesale clear, so unaffected panels keep their cached content hashes.
        final int total = AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES + 200;
        for (int i = 0; i < total; i++) {
            final String serial = "PANEL-" + i;
            ackService.arm(serial, 1000L + i);
            deliverLoaded(serial, 0, 1000L + i);
        }

        assertTrue(ackService.ackedUploadIdCacheSizeForTests() <= AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES,
                "the cache must stay bounded");
        // The most recent entries must survive an eviction storm.
        assertEquals(OptionalLong.of(1000L + total - 1), ackService.ackedUploadId("PANEL-" + (total - 1), 0));
    }

    @Test
    void downgradeWindowMakesPanelPreferRawUntilItExpires() {
        assertFalse(ackService.prefersRaw(SERIAL), "never-downgraded panels get v2 uploads");
        ackService.markDowngraded(SERIAL, Duration.ofMillis(20));
        assertTrue(ackService.prefersRaw(SERIAL));
        assertFalse(ackService.prefersRaw("OTHERPANEL"), "the downgrade is per-panel");

        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(ackService.prefersRaw(SERIAL), "after the window the server re-probes v2");
    }

    @Test
    void downgradeCacheIsSizeBoundedInsteadOfClearingWholesale() {
        final int total = AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES + 200;
        for (int i = 0; i < total; i++) {
            ackService.markDowngraded("PANEL-" + i, Duration.ofHours(1));
        }

        assertTrue(ackService.downgradeCacheSizeForTests() <= AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES,
                "the cache must stay bounded");
        assertTrue(ackService.prefersRaw("PANEL-" + (total - 1)),
                "the most recently downgraded panel stays on RAW");
    }
}
