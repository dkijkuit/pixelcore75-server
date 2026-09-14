package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.utils.mapper.JsonMapperFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code PanelRotationJob} on a real (in-memory) JobRunr (plan §4): successor scheduling
 * with the computed slot delay, stale-execution no-ops (config save raced the slot),
 * delete tombstones, and idempotent startup reconciliation. The storage provider is
 * in-memory, the BackgroundJobServer runs in-process with a 1 s poll — no docker, no
 * broker, no live panels.
 */
class PanelRotationJobJobRunrTests {

    private static final String SERIAL_A = "JOBRUNRP1";
    private static final String SERIAL_B = "JOBRUNRP2";

    private InMemoryStorageProvider storageProvider;
    private BackgroundJobServer backgroundJobServer;
    private RotationStateService rotationStateService;
    private MqttTransport mqttTransport;
    private ImageService imageService;
    private PanelRepository panelRepository;
    private Px75PanelConfigService panelConfigService;
    private PanelRotationControl control;
    private final List<String> renderedSerials = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        storageProvider = new InMemoryStorageProvider();
        rotationStateService = new RotationStateService();
        mqttTransport = mock(MqttTransport.class);
        // A "render" is observed by the retained static-frame publish it produces.
        doAnswerPubRecording();
        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0))).thenReturn(new byte[4096]);

        panelRepository = mock(PanelRepository.class);
        when(panelRepository.findBySerialIgnoreCase(anyString())).thenAnswer(inv -> {
            final String serial = inv.getArgument(0);
            return serial.equals(SERIAL_A) || serial.equals(SERIAL_B)
                    ? java.util.Optional.of(new Px75Panel(1L, 1L, serial, "00:00:00:00:00:00", "t", Px75PanelType.P_64_X_32))
                    : java.util.Optional.empty();
        });
        panelConfigService = mock(Px75PanelConfigService.class);
        when(panelConfigService.getPanelConfig(1L)).thenReturn(customRotation(5));

        final PanelRotationJob job = buildJob();
        // Outside the Spring autoconfiguration the storage provider needs its mapper wired by hand.
        final var jsonMapper = JsonMapperFactory.createJsonMapper();
        storageProvider.setJobMapper(new org.jobrunr.jobs.mappers.JobMapper(jsonMapper));
        final var panelRepository = mock(nl.ctasoftware.crypto.ticker.server.repository.PanelRepository.class);
        when(panelRepository.findAll()).thenReturn(java.util.List.of(
                new Px75Panel(1L, 1L, SERIAL_A, "00:00:00:00:00:00", "a", Px75PanelType.P_64_X_32),
                new Px75Panel(2L, 1L, SERIAL_B, "00:00:00:00:00:00", "b", Px75PanelType.P_64_X_32)));
        backgroundJobServer = new BackgroundJobServer(storageProvider, jsonMapper,
                new org.jobrunr.server.JobActivator() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T activateJob(final Class<T> type) {
                        return (T) job;
                    }
                },
                org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration()
                        .andPollIntervalInSeconds(1));
        control = new PanelRotationControl(panelRepository, rotationStateService, job,
                new JobScheduler(storageProvider),
                mock(ImageBroadcasterService.class),
                new PreviewStreamer(imageService, mock(ImageBroadcasterService.class), rotationStateService));
        backgroundJobServer.start();
    }

    @AfterEach
    void tearDown() {
        backgroundJobServer.stop();
    }

    /* ------------------------------------------------------------------ */

    @Test
    void successorIsScheduledWithTheComputedSlotDelay() {
        // A 5 s screen must persist its successor ~5 s out (plus 0-250 ms jitter).
        control.schedulePanelScreenJob(SERIAL_A);

        await().atMost(Duration.ofSeconds(10)).until(() -> storageProvider.countJobs(StateName.SUCCEEDED) >= 1);

        final Job successor = storageProvider.getJobById(
                UUID.fromString(rotationStateService.stateFor(SERIAL_A).allowedJobId.get()));
        assertTrue(successor.getJobState() instanceof ScheduledState,
                "the rendered slot must persist its successor");
        final long delayMillis = ((ScheduledState) successor.getJobState()).getScheduledAt().toEpochMilli()
                - System.currentTimeMillis();
        assertTrue(delayMillis > 4_000 && delayMillis < 6_000,
                "successor ~5 s out (+ jitter), was " + delayMillis + " ms");
    }

    @Test
    void staleExecutionIsANoOp() {
        // A config save kicked a fresh run; an orphaned successor from before it wakes up
        // with a foreign job id and must neither render nor schedule anything.
        final String freshJobId = rotationStateService.kick(SERIAL_A);

        buildJob().execute(SERIAL_A, "orphaned-successor-id");

        assertTrue(renderedSerials.isEmpty(), "a stale execution must not render");
        assertEquals(freshJobId, rotationStateService.stateFor(SERIAL_A).allowedJobId.get(),
                "the stale run must not steal admission");
        assertEquals(0, storageProvider.countJobs(StateName.SCHEDULED),
                "the stale run must not schedule a successor");
    }

    @Test
    void deleteBeforeRowDeleteLeavesNoRunnableJobs() throws Exception {
        control.schedulePanelScreenJob(SERIAL_A);
        await().atMost(Duration.ofSeconds(10)).until(() -> storageProvider.countJobs(StateName.SUCCEEDED) >= 1);

        control.stopBeforeDelete(SERIAL_A);

        await().atMost(Duration.ofSeconds(3)).until(() ->
                storageProvider.countJobs(StateName.SCHEDULED) == 0
                        && storageProvider.countJobs(StateName.ENQUEUED) == 0);
        final int rendersBefore = renderedSerials.size();
        Thread.sleep(2_500); // longer than one poll interval + a full slot render
        assertEquals(rendersBefore, renderedSerials.size(), "a deleted panel's rotation must be dead");
    }

    @Test
    void startupReconciliationIsIdempotent() throws Exception {
        // Double reconciliation (the crash-self-heal scenario: a successor may already be
        // persisted when reconcileStartup runs again): exactly one render per panel wins —
        // every job whose id lost the admission ticket is a silent no-op.
        control.reconcileStartup();
        control.reconcileStartup();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                storageProvider.countJobs(StateName.SUCCEEDED) >= 2);
        Thread.sleep(2_500);
        assertEquals(1, renderedSerials.stream().filter(SERIAL_A::equals).count(),
                "exactly one rotation may run for a reconciled panel");
        assertEquals(1, renderedSerials.stream().filter(SERIAL_B::equals).count(),
                "exactly one rotation may run for a reconciled panel");
    }

    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private PanelRotationJob buildJob() {
        final ScreenService<CustomScreenConfig> customService = new StubCustomService();
        final ScreenServices screenServices = new ScreenServices(List.of(customService));
        final AnimationLoadAckService ackService = new AnimationLoadAckService(mqttTransport);
        final RotationPlanner planner = new RotationPlanner(config -> config);
        final AnimationTransport transport = new AnimationTransport(
                imageService, ackService, screenServices, rotationStateService, mqttTransport);
        final PreviewStreamer previewStreamer =
                new PreviewStreamer(imageService, mock(ImageBroadcasterService.class), rotationStateService);
        final CommandPublisher commandPublisher =
                new CommandPublisher(mqttTransport, rotationStateService, previewStreamer);
        return new PanelRotationJob(panelRepository, panelConfigService, screenServices, planner,
                transport, commandPublisher, previewStreamer, rotationStateService,
                new JobScheduler(storageProvider), mqttTransport, imageService);
    }

    private void doAnswerPubRecording() {
        org.mockito.Mockito.doAnswer(invocation -> {
            final String topic = invocation.getArgument(0);
            final byte[] payload = invocation.getArgument(1);
            if ((topic.equals(SERIAL_A) || topic.equals(SERIAL_B)) && payload.length > 0) {
                renderedSerials.add(topic);
            }
            return null;
        }).when(mqttTransport).publish(anyString(), any(byte[].class), anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static Px75PanelConfig customRotation(final int durationSeconds) {
        return new Px75PanelConfig(1L, List.of(new CustomScreenConfig(ScreenType.CUSTOM, durationSeconds,
                "{\"schemaVersion\":1,\"name\":\"T\",\"frames\":[{\"layers\":[]}]}")));
    }

    /** Renders a static frame (the retained publish is how the test observes a render). */
    private static final class StubCustomService extends CustomScreenService {
        StubCustomService() {
            super(new PaintToolsService(null, null, null), null, null, null, null, null, null, null);
        }

        @Override
        public ScreenType getScreenType() {
            return ScreenType.CUSTOM;
        }

        @Override
        public Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }
}
