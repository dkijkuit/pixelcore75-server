package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
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
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.clock.ClockScreenService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rotation pipeline's ACMD command branch (plan §6), docker-free against a mocked
 * MQTT transport: flag ON + {@link CommandScreenService} → exactly one QoS-0 not-retained
 * {@code <serial>/cmd} publish of a parseable batch, the retained base image cleared,
 * no ANIM traffic, staging skipped for command screens. Flag OFF (or a non-command
 * screen) → the frame/static path byte-for-byte and zero {@code /cmd} publishes.
 */
class PanelRotationJobCommandTests {

    private static final String SERIAL = "CMDTESTP1";

    private record Pub(String topic, byte[] payload, int qos, boolean retained) {}

    private MqttTransport mqttTransport;
    private JobScheduler jobScheduler;
    private RotationStateService rotationStateService;
    private AnimationLoadAckService ackService;
    private ImageService imageService;
    private ImageBroadcasterService broadcaster;
    private PanelRepository panelRepository;
    private Px75PanelConfigService panelConfigService;
    private MqttTransport.MqttMessageListener loadedListener;

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

        doAnswer(invocation -> {
            handlePublish(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(mqttTransport).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());
        doAnswer(invocation -> {
            handlePublish(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(mqttTransport).publishAsync(anyString(), any(byte[].class), anyInt(), anyBoolean());

        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0)))
                .thenAnswer(inv -> frameBytes.computeIfAbsent(inv.getArgument(0),
                        img -> new byte[AnimationFrameCodec.FRAME_BYTES]));
        broadcaster = mock(ImageBroadcasterService.class);

        panelRepository = mock(PanelRepository.class);
        when(panelRepository.findBySerialIgnoreCase(SERIAL)).thenReturn(java.util.Optional.of(
                new Px75Panel(1L, 1L, SERIAL, "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32)));
        panelConfigService = mock(Px75PanelConfigService.class);
        when(panelConfigService.getPanelConfig(1L)).thenAnswer(
                invocation -> new Px75PanelConfig(1L, jobConfigs));
    }

    private List<ScreenConfig> jobConfigs;

    /** Firmware stand-in shared by the sync and async publish stubs. */
    private void handlePublish(final String topic, final byte[] payload, final int qos, final boolean retained) {
        pubs.add(new Pub(topic, payload, qos, retained));
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
        public java.util.Optional<BufferedImage> renderScreen(final ClockScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
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
        public java.util.Optional<BufferedImage> renderScreen(final AircraftScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
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
        public java.util.Optional<BufferedImage> renderScreen(final ScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
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
        public boolean commandCapable(final CustomScreenConfig screenConfig) {
            return capable;
        }

        @Override
        public byte[] renderCommandBatch(final CustomScreenConfig screenConfig) {
            return CommandBatch.builder().cls(0x0000).blink(0, 0, 8, 8, 500).build();
        }

        @Override
        public java.util.Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
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
        public java.util.Optional<BufferedImage> renderScreen(final ScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
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
    void flagOnPublishesCommandBatchToCmdTopicAndClearsRetainedBase() {
        jobConfigs = List.of(clockConfig());
        runSlot(newJob(true, new StubClockService()));

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
    void flagOffUsesStaticFramePathAndNeverTouchesCmd() {
        jobConfigs = List.of(clockConfig());
        runSlot(newJob(false, new StubClockService()));

        assertEquals(1, pubs.size(), "one static retained frame publish, nothing else");
        assertEquals(SERIAL, pubs.getFirst().topic());
        assertTrue(pubs.getFirst().retained());
        assertTrue(pubs.getFirst().payload().length > 0, "static frame payload");
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "flag OFF must never publish to /cmd");
    }

    @Test
    void flagOnWithFrameScreenKeepsFramePathByteForByte() {
        @SuppressWarnings("unchecked")
        final FrameScreenService<AnimationScreenConfig> animationService = mock(FrameScreenService.class);
        when(animationService.getScreenType()).thenReturn(ScreenType.ANIMATION);
        when(animationService.renderFrameStream(any(AnimationScreenConfig.class))).thenReturn(
                new FrameScreenService.FrameStream(
                        List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB),
                                new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)),
                        50));
        ackUploads = true;
        jobConfigs = List.of(new AnimationScreenConfig(ScreenType.ANIMATION, 1, 50, List.of("f1", "f2")));

        runSlot(newJob(true, animationService));

        assertEquals(1, pubs(p -> p.topic().equals(SERIAL + AnimationTransport.ANIM_START_TOPIC)).size(),
                "flag ON on a non-command screen must upload the animation as before");
        assertEquals(2, pubs(p -> p.topic().equals(SERIAL + AnimationTransport.ANIM_FRAME_TOPIC)).size());
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "a screen without CommandScreenService never reaches /cmd");
    }

    @Test
    void stagingSkipsFrameUploadWhenNextScreenRendersViaCommands() {
        jobConfigs = List.of(clockConfig(), radarConfig());
        final PanelRotationJob job = newJob(true, new StubClockService(), new StubRadarService());

        final String jobId = runSlot(job); // clock boundary: radar would normally stage its frame stream here

        assertEquals(2, pubs.size(), "retained clear + clock batch; no staging upload for a command-rendered next screen");
        assertEquals(SERIAL, pubs.get(0).topic());
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic());

        job.execute(SERIAL, jobId); // radar boundary: command path, not the staged inline upload

        assertEquals(4, pubs.size(), "radar boundary publishes clear + batch, nothing else");
        assertEquals(SERIAL + "/cmd", pubs.get(3).topic());
        assertEquals(0, pubs(p -> p.topic().endsWith(AnimationTransport.ANIM_START_TOPIC)).size(),
                "no ANIM upload anywhere in the rotation");
    }

    @Test
    void capableCustomDesignRendersViaCommandsFlagOn() {
        jobConfigs = List.of(customConfig(SINGLE_FRAME_DESIGN));
        runSlot(newJob(true, new StubCustomService(true)));

        assertEquals(2, pubs.size(), "retained clear + the command batch");
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic());
        assertEquals(AcmdCommand.Blink.class,
                AcmdParser.parse(pubs.get(1).payload()).commands().get(1).getClass());
    }

    @Test
    void frameDesignCustomScreenKeepsItsPathsDespiteTheCommandInterface() {
        // Flag ON and the CUSTOM service implements CommandScreenService — but this design
        // is not command-capable: a single-frame design takes the static retained path...
        jobConfigs = List.of(customConfig(SINGLE_FRAME_DESIGN));
        runSlot(newJob(true, new StubCustomService(false)));

        assertEquals(1, pubs.size(), "one static retained frame publish, nothing else");
        assertEquals(SERIAL, pubs.getFirst().topic());
        assertTrue(pubs.getFirst().retained());
        assertTrue(pubs.stream().noneMatch(p -> p.topic().endsWith("/cmd")),
                "an incapable design must never reach /cmd");
    }

    @Test
    void stagingUploadsIncapableCustomFrameDesigns() {
        ackUploads = true;
        // ...and a multi-frame design still stages its ANIM upload for the next boundary.
        jobConfigs = List.of(clockConfig(), customConfig(TWO_FRAME_DESIGN));
        final PanelRotationJob job = newJob(true, new StubClockService(), new StubCustomService(false));

        runSlot(job); // clock boundary: stage the custom animation

        assertEquals(1, pubs(p -> p.topic().equals(SERIAL + AnimationTransport.ANIM_START_TOPIC)).size(),
                "the frame-design CUSTOM must stage its animation despite the command interface");
        // The clock boundary legitimately publishes its own /cmd batch (pub #2); nothing
        // after it may touch /cmd — the incapable custom stays on the ANIM pipeline.
        assertEquals(SERIAL + "/cmd", pubs.get(1).topic(), "the clock's command batch");
        assertEquals(1, pubs(p -> p.topic().endsWith("/cmd")).size(),
                "no command traffic for the incapable design");
    }

    @Test
    void flagOnCyclesCommandPagesAtTheDwellUntilTheSlotEnds() throws Exception {
        jobConfigs = List.of(pagedConfig());
        runSlot(newJob(true, new StubPagedService()));

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
        jobConfigs = List.of(radarConfig());
        runSlot(newJob(true, new StubRefreshingService()));

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

    /** Runs one slot, returning the successor's admission id (the mocked scheduler never runs it). */
    private String runSlot(final PanelRotationJob job) {
        final String jobId = rotationStateService.kick(SERIAL);
        job.execute(SERIAL, jobId);
        return rotationStateService.stateFor(SERIAL).allowedJobId.get();
    }

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

    private PanelRotationJob newJob(final boolean enabled, final ScreenService<? extends ScreenConfig> first,
                                    final ScreenService<? extends ScreenConfig> second) {
        return buildJob(enabled, List.of(first, second));
    }

    private PanelRotationJob newJob(final boolean enabled,
                                    final ScreenService<? extends ScreenConfig> service) {
        return buildJob(enabled, List.of(service));
    }

    private PanelRotationJob buildJob(final boolean enabled,
                                      final List<ScreenService<? extends ScreenConfig>> services) {
        final ScreenServices screenServices = new ScreenServices(new ArrayList<>(services));
        final RotationPlanner planner = new RotationPlanner(config -> config);
        final AnimationTransport transport = new AnimationTransport(
                imageService, ackService, screenServices, rotationStateService, mqttTransport, enabled);
        final PreviewStreamer previewStreamer = new PreviewStreamer(imageService, broadcaster, rotationStateService);
        final CommandPublisher commandPublisher = new CommandPublisher(mqttTransport, rotationStateService, previewStreamer);
        return new PanelRotationJob(panelRepository, panelConfigService, screenServices, planner,
                transport, commandPublisher, previewStreamer, rotationStateService,
                jobScheduler, mqttTransport, imageService, enabled);
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
        loadedListener.messageArrived(SERIAL + "/anim/loaded", new byte[]{
                'A', 'N', 'I', 'L',
                (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8), (byte) (uploadId >> 16), (byte) (uploadId >> 24)});
    }

    private static long u32le(final byte[] payload, final int offset) {
        return (payload[offset] & 0xFFL)
                | (payload[offset + 1] & 0xFFL) << 8
                | (payload[offset + 2] & 0xFFL) << 16
                | (payload[offset + 3] & 0xFFL) << 24;
    }
}
