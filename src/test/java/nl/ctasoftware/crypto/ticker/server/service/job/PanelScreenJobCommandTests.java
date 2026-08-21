package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdOpcode;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.clock.ClockScreenService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code PanelScreenJob}'s ACMD command branch (plan §6), docker-free against a mocked
 * MQTT client: flag ON + {@link CommandScreenService} → exactly one QoS-0 not-retained
 * {@code <serial>/cmd} publish of a parseable batch, the retained base image cleared,
 * no ANIM traffic, staging skipped for command screens. Flag OFF (or a non-command
 * screen) → the frame/static path byte-for-byte and zero {@code /cmd} publishes.
 */
class PanelScreenJobCommandTests {

    private static final String SERIAL = "CMDTESTP1";

    private record Pub(String topic, byte[] payload, int qos, boolean retained) {}

    private IMqttClient mqttClient;
    private AnimationLoadAckService ackService;
    private ImageService imageService;
    private ImageBroadcasterService broadcaster;
    private IMqttMessageListener loadedListener;
    private final List<Pub> pubs = new ArrayList<>();
    private final Map<BufferedImage, byte[]> frameBytes = new HashMap<>();

    /** Firmware stand-in for the frame-path test: acks uploads like main.cpp does. */
    private boolean ackUploads;
    private int pendingSlot = -1;
    private long pendingUploadId;
    private int pendingFrameCount;
    private int receivedFrames;

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

        final ArgumentCaptor<IMqttMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(IMqttMessageListener.class);
        verify(mqttClient).subscribe(anyString(), listenerCaptor.capture());
        loadedListener = listenerCaptor.getValue();

