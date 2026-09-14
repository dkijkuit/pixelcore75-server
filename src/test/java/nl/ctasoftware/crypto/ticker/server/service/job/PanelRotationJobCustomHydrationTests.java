package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenResolver;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The rotation loop's CUSTOM library-reference handling, docker-free against a mocked
 * MQTT transport: references are re-resolved every cycle (library edits propagate live),
 * dangling references are skipped instead of failing the panel, and a rotation whose
 * entries are all dangling shows the no-config image but keeps retrying so it recovers
 * once the library entry exists again.
 */
class PanelRotationJobCustomHydrationTests {

    private static final String SERIAL = "CUSTOMJOB1";

    /** Single-frame design: static path (retained base image), no ANIM machinery needed. */
    private static final String STATIC_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"Static\",\"frames\":[{\"layers\":[]}]}";

    private record Pub(String topic, byte[] payload, boolean retained) {}

    private MqttTransport mqttTransport;
    private JobScheduler jobScheduler;
    private RotationStateService rotationStateService;
    private ImageService imageService;
    private PanelRepository panelRepository;
    private Px75PanelConfigService panelConfigService;
    private PanelRotationJob job;
    private final List<Pub> pubs = new CopyOnWriteArrayList<>();

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
        doAnswer(invocation -> {
            pubs.add(new Pub(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(3)));
            return null;
        }).when(mqttTransport).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());

        rotationStateService = new RotationStateService();
        jobScheduler = mock(JobScheduler.class);

        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0))).thenReturn(new byte[4096]);
        when(imageService.imageToBufferedImage(anyString()))
                .thenReturn(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));

        panelRepository = mock(PanelRepository.class);
        when(panelRepository.findBySerialIgnoreCase(SERIAL)).thenReturn(java.util.Optional.of(
                new Px75Panel(1L, 1L, SERIAL, "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32)));
        panelConfigService = mock(Px75PanelConfigService.class);
        when(panelConfigService.getPanelConfig(1L)).thenAnswer(
                invocation -> new Px75PanelConfig(1L, jobConfigs));

        job = newJob(config -> config);
    }

    private List<ScreenConfig> jobConfigs;

    /** Runs one slot, returning the successor's admission id (the mocked scheduler never runs it). */
    private String runSlot(final String jobId) {
        job.execute(SERIAL, jobId);
        return rotationStateService.stateFor(SERIAL).allowedJobId.get();
    }

    @Test
    void skipsDanglingReferenceAndRendersTheRemainingScreen() {
        // rotation: [dangling reference, resolvable reference] — only the second renders
        job = newJob(config -> config.customScreenId() == 42L ? null : resolved(config));
        jobConfigs = List.of(reference(42L, 10), reference(7L, 5));

        final String successor = runSlot(rotationStateService.kick(SERIAL));

        assertTrue(successor != null, "a healthy rotation keeps running");
        assertEquals(1, baseTopicPubs().size());
        assertTrue(baseTopicPubs().get(0).payload().length > 0, "static screen publishes a retained frame");
    }

    @Test
    void allReferencesDanglingShowsNoConfigAndKeepsRetrying() {
        job = newJob(config -> null);
        jobConfigs = List.of(reference(42L, 10));

        final String successor = runSlot(rotationStateService.kick(SERIAL));

        assertTrue(successor != null, "re-checks the library instead of stopping forever");
        // The retry delay is persisted on the scheduled successor job, not in memory.
        verify(jobScheduler).schedule(any(java.util.UUID.class),
                org.mockito.ArgumentMatchers.argThat((java.time.temporal.Temporal t) ->
                        java.time.Duration.between(java.time.Instant.now(), (java.time.Instant) t).toMillis() > 20_000),
                any(org.jobrunr.jobs.lambdas.JobLambda.class));
        assertEquals(1, baseTopicPubs().size());
    }

    @Test
    void referenceIsReResolvedEveryCycle() {
        final AtomicInteger resolutions = new AtomicInteger();
        job = newJob(config -> {
            resolutions.incrementAndGet();
            return resolved(config);
        });
        jobConfigs = List.of(reference(7L, 1));

        String jobId = rotationStateService.kick(SERIAL);
        jobId = runSlot(jobId);
        runSlot(jobId);

        assertEquals(2, resolutions.get(), "library edits must reach a running rotation without a re-save");
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static CustomScreenConfig reference(final long id, final int durationSeconds) {
        return new CustomScreenConfig(ScreenType.CUSTOM, durationSeconds, id, null);
    }

    private static CustomScreenConfig resolved(final CustomScreenConfig config) {
        return new CustomScreenConfig(ScreenType.CUSTOM, config.durationSeconds(), STATIC_DESIGN);
    }

    private PanelRotationJob newJob(final CustomScreenResolver resolver) {
        final ScreenServices screenServices = new ScreenServices(List.of(new StubCustomService()));
        final AnimationLoadAckService ackService = new AnimationLoadAckService(mqttTransport);
        final RotationPlanner planner = new RotationPlanner(resolver);
        final AnimationTransport transport = new AnimationTransport(
                imageService, ackService, screenServices, rotationStateService, mqttTransport);
        final PreviewStreamer previewStreamer = new PreviewStreamer(
                imageService, mock(ImageBroadcasterService.class), rotationStateService);
        final CommandPublisher commandPublisher = new CommandPublisher(mqttTransport, rotationStateService, previewStreamer);
        return new PanelRotationJob(panelRepository, panelConfigService, screenServices, planner,
                transport, commandPublisher, previewStreamer, rotationStateService,
                jobScheduler, mqttTransport, imageService);
    }

    private List<Pub> baseTopicPubs() {
        return pubs.stream().filter(p -> p.topic().equals(SERIAL)).toList();
    }

    /** Extends the concrete service (the static-path switch casts to it); renders fixed frames. */
    private static final class StubCustomService
            extends nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService {
        StubCustomService() {
            super(new nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService(null, null, null),
                    null, null, null, null, null, null, null);
        }

        @Override
        public java.util.Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }
}
