package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Content-hash slot caching in {@code PanelScreenJob} (plan §4 1b), driven end-to-end through
 * {@code run()} with a simulated panel behind a mocked MQTT client: the sim parses the
 * ANIM/ANIF/ANIP publishes, persists per-slot uploadIds, and answers ANIL acks the way the
 * firmware does (silent on id/slot mismatch, so the server's 2 s play-ack timeout must fall
 * back to the inline upload).
 */
class PanelScreenJobHashSkipTests {

    private static final String SERIAL = "HASHTESTP1";
    private static final int FRAME_DELAY_MS = 50;

    private record Pub(String topic, byte[] payload, int qos, boolean retained) {}

    /** Firmware stand-in: records publishes, persists slot ids, acks like main.cpp will. */
    private static final class PanelSim {
        final Map<Integer, Long> persisted = new ConcurrentHashMap<>();
        volatile boolean ackUploads = false;
        volatile boolean ackPlays = false;
        final List<Pub> published = new ArrayList<>();

        private IMqttMessageListener loadedListener;
        private int pendingSlot = -1;
        private long pendingUploadId;
        private int pendingFrameCount;
        private final Set<Integer> receivedFrames = new HashSet<>();

        void wire(final IMqttClient mqttClient, final IMqttMessageListener listener) throws org.eclipse.paho.client.mqttv3.MqttException {
            this.loadedListener = listener;
            doAnswer(invocation -> {
                final String topic = invocation.getArgument(0);
                final byte[] payload = invocation.getArgument(1);
                published.add(new Pub(topic, payload, invocation.getArgument(2), invocation.getArgument(3)));
                if (topic.endsWith("/anim/start")) {
                    pendingSlot = payload[13] & 0xFF;
                    pendingUploadId = u32le(payload, 8);
                    pendingFrameCount = (payload[4] & 0xFF) | (payload[5] & 0xFF) << 8;
                    receivedFrames.clear();
                } else if (topic.endsWith("/anim/frame")) {
                    receivedFrames.add((payload[4] & 0xFF) | (payload[5] & 0xFF) << 8);
                    if (ackUploads && receivedFrames.size() == pendingFrameCount) {
                        persisted.put(pendingSlot, pendingUploadId);
                        fireLoaded(pendingSlot, pendingUploadId);
                    }
                } else if (topic.endsWith("/anim/play")) {
                    final int slot = payload[4] & 0xFF;
                    final long uploadId = u32le(payload, 5);
                    if (ackPlays && persisted.getOrDefault(slot, -1L) == uploadId) {
                        fireLoaded(slot, uploadId);
                    }
                }
                return null;
            }).when(mqttClient).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());
        }

