package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * Content-hash slot caching (plan §4 1b) and protocol v2 compression/fallback (plan §5) in
 * the rotation pipeline, driven end-to-end through {@code PanelRotationJob.execute} with a
 * simulated panel behind a mocked MQTT transport: the sim parses v1 and v2 ANIM/ANIF/ANIP
 * publishes, persists per-slot uploadIds, and answers ANIL acks the way the firmware does
 * (silent on id/slot mismatch, so the server's 2 s play-ack timeout must fall back to the
 * inline upload; and with {@code v2Capable = false} it drops length-mismatched v2 ANIFs
 * like old firmware, so the upload-ack timeout must fire the codec-0 RAW fallback).
 */
class PanelRotationJobHashSkipTests {

    private static final String SERIAL = "HASHTESTP1";
    private static final int FRAME_DELAY_MS = 50;
    private static final int V1_ANIF_LENGTH = 4102;

    private record Pub(String topic, byte[] payload, int qos, boolean retained) {}

    /** Firmware stand-in: records publishes, persists slot ids, acks like main.cpp does. */
    private static final class PanelSim {
        final Map<Integer, Long> persisted = new ConcurrentHashMap<>();
        volatile boolean ackUploads = false;
        volatile boolean ackPlays = false;
        volatile boolean v2Capable = true;
        final List<Pub> published = new ArrayList<>();
        final Map<Integer, Integer> frameFlagsByIdx = new LinkedHashMap<>();
        final Map<Integer, byte[]> decodedByIdx = new LinkedHashMap<>();

        private MqttTransport.MqttMessageListener loadedListener;
        private int pendingSlot = -1;
        private long pendingUploadId;
        private int pendingFrameCount;
        private final Set<Integer> receivedFrames = new HashSet<>();

