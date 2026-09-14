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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rotation loop's disabled-screen handling, docker-free against a mocked MQTT
 * transport: {@code disabled} entries stay in the config but are skipped by rendering,
 * and a rotation whose entries are all disabled shows the no-config image without
 * scheduling a successor (same observable behavior as a panel without any config).
 */
class PanelRotationJobDisabledTests {

    private static final String SERIAL = "DISABLEDJOB1";

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
    private final List<Integer> renderedDurations = new CopyOnWriteArrayList<>();

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

        job = newJob(List.of(new RecordingCustomService()));
    }

    private List<ScreenConfig> jobConfigs;

    @Test
    void disabledScreensAreSkippedByTheRotation() {
        jobConfigs = List.of(inline(10, true), inline(5, false));

        String jobId = rotationStateService.kick(SERIAL);
        for (int cycle = 0; cycle < 3; cycle++) {
            job.execute(SERIAL, jobId);
            jobId = rotationStateService.stateFor(SERIAL).allowedJobId.get();
            assertTrue(jobId != null, "a running rotation schedules its successor");
        }

        assertEquals(List.of(5, 5, 5), renderedDurations, "only the enabled screen ever renders");
        assertEquals(3, baseTopicPubs().size(), "every cycle publishes the rendered static frame");
    }

    @Test
    void allDisabledBehavesLikeNoConfig() {
        jobConfigs = List.of(inline(10, true), inline(20, true));

        final String jobId = rotationStateService.kick(SERIAL);
        job.execute(SERIAL, jobId);

        assertTrue(renderedDurations.isEmpty(), "no screen service may render");
        assertEquals(1, baseTopicPubs().size(), "the no-config image is published instead");
        assertTrue(baseTopicPubs().get(0).retained());
        assertTrue(baseTopicPubs().get(0).payload().length > 0);
        org.mockito.Mockito.verify(jobScheduler, org.mockito.Mockito.never())
                .schedule(any(java.util.UUID.class), any(java.time.temporal.Temporal.class), any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static CustomScreenConfig inline(final int durationSeconds, final boolean disabled) {
        return new CustomScreenConfig(ScreenType.CUSTOM, durationSeconds, null, STATIC_DESIGN, disabled);
    }

    private PanelRotationJob newJob(final List<ScreenService<? extends ScreenConfig>> services) {
        final ScreenServices screenServices = new ScreenServices(services);
        final AnimationLoadAckService ackService = new AnimationLoadAckService(mqttTransport);
        final RotationPlanner planner = new RotationPlanner(config -> config);
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

    /** Extends the concrete service (the static-path switch casts to it); records what renders. */
    private final class RecordingCustomService
            extends nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService {
        RecordingCustomService() {
            super(new nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService(null, null, null),
                    null, null, null, null, null, null, null);
        }

        @Override
        public java.util.Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            renderedDurations.add(screenConfig.durationSeconds());
            return java.util.Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }
}