        private void fireLoaded(final int slot, final long uploadId) {
            try {
                loadedListener.messageArrived(SERIAL + "/anim/loaded", new MqttMessage(new byte[]{
                        'A', 'N', 'I', 'L',
                        (byte) slot,
                        (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)
                }));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        List<Pub> pubs(final String suffix) {
            return published.stream().filter(p -> p.topic().equals(SERIAL + suffix)).toList();
        }

        static long u32le(final byte[] payload, final int offset) {
            return (payload[offset] & 0xFFL)
                    | (payload[offset + 1] & 0xFFL) << 8
                    | (payload[offset + 2] & 0xFFL) << 16
                    | (payload[offset + 3] & 0xFFL) << 24;
        }
    }

    private IMqttClient mqttClient;
    private AnimationLoadAckService ackService;
    private IMqttMessageListener loadedListener;
    private ImageService imageService;
    @SuppressWarnings("unchecked")
    private final FrameScreenService<AnimationScreenConfig> screenService = mock(FrameScreenService.class);
    private final PanelSim panel = new PanelSim();
    private final Map<BufferedImage, byte[]> frameBytes = new HashMap<>();

    @BeforeAll
    static void createScratchDir() throws Exception {
        Files.createDirectories(Path.of("generated_images"));
    }

    @AfterAll
    static void deleteScratchFile() throws Exception {
        Files.deleteIfExists(Path.of("generated_images", SERIAL + ".png"));
    }

    @BeforeEach
    void setUp() throws Exception {
        mqttClient = mock(IMqttClient.class);
        ackService = new AnimationLoadAckService(mqttClient);

        final ArgumentCaptor<IMqttMessageListener> listenerCaptor = ArgumentCaptor.forClass(IMqttMessageListener.class);
        verify(mqttClient).subscribe(anyString(), listenerCaptor.capture());
        loadedListener = listenerCaptor.getValue();
        panel.wire(mqttClient, loadedListener);

        imageService = mock(ImageService.class);
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0)))
                .thenAnswer(inv -> frameBytes.get(inv.getArgument(0)));
        when(screenService.getScreenType()).thenReturn(ScreenType.ANIMATION);
    }

    private BufferedImage frame(final byte[] bytes) {
        final BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        frameBytes.put(image, bytes);
        return image;
    }

    private void stubStream(final List<BufferedImage> frames) {
        when(screenService.renderFrameStream(any(AnimationScreenConfig.class)))
                .thenReturn(new FrameScreenService.FrameStream(frames, FRAME_DELAY_MS));
    }

    private PanelScreenJob newJob(final AnimationScreenConfig... configs) {
        final Px75Panel px75Panel = mock(Px75Panel.class);
        when(px75Panel.getSerial()).thenReturn(SERIAL);
        final Px75PanelConfig panelConfig = new Px75PanelConfig(1L, List.of(configs));

        final List<nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService<? extends nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig>> services = new ArrayList<>();
        services.add(screenService);

        final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();
        return new PanelScreenJob(px75Panel, panelConfig, services, imageService, mqttClient,
                mock(ImageBroadcasterService.class), ackService, previewGenerations);
    }

    private AnimationScreenConfig animConfig() {
        return new AnimationScreenConfig(ScreenType.ANIMATION, 1, FRAME_DELAY_MS, List.of("f1", "f2"));
    }

    private void seedAckedMap(final int slot, final long uploadId) throws Exception {
        ackService.arm(SERIAL, uploadId);
        loadedListener.messageArrived(SERIAL + "/anim/loaded", new MqttMessage(new byte[]{
                'A', 'N', 'I', 'L', (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)}));
    }

    @Test
    void mapMissUploadsInlineWithContentHashIdAndRecordsAck() throws Exception {
        final byte[] frame0 = {1, 2, 3, 4};
        final byte[] frame1 = {5, 6, 7, 8};
        final AnimationScreenConfig config = animConfig();
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        panel.ackUploads = true;
        newJob(config).run();

        final List<Pub> starts = panel.pubs(PanelScreenJob.ANIM_START_TOPIC);
        assertEquals(1, starts.size(), "map miss must upload");
        final byte[] anim = starts.getFirst().payload();
        assertEquals(14, anim.length);
        assertEquals("ANIM", new String(anim, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(2, (anim[4] & 0xFF) | (anim[5] & 0xFF) << 8);
        assertEquals(FRAME_DELAY_MS, (anim[6] & 0xFF) | (anim[7] & 0xFF) << 8);
        assertEquals(hash, PanelSim.u32le(anim, 8), "ANIM must carry the content hash as uploadId");
        assertEquals(0, anim[12], "inline upload is not stage-only");
        assertEquals(0, anim[13]);

        final List<Pub> frames = panel.pubs(PanelScreenJob.ANIM_FRAME_TOPIC);
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).payload()[4]);
        assertEquals(1, frames.get(1).payload()[4]);

        assertEquals(1, panel.pubs("").size(), "playbackStarted must clear the retained base image");
        assertEquals(OptionalLong.of(hash), ackService.ackedUploadId(SERIAL, 0));
    }

    @Test
    void mapHitSendsAnipOnly() throws Exception {
        final byte[] frame0 = {1, 2, 3, 4};
        final byte[] frame1 = {5, 6, 7, 8};
        final AnimationScreenConfig config = animConfig();
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        seedAckedMap(0, hash);
        panel.persisted.put(0, hash); // panel still holds the content
        panel.ackPlays = true;

        newJob(config).run();

        assertEquals(0, panel.pubs(PanelScreenJob.ANIM_START_TOPIC).size(), "map hit must skip ANIM/ANIF");
        assertEquals(0, panel.pubs(PanelScreenJob.ANIM_FRAME_TOPIC).size(), "map hit must skip ANIM/ANIF");
        final List<Pub> plays = panel.pubs(PanelScreenJob.ANIM_PLAY_TOPIC);
        assertEquals(1, plays.size());
        final byte[] anip = plays.getFirst().payload();
        assertEquals(9, anip.length);
        assertEquals(0, anip[4] & 0xFF);
        assertEquals(hash, PanelSim.u32le(anip, 5));
        assertEquals(1, panel.pubs("").size(), "acked play must clear the retained base image");
    }

    @Test
    void mapHitWithSilentPanelFallsBackToInlineUpload() throws Exception {
        final byte[] frame0 = {9, 9, 9, 9};
        final byte[] frame1 = {8, 8, 8, 8};
        final AnimationScreenConfig config = animConfig();
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        seedAckedMap(0, hash);
        // panel.persisted stays empty: firmware would be silent on the id mismatch

        panel.ackUploads = true;
        newJob(config).run(); // play-ack timeout (2 s) inside

        assertEquals(1, panel.pubs(PanelScreenJob.ANIM_PLAY_TOPIC).size(), "map hit must still try ANIP first");
        final List<Pub> starts = panel.pubs(PanelScreenJob.ANIM_START_TOPIC);
        assertEquals(1, starts.size(), "silent panel must trigger the inline fallback upload");
        assertEquals(hash, PanelSim.u32le(starts.getFirst().payload(), 8),
                "re-upload carries the same content hash id so the panel re-persists it");
        assertEquals(2, panel.pubs(PanelScreenJob.ANIM_FRAME_TOPIC).size());
        assertEquals(1, panel.pubs("").size(), "fallback upload must start playback");
        assertEquals(OptionalLong.of(hash), ackService.ackedUploadId(SERIAL, 0));
    }

    @Test
    void stagingSkipsUploadWhenSlotStillHoldsContent() throws Exception {
        final AnimationScreenConfig configA = animConfig();
        final AnimationScreenConfig configB = animConfig();
        final byte[] bytesA0 = {1, 1, 1, 1};
        final byte[] bytesA1 = {2, 2, 2, 2};
        final byte[] bytesB0 = {3, 3, 3, 3};
        final byte[] bytesB1 = {4, 4, 4, 4};
        when(screenService.renderFrameStream(any(AnimationScreenConfig.class))).thenAnswer(inv ->
                inv.getArgument(0) == configA
                        ? new FrameScreenService.FrameStream(List.of(frame(bytesA0), frame(bytesA1)), FRAME_DELAY_MS)
                        : new FrameScreenService.FrameStream(List.of(frame(bytesB0), frame(bytesB1)), FRAME_DELAY_MS));

        // Panel already acked B's exact staged content for slot 1 in an earlier cycle.
        final long hashB = UploadIdHasher.contentHash(2, FRAME_DELAY_MS,
                PanelScreenJob.ANIM_FLAG_STAGE_ONLY, List.of(bytesB0, bytesB1));
        seedAckedMap(1, hashB);
        panel.persisted.put(1, hashB);
        panel.ackUploads = true;
        panel.ackPlays = true;

        final PanelScreenJob job = newJob(configA, configB);
        job.run(); // A's boundary: nothing staged for it -> inline upload (slot 0)

        assertEquals(1, panel.pubs(PanelScreenJob.ANIM_START_TOPIC).size(), "only A's inline upload");
        assertEquals(0, panel.pubs(PanelScreenJob.ANIM_PLAY_TOPIC).size());
        assertEquals(2, panel.pubs(PanelScreenJob.ANIM_FRAME_TOPIC).size());

        job.run(); // B's boundary: hash-cached staged state -> ANIP-only commit

        final List<Pub> starts = panel.pubs(PanelScreenJob.ANIM_START_TOPIC);
        assertTrue(starts.stream().noneMatch(p -> (p.payload()[13] & 0xFF) == 1),
                "B's content was never (re-)uploaded");
        final List<Pub> plays = panel.pubs(PanelScreenJob.ANIM_PLAY_TOPIC);
        assertEquals(1, plays.size());
        assertEquals(1, plays.getFirst().payload()[4] & 0xFF);
        assertEquals(hashB, PanelSim.u32le(plays.getFirst().payload(), 5));
        assertEquals(2, panel.pubs("").size(), "both boundaries started playback");
    }
}