        doAnswer(invocation -> {
            final String topic = invocation.getArgument(0);
            final byte[] payload = invocation.getArgument(1);
            pubs.add(new Pub(topic, payload, invocation.getArgument(2), invocation.getArgument(3)));
            if (topic.endsWith("/anim/start")) {
                pendingSlot = payload[13] & 0xFF;
                pendingUploadId = u32le(payload, 8);
                pendingFrameCount = (payload[4] & 0xFF) | (payload[5] & 0xFF) << 8;
                receivedFrames = 0;
            } else if (topic.endsWith("/anim/frame")) {
                receivedFrames++;
                if (ackUploads && receivedFrames == pendingFrameCount) {
                    fireLoaded(pendingSlot, pendingUploadId);
                }
            }
            return null;
        }).when(mqttClient).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());

        doAnswer(invocation -> {
            final MqttMessage message = invocation.getArgument(1);
            pubs.add(new Pub(invocation.getArgument(0), message.getPayload(),
                    message.getQos(), message.isRetained()));
            return null;
        }).when(mqttClient).publish(anyString(), any(MqttMessage.class));

        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0)))
                .thenAnswer(inv -> frameBytes.computeIfAbsent(inv.getArgument(0),
                        img -> new byte[AnimationFrameCodec.FRAME_BYTES]));
        broadcaster = mock(ImageBroadcasterService.class);
    }

    /* ------------------------------------------------------------------ */

    /**
     * A clock screen that can render both ways (command batch = CLS + SWEEP). Extends
     * {@code ClockScreenService} because the static-path switch casts to the concrete
     * service classes.
     */
    private static final class StubClockService extends ClockScreenService {
        StubClockService() {
            super(new PaintToolsService(null, null, null), null);
        }

        @Override
        public Optional<BufferedImage> renderScreen(final ClockScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public byte[] renderCommandBatch(final ClockScreenConfig screenConfig) {
            return CommandBatch.builder().cls(0x0000).sweep(32, 16, 10, 0x07E0, 90).build();
        }
    }

    /** A radar aircraft screen: frame stream (stage-ahead) AND a command batch. */
    private static final class StubRadarService
            implements FrameScreenService<AircraftScreenConfig>, CommandScreenService<AircraftScreenConfig> {
        @Override
        public ScreenType getScreenType() {
            return ScreenType.NEARBY_AIRCRAFT;
        }

        @Override
        public Optional<BufferedImage> renderScreen(final AircraftScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final AircraftScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB),
                    new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public byte[] renderCommandBatch(final AircraftScreenConfig screenConfig) {
            return CommandBatch.builder().cls(0x0000).pix(16, 16, 0xFFFF).build();
        }
    }

    /* ------------------------------------------------------------------ */

    @Test
    void flagOnPublishesCommandBatchToCmdTopicAndClearsRetainedBase() throws Exception {
        newJob(true, new StubClockService(), clockConfig()).run();

        assertEquals(2, pubs.size(), "exactly the retained clear + the command batch");
        assertEquals(SERIAL, pubs.get(0).topic());
        assertEquals(0, pubs.get(0).payload().length, "retained base image cleared with an empty publish");
        assertEquals(1, pubs.get(0).qos());
        assertTrue(pubs.get(0).retained());
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic());
        assertEquals(0, pubs.get(1).qos(), "ACMD is QoS 0 fire-and-forget");
        assertFalse(pubs.get(1).retained(), "ACMD is not retained");

        final AcmdParser.Parsed parsed = AcmdParser.parse(pubs.get(1).payload());
        assertFalse(parsed.truncated());
        assertEquals(2, parsed.commands().size());
        assertEquals(AcmdOpcode.CLS, pubs.get(1).payload()[7] & 0xFF, "first command opcode");
        assertEquals(0x0000, ((AcmdCommand.Cls) parsed.commands().getFirst()).color());
        assertEquals(90, ((AcmdCommand.Sweep) parsed.commands().get(1)).speedDegPerSec());
        verify(broadcaster, atLeastOnce()).updateLatest(eq(SERIAL), any(BufferedImage.class));
    }

    @Test
    void flagOffUsesStaticFramePathAndNeverTouchesCmd() throws Exception {
        newJob(false, new StubClockService(), clockConfig()).run();

        assertEquals(1, pubs.size(), "one static retained frame publish, nothing else");
        assertEquals(SERIAL, pubs.getFirst().topic());
        assertTrue(pubs.getFirst().retained());
        assertTrue(pubs.getFirst().payload().length > 0, "static frame payload");
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "flag OFF must never publish to /cmd");
    }

    @Test
    void flagOnWithFrameScreenKeepsFramePathByteForByte() throws Exception {
        @SuppressWarnings("unchecked")
        final FrameScreenService<AnimationScreenConfig> animationService = mock(FrameScreenService.class);
        when(animationService.getScreenType()).thenReturn(ScreenType.ANIMATION);
        when(animationService.renderFrameStream(any(AnimationScreenConfig.class))).thenReturn(
                new FrameScreenService.FrameStream(
                        List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB),
                                new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)),
                        50));
        ackUploads = true;

        newJob(true, animationService,
                new AnimationScreenConfig(ScreenType.ANIMATION, 1, 50, List.of("f1", "f2"))).run();

        assertEquals(1, pubs(p -> p.topic().equals(SERIAL + PanelScreenJob.ANIM_START_TOPIC)).size(),
                "flag ON on a non-command screen must upload the animation as before");
        assertEquals(2, pubs(p -> p.topic().equals(SERIAL + PanelScreenJob.ANIM_FRAME_TOPIC)).size());
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "a screen without CommandScreenService never reaches /cmd");
    }

    @Test
    void stagingSkipsFrameUploadWhenNextScreenRendersViaCommands() throws Exception {
        final PanelScreenJob job = newJob(true, new StubClockService(), new StubRadarService(),
                clockConfig(), radarConfig());

        job.run(); // clock boundary: radar would normally stage its frame stream here

        assertEquals(2, pubs.size(), "retained clear + clock batch; no staging upload for a command-rendered next screen");
        assertEquals(SERIAL, pubs.get(0).topic());
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic());

        job.run(); // radar boundary: command path, not the staged inline upload

        assertEquals(4, pubs.size(), "radar boundary publishes clear + batch, nothing else");
        assertEquals(SERIAL + "/cmd", pubs.get(3).topic());
        assertEquals(0, pubs(p -> p.topic().endsWith(PanelScreenJob.ANIM_START_TOPIC)).size(),
                "no ANIM upload anywhere in the rotation");
    }

    /* ------------------------------------------------------------------ */

    private List<Pub> pubs(final java.util.function.Predicate<Pub> filter) {
        return pubs.stream().filter(filter).toList();
    }

    private PanelScreenJob newJob(final boolean enabled, final ScreenService<? extends ScreenConfig> first,
                                  final ScreenService<? extends ScreenConfig> second,
                                  final ScreenConfig firstConfig, final ScreenConfig secondConfig) {
        final Px75Panel px75Panel = mock(Px75Panel.class);
        when(px75Panel.getSerial()).thenReturn(SERIAL);
        final List<ScreenService<? extends ScreenConfig>> services = List.of(first, second);
        final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();
        return new PanelScreenJob(px75Panel, new Px75PanelConfig(1L, List.of(firstConfig, secondConfig)),
                services, imageService, mqttClient, broadcaster, ackService, previewGenerations, enabled);
    }

    private PanelScreenJob newJob(final boolean enabled,
                                  final ScreenService<? extends ScreenConfig> service,
                                  final ScreenConfig config) {
        final Px75Panel px75Panel = mock(Px75Panel.class);
        when(px75Panel.getSerial()).thenReturn(SERIAL);
        final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();
        return new PanelScreenJob(px75Panel, new Px75PanelConfig(1L, List.of(config)),
                List.of(service), imageService, mqttClient, broadcaster, ackService, previewGenerations, enabled);
    }

    private static ClockScreenConfig clockConfig() {
        return new ClockScreenConfig(ScreenType.CLOCK, 1, "Europe/Amsterdam", true, "#00FF00");
    }

    private static AircraftScreenConfig radarConfig() {
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, 1,
                AircraftScreenConfig.AircraftDisplayMode.RADAR, null, 50, false,
                AircraftScreenConfig.AircraftDisplayUnits.AVIATION, 100);
    }

    private void fireLoaded(final int slot, final long uploadId) {
        try {
            loadedListener.messageArrived(SERIAL + "/anim/loaded", new MqttMessage(new byte[]{
                    'A', 'N', 'I', 'L',
                    (byte) slot,
                    (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)}));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long u32le(final byte[] payload, final int offset) {
        return (payload[offset] & 0xFFL)
                | (payload[offset + 1] & 0xFFL) << 8
                | (payload[offset + 2] & 0xFFL) << 16
                | (payload[offset + 3] & 0xFFL) << 24;
    }
}