        void wire(final MqttTransport mqttTransport, final MqttTransport.MqttMessageListener listener) {
            this.loadedListener = listener;
            doAnswer(invocation -> {
                publish(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3));
                return null;
            }).when(mqttTransport).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());
            // The /anim/* path goes through the transport's async windowed publish.
            doAnswer(invocation -> {
                final String topic = invocation.getArgument(0);
                final byte[] payload = invocation.getArgument(1);
                final int qos = invocation.getArgument(2);
                final boolean retained = invocation.getArgument(3);
                publish(topic, payload, qos, retained);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }).when(mqttTransport).publishAsync(anyString(), any(byte[].class), anyInt(), anyBoolean());
        }

        private void publish(final String topic, final byte[] payload, final int qos, final boolean retained) throws Exception {
            published.add(new Pub(topic, payload, qos, retained));
            if (topic.endsWith("/anim/start")) {
                pendingSlot = payload[13] & 0xFF;
                pendingUploadId = u32le(payload, 8);
                pendingFrameCount = (payload[4] & 0xFF) | (payload[5] & 0xFF) << 8;
                receivedFrames.clear();
            } else if (topic.endsWith("/anim/frame")) {
                final int idx = (payload[4] & 0xFF) | (payload[5] & 0xFF) << 8;
                if (payload.length != V1_ANIF_LENGTH && !v2Capable) {
                    return; // old firmware ignores v2 ANIFs on length
                }
                if (payload.length == V1_ANIF_LENGTH) {
                    frameFlagsByIdx.put(idx, 0);
                    decodedByIdx.put(idx, Arrays.copyOfRange(payload, 6, payload.length));
                } else {
                    final int frameFlags = payload[6] & 0xFF;
                    final byte[] body = Arrays.copyOfRange(payload, 7, payload.length);
                    frameFlagsByIdx.put(idx, frameFlags);
                    decodedByIdx.put(idx, frameFlags == AnimationFrameCodec.FRAME_FLAG_PAL_RLE
                            ? AnimationFrameCodec.decodePalRle(body) : body);
                }
                receivedFrames.add(idx);
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
        }

        private void fireLoaded(final int slot, final long uploadId) {
            loadedListener.messageArrived(SERIAL + "/anim/loaded", new byte[]{
                    'A', 'N', 'I', 'L',
                    (byte) slot,
                    (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)
            });
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

    private MqttTransport mqttTransport;
    private JobScheduler jobScheduler;
    private RotationStateService rotationStateService;
    private AnimationLoadAckService ackService;
    private MqttTransport.MqttMessageListener loadedListener;
    private ImageService imageService;
    private PanelRepository panelRepository;
    private Px75PanelConfigService panelConfigService;
    private PanelRotationJob job;
    @SuppressWarnings("unchecked")
    private final FrameScreenService<AnimationScreenConfig> screenService = mock(FrameScreenService.class);
    private final PanelSim panel = new PanelSim();
    private final Map<BufferedImage, byte[]> frameBytes = new HashMap<>();

    @BeforeAll
    static void createScratchDir() throws Exception {
        Files.createDirectories(Path.of(PanelRotationControl.GENERATED_IMAGES_DIR));
    }

    @AfterAll
    static void deleteScratchFile() throws Exception {
        Files.deleteIfExists(Path.of(PanelRotationControl.GENERATED_IMAGES_DIR, SERIAL + ".png"));
    }

    @BeforeEach
    void setUp() {
        mqttTransport = mock(MqttTransport.class);
        rotationStateService = new RotationStateService();
        jobScheduler = mock(JobScheduler.class);
        ackService = new AnimationLoadAckService(mqttTransport);

        final ArgumentCaptor<MqttTransport.MqttMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(MqttTransport.MqttMessageListener.class);
        verify(mqttTransport).subscribe(anyString(), listenerCaptor.capture());
        loadedListener = listenerCaptor.getValue();
        panel.wire(mqttTransport, loadedListener);

        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0)))
                .thenAnswer(inv -> frameBytes.get(inv.getArgument(0)));
        when(screenService.getScreenType()).thenReturn(ScreenType.ANIMATION);

        panelRepository = mock(PanelRepository.class);
        when(panelRepository.findBySerialIgnoreCase(SERIAL)).thenReturn(java.util.Optional.of(
                new Px75Panel(1L, 1L, SERIAL, "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32)));
        panelConfigService = mock(Px75PanelConfigService.class);
        when(panelConfigService.getPanelConfig(1L)).thenAnswer(
                invocation -> new Px75PanelConfig(1L, jobConfigs));

        job = newJob();
    }

    private List<nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig> jobConfigs;

    private BufferedImage frame(final byte[] bytes) {
        final BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        frameBytes.put(image, bytes);
        return image;
    }

    private static byte[] flatFrame(final int color565) {
        final byte[] payload = new byte[AnimationFrameCodec.FRAME_BYTES];
        for (int i = 0; i < AnimationFrameCodec.FRAME_PIXELS; i++) {
            payload[2 * i] = (byte) color565;
            payload[2 * i + 1] = (byte) (color565 >> 8);
        }
        return payload;
    }

    private static byte[] noisyFrame(final long seed) {
        final byte[] payload = new byte[AnimationFrameCodec.FRAME_BYTES];
        long state = seed;
        for (int i = 0; i < payload.length; i += 2) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            final int color = (int) ((state >>> 33) & 0xFFFF);
            payload[i] = (byte) color;
            payload[i + 1] = (byte) (color >> 8);
        }
        return payload;
    }

    private void stubStream(final List<BufferedImage> frames) {
        when(screenService.renderFrameStream(any(AnimationScreenConfig.class)))
                .thenReturn(new FrameScreenService.FrameStream(frames, FRAME_DELAY_MS));
    }

    /** Runs one slot, returning the successor's admission id (the mocked scheduler never runs it). */
    private String runSlot(final String jobId) {
        job.execute(SERIAL, jobId);
        return rotationStateService.stateFor(SERIAL).allowedJobId.get();
    }

    private AnimationScreenConfig animConfig() {
        return new AnimationScreenConfig(ScreenType.ANIMATION, 1, FRAME_DELAY_MS, List.of("f1", "f2"));
    }

    private void seedAckedMap(final int slot, final long uploadId) {
        ackService.arm(SERIAL, uploadId);
        loadedListener.messageArrived(SERIAL + "/anim/loaded", new byte[]{
                'A', 'N', 'I', 'L', (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)});
    }

    @Test
    void mapMissUploadsInlineWithContentHashIdAndRecordsAck() {
        final byte[] frame0 = flatFrame(0x1111);
        final byte[] frame1 = flatFrame(0x2222);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        panel.ackUploads = true;
        runSlot(rotationStateService.kick(SERIAL));

        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertEquals(1, starts.size(), "map miss must upload");
        final byte[] anim = starts.getFirst().payload();
        assertEquals(14, anim.length);
        assertEquals("ANIM", new String(anim, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(2, (anim[4] & 0xFF) | (anim[5] & 0xFF) << 8);
        assertEquals(FRAME_DELAY_MS, (anim[6] & 0xFF) | (anim[7] & 0xFF) << 8);
        assertEquals(hash, PanelSim.u32le(anim, 8), "ANIM must carry the content hash as uploadId");
        assertEquals(0, anim[12] & AnimationTransport.ANIM_FLAG_STAGE_ONLY, "inline upload is not stage-only");
        assertEquals(AnimationTransport.ANIM_CODEC_PAL_RLE, anim[12] & AnimationTransport.ANIM_CODEC_MASK,
                "fresh upload defaults to the v2 codec");

        final List<Pub> frames = panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC);
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).payload()[4]);
        assertEquals(1, frames.get(1).payload()[4]);
        assertEquals(7 + AnimationFrameCodec.PALETTE_BYTES + 2 * AnimationFrameCodec.FRAME_ROWS,
                frames.get(0).payload().length, "flat frame encodes to one run per row");
        assertArrayEquals(frame0, panel.decodedByIdx.get(0), "panel decodes the RLE frame back to the payload");

        assertEquals(1, panel.pubs("").size(), "playbackStarted must clear the retained base image");
        assertEquals(OptionalLong.of(hash), ackService.ackedUploadId(SERIAL, 0));
    }

    @Test
    void mapHitSendsAnipOnly() {
        final byte[] frame0 = flatFrame(0x1111);
        final byte[] frame1 = flatFrame(0x2222);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        seedAckedMap(0, hash);
        panel.persisted.put(0, hash); // panel still holds the content
        panel.ackPlays = true;

        runSlot(rotationStateService.kick(SERIAL));

        assertEquals(0, panel.pubs(AnimationTransport.ANIM_START_TOPIC).size(), "map hit must skip ANIM/ANIF");
        assertEquals(0, panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).size(), "map hit must skip ANIM/ANIF");
        final List<Pub> plays = panel.pubs(AnimationTransport.ANIM_PLAY_TOPIC);
        assertEquals(1, plays.size());
        final byte[] anip = plays.getFirst().payload();
        assertEquals(9, anip.length);
        assertEquals(0, anip[4] & 0xFF);
        assertEquals(hash, PanelSim.u32le(anip, 5));
        assertEquals(1, panel.pubs("").size(), "acked play must clear the retained base image");
    }

    @Test
    void mapHitWithSilentPanelFallsBackToInlineUpload() {
        final byte[] frame0 = flatFrame(0x3333);
        final byte[] frame1 = flatFrame(0x4444);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        seedAckedMap(0, hash);
        // panel.persisted stays empty: firmware would be silent on the id mismatch

        panel.ackUploads = true;
        runSlot(rotationStateService.kick(SERIAL)); // play-ack timeout (2 s) inside

        assertEquals(1, panel.pubs(AnimationTransport.ANIM_PLAY_TOPIC).size(), "map hit must still try ANIP first");
        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertEquals(1, starts.size(), "silent panel must trigger the inline fallback upload");
        assertEquals(hash, PanelSim.u32le(starts.getFirst().payload(), 8),
                "re-upload carries the same content hash id so the panel re-persists it");
        assertEquals(2, panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).size());
        assertEquals(1, panel.pubs("").size(), "fallback upload must start playback");
        assertEquals(OptionalLong.of(hash), ackService.ackedUploadId(SERIAL, 0));
    }

    @Test
    void stagingSkipsUploadWhenSlotStillHoldsContent() {
        final AnimationScreenConfig configA = animConfig();
        final AnimationScreenConfig configB = animConfig();
        jobConfigs = List.of(configA, configB);
        final byte[] bytesA0 = flatFrame(0x0101);
        final byte[] bytesA1 = flatFrame(0x0202);
        final byte[] bytesB0 = flatFrame(0x0303);
        final byte[] bytesB1 = flatFrame(0x0404);
        when(screenService.renderFrameStream(any(AnimationScreenConfig.class))).thenAnswer(inv ->
                inv.getArgument(0) == configA
                        ? new FrameScreenService.FrameStream(List.of(frame(bytesA0), frame(bytesA1)), FRAME_DELAY_MS)
                        : new FrameScreenService.FrameStream(List.of(frame(bytesB0), frame(bytesB1)), FRAME_DELAY_MS));

        // Panel already acked B's exact staged content for slot 1 in an earlier cycle.
        final long hashB = UploadIdHasher.contentHash(2, FRAME_DELAY_MS,
                AnimationTransport.ANIM_FLAG_STAGE_ONLY, List.of(bytesB0, bytesB1));
        seedAckedMap(1, hashB);
        panel.persisted.put(1, hashB);
        panel.ackUploads = true;
        panel.ackPlays = true;

        String jobId = rotationStateService.kick(SERIAL);
        jobId = runSlot(jobId); // A's boundary: nothing staged for it -> inline upload (slot 0)

        assertEquals(1, panel.pubs(AnimationTransport.ANIM_START_TOPIC).size(), "only A's inline upload");
        assertEquals(0, panel.pubs(AnimationTransport.ANIM_PLAY_TOPIC).size());
        assertEquals(2, panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).size());

        jobId = runSlot(jobId); // B's boundary: hash-cached staged state -> ANIP-only commit

        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertTrue(starts.stream().noneMatch(p -> (p.payload()[13] & 0xFF) == 1),
                "B's content was never (re-)uploaded");
        final List<Pub> plays = panel.pubs(AnimationTransport.ANIM_PLAY_TOPIC);
        assertEquals(1, plays.size());
        assertEquals(1, plays.getFirst().payload()[4] & 0xFF);
        assertEquals(hashB, PanelSim.u32le(plays.getFirst().payload(), 5));
        assertEquals(2, panel.pubs("").size(), "both boundaries started playback");
    }

    @Test
    void v2UploadSendsCodecBitsAndPerFrameFlags() {
        final byte[] flat = flatFrame(0x1234);
        final byte[] noisy = noisyFrame(42L);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(flat), frame(noisy)));

        panel.ackUploads = true;
        runSlot(rotationStateService.kick(SERIAL));

        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertEquals(1, starts.size());
        final byte[] anim = starts.getFirst().payload();
        assertEquals(AnimationTransport.ANIM_CODEC_PAL_RLE, anim[12] & AnimationTransport.ANIM_CODEC_MASK,
                "ANIM flags must announce the PAL_RLE codec");
        assertEquals(0, anim[12] & AnimationTransport.ANIM_FLAG_STAGE_ONLY);

        assertEquals(AnimationFrameCodec.FRAME_FLAG_PAL_RLE, panel.frameFlagsByIdx.get(0),
                "flat frame takes the RLE body");
        assertEquals(AnimationFrameCodec.FRAME_FLAG_RAW, panel.frameFlagsByIdx.get(1),
                "noisy frame exceeds 16 colors and stays a RAW body inside the v2 upload");
        assertEquals(7 + AnimationFrameCodec.PALETTE_BYTES + 2 * AnimationFrameCodec.FRAME_ROWS,
                panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).get(0).payload().length);
        assertEquals(7 + AnimationFrameCodec.FRAME_BYTES,
                panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).get(1).payload().length);
        assertArrayEquals(flat, panel.decodedByIdx.get(0), "RLE frame roundtrips to the original payload");
        assertArrayEquals(noisy, panel.decodedByIdx.get(1), "RAW-bodied v2 frame roundtrips");
        assertEquals(1, panel.pubs("").size());
    }

    @Test
    void v2UploadAckTimeoutResendsRawWithSameUploadIdAndDowngrades() {
        final byte[] frame0 = flatFrame(0x1234);
        final byte[] frame1 = flatFrame(0x5678);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        panel.ackUploads = true;
        panel.v2Capable = false; // old firmware: drops v2 ANIFs on length, never acks them

        runSlot(rotationStateService.kick(SERIAL)); // upload-ack timeout (5.5 s) inside

        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertEquals(2, starts.size(), "timed-out v2 upload must be re-sent as RAW");
        assertEquals(AnimationTransport.ANIM_CODEC_PAL_RLE, starts.get(0).payload()[12] & AnimationTransport.ANIM_CODEC_MASK);
        assertEquals(AnimationTransport.ANIM_CODEC_RAW, starts.get(1).payload()[12] & AnimationTransport.ANIM_CODEC_MASK,
                "fallback re-send is codec 0");
        assertEquals(0, starts.get(1).payload()[12] & AnimationTransport.ANIM_FLAG_STAGE_ONLY);
        assertEquals(PanelSim.u32le(starts.get(0).payload(), 8), PanelSim.u32le(starts.get(1).payload(), 8),
                "RAW re-send must reuse the codec-blind content hash id");
        assertEquals(hash, PanelSim.u32le(starts.get(1).payload(), 8));

        final List<Pub> frames = panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC);
        assertEquals(4, frames.size(), "two v2 frames (dropped by the panel) then two v1 frames");
        assertTrue(frames.get(0).payload().length != V1_ANIF_LENGTH && frames.get(1).payload().length != V1_ANIF_LENGTH);
        assertEquals(V1_ANIF_LENGTH, frames.get(2).payload().length);
        assertEquals(V1_ANIF_LENGTH, frames.get(3).payload().length);
        assertArrayEquals(frame0, panel.decodedByIdx.get(0), "the RAW frames carry the same content");

        assertTrue(ackService.prefersRaw(SERIAL), "panel must be in the downgrade window after the fallback");
        assertEquals(1, panel.pubs("").size(), "the RAW re-send must start playback");
        assertEquals(OptionalLong.of(hash), ackService.ackedUploadId(SERIAL, 0));
    }

    @Test
    void downgradedPanelSendsCodec0OnInlineAndStagedPaths() {
        ackService.markDowngraded(SERIAL);
        panel.ackUploads = true;

        final AnimationScreenConfig configA = animConfig();
        final AnimationScreenConfig configB = animConfig();
        jobConfigs = List.of(configA, configB);
        final byte[] bytesA0 = flatFrame(0x0A0A);
        final byte[] bytesA1 = flatFrame(0x0B0B);
        final byte[] bytesB0 = flatFrame(0x0C0C);
        final byte[] bytesB1 = flatFrame(0x0D0D);
        when(screenService.renderFrameStream(any(AnimationScreenConfig.class))).thenAnswer(inv ->
                inv.getArgument(0) == configA
                        ? new FrameScreenService.FrameStream(List.of(frame(bytesA0), frame(bytesA1)), FRAME_DELAY_MS)
                        : new FrameScreenService.FrameStream(List.of(frame(bytesB0), frame(bytesB1)), FRAME_DELAY_MS));

        runSlot(rotationStateService.kick(SERIAL)); // A inline + stages B

        final List<Pub> starts = panel.pubs(AnimationTransport.ANIM_START_TOPIC);
        assertEquals(2, starts.size());
        assertEquals(0, starts.get(0).payload()[12],
                "downgraded panel: inline boundary upload is pure v1 (no stage-only, no codec bits)");
        assertEquals(0, starts.get(0).payload()[13] & 0xFF);
        assertEquals(AnimationTransport.ANIM_FLAG_STAGE_ONLY, starts.get(1).payload()[12] & 0xFF,
                "downgraded panel: staged upload is v1 stage-only, no codec bits");
        assertEquals(1, starts.get(1).payload()[13] & 0xFF);
        assertTrue(panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).stream()
                        .allMatch(p -> p.payload().length == V1_ANIF_LENGTH),
                "every frame on the downgraded panel is v1 wire");
        assertEquals(4, panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).size());
    }

    @Test
    void hashSkipStillFiresAcrossV2ToRawTransition() {
        final byte[] frame0 = flatFrame(0x2345);
        final byte[] frame1 = flatFrame(0x6789);
        jobConfigs = List.of(animConfig());
        stubStream(List.of(frame(frame0), frame(frame1)));
        final long hash = UploadIdHasher.contentHash(2, FRAME_DELAY_MS, 0, List.of(frame0, frame1));

        panel.ackUploads = true;
        panel.ackPlays = true;
        String jobId = rotationStateService.kick(SERIAL);
        jobId = runSlot(jobId); // v2 inline upload, acked by the v2-capable panel

        assertEquals(1, panel.pubs(AnimationTransport.ANIM_START_TOPIC).size());
        assertEquals(AnimationTransport.ANIM_CODEC_PAL_RLE,
                panel.pubs(AnimationTransport.ANIM_START_TOPIC).getFirst().payload()[12] & AnimationTransport.ANIM_CODEC_MASK);

        ackService.markDowngraded(SERIAL); // fleet went mixed: uploads would be RAW from now on
        runSlot(jobId); // same content next cycle

        assertEquals(1, panel.pubs(AnimationTransport.ANIM_START_TOPIC).size(),
                "hash-skip must not re-upload just because the codec choice changed");
        assertEquals(2, panel.pubs(AnimationTransport.ANIM_FRAME_TOPIC).size());
        final List<Pub> plays = panel.pubs(AnimationTransport.ANIM_PLAY_TOPIC);
        assertEquals(1, plays.size(), "the boundary is an ANIP-only commit");
        assertEquals(hash, PanelSim.u32le(plays.getFirst().payload(), 5),
                "the same codec-blind id skips across the v2->RAW transition");
        assertEquals(2, panel.pubs("").size(), "both cycles started playback");
    }

    /* ------------------------------ wiring ------------------------------ */

    private PanelRotationJob newJob() {
        final ScreenServices screenServices = new ScreenServices(new ArrayList<>(List.of(screenService)));
        final RotationPlanner planner = new RotationPlanner(config -> config);
        final AnimationTransport transport = new AnimationTransport(
                imageService, ackService, screenServices, rotationStateService, mqttTransport, false);
        final PreviewStreamer previewStreamer = new PreviewStreamer(
                imageService, mock(ImageBroadcasterService.class), rotationStateService);
        final CommandPublisher commandPublisher = new CommandPublisher(mqttTransport, rotationStateService, previewStreamer);
        return new PanelRotationJob(panelRepository, panelConfigService, screenServices, planner,
                transport, commandPublisher, previewStreamer, rotationStateService,
                jobScheduler, mqttTransport, imageService, false);
    }
}
