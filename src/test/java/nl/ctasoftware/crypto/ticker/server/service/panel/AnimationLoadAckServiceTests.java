package nl.ctasoftware.crypto.ticker.server.service.panel;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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

    private IMqttClient mqttClient;
    private AnimationLoadAckService ackService;
    private IMqttMessageListener loadedListener;

    @BeforeEach
    void setUp() throws Exception {
        mqttClient = mock(IMqttClient.class);
        ackService = new AnimationLoadAckService(mqttClient);

        final ArgumentCaptor<IMqttMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(IMqttMessageListener.class);
        verify(mqttClient).subscribe(eq(AnimationLoadAckService.ANIM_LOADED_TOPIC_FILTER), listenerCaptor.capture());
        loadedListener = listenerCaptor.getValue();
    }

    private void deliverLoaded(final String serial, final int slot, final long uploadId) throws Exception {
        final MqttMessage message = new MqttMessage(new byte[]{
                'A', 'N', 'I', 'L',
                (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)
        });
        loadedListener.messageArrived(serial + "/anim/loaded", message);
    }

    @Test
    void correlatedAckRecordsSlotMappingAndCompletesWaiter() throws Exception {
        ackService.arm(SERIAL, 42L);
        deliverLoaded(SERIAL, 3, 42L);

        assertTrue(ackService.awaitLoaded(SERIAL, 42L, Duration.ofMillis(100)));
        assertEquals(OptionalLong.of(42L), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void ackWithoutWaiterIsNotRecorded() throws Exception {
        deliverLoaded(SERIAL, 3, 42L);

        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void staleAckIsNotRecorded() throws Exception {
        ackService.arm(SERIAL, 7L);
        deliverLoaded(SERIAL, 3, 42L);

        assertFalse(ackService.awaitLoaded(SERIAL, 7L, Duration.ofMillis(50)));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
    }

    @Test
    void slotsAreRecordedIndependently() throws Exception {
        ackService.arm(SERIAL, 5L);
        deliverLoaded(SERIAL, 2, 5L);
        assertTrue(ackService.awaitLoaded(SERIAL, 5L, Duration.ofMillis(100)));

        assertEquals(OptionalLong.of(5L), ackService.ackedUploadId(SERIAL, 2));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId(SERIAL, 3));
        assertEquals(OptionalLong.empty(), ackService.ackedUploadId("OTHERPANEL", 2));
    }

    @Test
    void overflowingTheBoundClearsEarlierEntries() throws Exception {
        for (int i = 0; i <= AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES + 5; i++) {
            final String serial = "PANEL-" + i;
            ackService.arm(serial, 1000L + i);
            deliverLoaded(serial, 0, 1000L + i);
        }

        assertEquals(OptionalLong.empty(), ackService.ackedUploadId("PANEL-0", 0));
        final int last = AnimationLoadAckService.MAX_ACKED_SLOT_ENTRIES + 5;
        assertEquals(OptionalLong.of(1000L + last), ackService.ackedUploadId("PANEL-" + last, 0));
    }
}
