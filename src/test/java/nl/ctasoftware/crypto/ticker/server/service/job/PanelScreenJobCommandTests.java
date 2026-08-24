package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** Written from the job thread AND the page-flip virtual thread — must be thread-safe. */
    private final List<Pub> pubs = new CopyOnWriteArrayList<>();
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

    /** A CLOSEST-style screen cycling two command pages at a 300 ms dwell. */
    private static final class StubPagedService implements CommandScreenService<ScreenConfig> {
        @Override
        public ScreenType getScreenType() {
            return ScreenType.NEARBY_AIRCRAFT;
        }

        @Override
        public Optional<BufferedImage> renderScreen(final ScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public byte[] renderCommandBatch(final ScreenConfig screenConfig) {
            return page(10, 10, 0xF800);
        }

        @Override
        public BatchStream renderCommandBatches(final ScreenConfig screenConfig) {
            return new BatchStream(List.of(page(10, 10, 0xF800), page(20, 20, 0x001F)), 300);
        }

        private static byte[] page(final int x, final int y, final int color) {
            return CommandBatch.builder().cls(0x0000).pix(x, y, color).build();
        }
    }

    /**
     * A CUSTOM screen whose per-design {@code commandCapable} is stubbed: the real service
     * derives it from the design (parametric layers), the job must gate both the command
     * branch and the staging skip on it.
     */
    private static final class StubCustomService
            extends nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService {
        final boolean capable;

        StubCustomService(final boolean capable) {
            super(new PaintToolsService(null, null, null), null, null, null, null, null, null, null);
            this.capable = capable;
        }

        @Override
        public boolean commandCapable(
                final nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig screenConfig) {
            return capable;
        }

        @Override
        public byte[] renderCommandBatch(
                final nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig screenConfig) {
            return CommandBatch.builder().cls(0x0000).blink(0, 0, 8, 8, 500).build();
        }

        @Override
        public Optional<BufferedImage> renderScreen(
                final nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(
                final nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB),
                    new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }

    /**
     * A RADAR-style live screen: a fresh batch every render (the pixel's y encodes the
     * render counter, so republished payloads prove re-rendered data) and a 300 ms
     * refresh stream (one whole sweep loop per refresh, like the real radar's contract).
     */
    private static final class StubRefreshingService implements CommandScreenService<ScreenConfig> {
        final AtomicInteger renders = new AtomicInteger();

        @Override
        public ScreenType getScreenType() {
            return ScreenType.NEARBY_AIRCRAFT;
        }

        @Override
        public Optional<BufferedImage> renderScreen(final ScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public byte[] renderCommandBatch(final ScreenConfig screenConfig) {
            return CommandBatch.builder().cls(0x0000)
                    .pix(10, 10 + renders.incrementAndGet(), 0x07E0).build();
        }

        @Override
        public RefreshStream renderCommandRefresh(final ScreenConfig screenConfig) {
            return new RefreshStream(renderCommandBatch(screenConfig),
                    () -> renderCommandBatch(screenConfig), 300);
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

    @Test
    void capableCustomDesignRendersViaCommandsFlagOn() throws Exception {
        newJob(true, new StubCustomService(true), customConfig(SINGLE_FRAME_DESIGN)).run();

        assertEquals(2, pubs.size(), "retained clear + the command batch");
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic());
        assertEquals(AcmdCommand.Blink.class,
                AcmdParser.parse(pubs.get(1).payload()).commands().get(1).getClass());
    }

    @Test
    void frameDesignCustomScreenKeepsItsPathsDespiteTheCommandInterface() throws Exception {
        // Flag ON and the CUSTOM service implements CommandScreenService — but this design
        // is not command-capable: a single-frame design takes the static retained path...
        newJob(true, new StubCustomService(false), customConfig(SINGLE_FRAME_DESIGN)).run();

        assertEquals(1, pubs.size(), "one static retained frame publish, nothing else");
        assertEquals(SERIAL, pubs.getFirst().topic());
        assertTrue(pubs.getFirst().retained());
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "an incapable design must never reach /cmd");
    }

    @Test
    void stagingUploadsIncapableCustomFrameDesigns() throws Exception {
        ackUploads = true;
        // ...and a multi-frame design still stages its ANIM upload for the next boundary.
        final PanelScreenJob job = newJob(true, new StubClockService(), new StubCustomService(false),
                clockConfig(), customConfig(TWO_FRAME_DESIGN));

        job.run(); // clock boundary: stage the custom animation

        assertEquals(1, pubs(p -> p.topic().equals(SERIAL + PanelScreenJob.ANIM_START_TOPIC)).size(),
                "the frame-design CUSTOM must stage its animation despite the command interface");
        // The clock boundary legitimately publishes its own /cmd batch (pub #2); nothing
        // after it may touch /cmd — the incapable custom stays on the ANIM pipeline.
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic(), "the clock's command batch");
        assertEquals(1, pubs(p -> p.topic().endsWith("/cmd")).size(),
                "no command traffic for the incapable design");
    }

    @Test
    void flagOnCyclesCommandPagesAtTheDwellUntilTheSlotEnds() throws Exception {
        newJob(true, new StubPagedService(), pagedConfig()).run();

        // Initial page publishes synchronously; the flip to page 2 lands within a
        // preview tick after the 300 ms dwell.
        awaitCmdPubs(2, 2_000);

        final List<Pub> cmdPubs = pubs(p -> p.topic().equals(SERIAL + "/cmd"));
        assertEquals(2, cmdPubs.size(), "initial page + one flip; page 2 holds until the slot ends");
        assertTrue(Arrays.equals(StubPagedService.page(10, 10, 0xF800), cmdPubs.get(0).payload()),
                "identity page first");
        assertTrue(Arrays.equals(StubPagedService.page(20, 20, 0x001F), cmdPubs.get(1).payload()),
                "second page flipped at the dwell");
        assertEquals(0, cmdPubs.get(1).qos(), "page flips stay QoS 0");
        assertFalse(cmdPubs.get(1).retained(), "page flips are not retained");

        // Past the slot deadline (1 s from the send) the cycle must have stopped.
        Thread.sleep(1_500);
        assertEquals(2, pubs(p -> p.topic().equals(SERIAL + "/cmd")).size(),
                "no publishes after the slot ends");
    }

    @Test
    void flagOnRefreshesTheCommandBatchUntilTheSlotEnds() throws Exception {
        newJob(true, new StubRefreshingService(), radarConfig()).run();

        // 1 s slot on a 300 ms grid: the first batch plus refreshes at 300/600/900 ms.
        final long deadline = System.currentTimeMillis() + 3_000;
        while (pubs(p -> p.topic().equals(SERIAL + "/cmd")).size() < 3
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        final List<Pub> cmdPubs = pubs(p -> p.topic().equals(SERIAL + "/cmd"));
        assertTrue(cmdPubs.size() >= 3, "first batch + at least two on-grid refreshes, got " + cmdPubs.size());

        for (int i = 1; i < cmdPubs.size(); i++) {
            assertFalse(Arrays.equals(cmdPubs.get(i - 1).payload(), cmdPubs.get(i).payload()),
                    "each refresh publishes a freshly rendered batch");
            assertEquals(0, cmdPubs.get(i).qos(), "refreshes stay QoS 0");
            assertFalse(cmdPubs.get(i).retained(), "refreshes are not retained");
        }

        // Past the slot deadline (1 s from the send) the refreshes must have stopped.
        Thread.sleep(1_500);
        final int settled = pubs(p -> p.topic().equals(SERIAL + "/cmd")).size();
        Thread.sleep(600);
        assertEquals(settled, pubs(p -> p.topic().equals(SERIAL + "/cmd")).size(),
                "no refreshes after the slot ends");
    }

    /* ------------------------------------------------------------------ */

    private void awaitCmdPubs(final int count, final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (pubs(p -> p.topic().equals(SERIAL + "/cmd")).size() < count
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(count, pubs(p -> p.topic().equals(SERIAL + "/cmd")).size(),
                "expected " + count + " /cmd publishes within " + timeoutMs + " ms");
    }

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

    private static AircraftScreenConfig pagedConfig() {
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, 1,
                AircraftScreenConfig.AircraftDisplayMode.CLOSEST, null, 50, false,
                AircraftScreenConfig.AircraftDisplayUnits.AVIATION, 100);
    }

    private static final String SINGLE_FRAME_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"T\",\"frames\":[{\"layers\":[]}]}";
    private static final String TWO_FRAME_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"T\",\"frames\":[{\"layers\":[]},{\"layers\":[]}]}";

    private static CustomScreenConfig customConfig(final String design) {
        return new CustomScreenConfig(ScreenType.CUSTOM, 1, design);
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
