package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.ScreenServiceNotFoundException;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.*;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.StaticScreenService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One rotation slot for one panel, executed by JobRunr (plan §4): loads the panel and its
 * config from the DB every run (config saves become visible at the next cycle without any
 * re-registration), renders the current screen, stages the next animation one slot ahead,
 * and persists its own successor — so a crash between run and successor self-heals at the
 * next startup reconciliation.
 *
 * <p>Job parameters are only the serial and the job id minted by
 * {@link RotationStateService#kick}: the id is the admission ticket — a stale execution
 * (config save raced the slot, orphaned successor after a restart) sees a foreign id and
 * exits at the next safe point instead of rendering. All publishes re-check the ticket or
 * the epoch; there are no interrupts.
 */
@Slf4j
@Service
public class PanelRotationJob {

    // The repository (not Px75PanelService): the panel service calls into the control plane
    // for deletes, and this job must not close that cycle back into the service layer.
    private final PanelRepository panelRepository;
    private final Px75PanelConfigService px75PanelConfigService;
    private final ScreenServices screenServices;
    private final RotationPlanner rotationPlanner;
    private final AnimationTransport animationTransport;
    private final CommandPublisher commandPublisher;
    private final PreviewStreamer previewStreamer;
    private final RotationStateService rotationStateService;
    private final JobScheduler jobScheduler;
    private final MqttTransport mqttTransport;
    private final ImageService imageService;

    public PanelRotationJob(final PanelRepository panelRepository,
                            final Px75PanelConfigService px75PanelConfigService,
                            final ScreenServices screenServices,
                            final RotationPlanner rotationPlanner,
                            final AnimationTransport animationTransport,
                            final CommandPublisher commandPublisher,
                            final PreviewStreamer previewStreamer,
                            final RotationStateService rotationStateService,
                            final JobScheduler jobScheduler,
                            final MqttTransport mqttTransport,
                            final ImageService imageService) {
        this.panelRepository = panelRepository;
        this.px75PanelConfigService = px75PanelConfigService;
        this.screenServices = screenServices;
        this.rotationPlanner = rotationPlanner;
        this.animationTransport = animationTransport;
        this.commandPublisher = commandPublisher;
        this.previewStreamer = previewStreamer;
        this.rotationStateService = rotationStateService;
        this.jobScheduler = jobScheduler;
        this.mqttTransport = mqttTransport;
        this.imageService = imageService;
    }

    /**
     * Renders one rotation slot for {@code serial} and schedules the successor at the slot
     * end (plus 0–250 ms jitter, as before, to spread boundary load). Retries: JobRunr
     * re-runs a failed execution with exponential backoff; after the retries are exhausted
     * the failure stays visible on the dashboard instead of the old silent 30 s loop.
     */
    @Job(name = "Panel rotation", retries = 3)
    public void execute(final String serial, final String jobId) {
        if (!rotationStateService.isAllowed(serial, jobId)) {
            log.debug("Panel rotation for {} is no longer the admitted job; skipping", serial);
            return;
        }
        final long epoch = rotationStateService.beginRun(serial);

        final Px75Panel panel = panelRepository.findBySerialIgnoreCase(serial).orElse(null);
        if (panel == null) {
            log.warn("Panel {} no longer exists; not rescheduling its rotation", serial);
            return;
        }
        final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(panel.getPanelId());
        if (panelConfig == null) {
            log.warn("No config found for panel {} ({}); not rescheduling its rotation", panel.getPanelId(), serial);
            return;
        }

        final RotationPlanner.Rotation rotation = rotationPlanner.plan(panelConfig);
        if (rotation.reason() != RotationPlanner.Reason.OK) {
            log.warn("----> {} for panel {}; showing the no-config image{}",
                    rotation.reason(), panel.getPanelId(),
                    rotation.reschedules() ? " and re-checking in 30s" : "");
            sendNoConfigImage(serial, epoch);
            if (rotation.reschedules()) {
                scheduleSuccessor(serial, jobId, rotation.retryDelayMillis());
            }
            return;
        }

        final List<? extends ScreenConfig> activeScreens = rotation.screens();
        final int screenIdx = rotationStateService.nextScreenIndex(serial, activeScreens.size());
        final ScreenConfig screenConfig = activeScreens.get(screenIdx);

        try {
            log.debug("----> Next screen job for panel: {}", panel.getPanelId());
            renderScreen(serial, jobId, epoch, panel.getPanelId(), screenConfig, activeScreens, screenIdx);
        } catch (Exception e) {
            log.error("Error rendering screen job for panel: {}", panel.getPanelId(), e);
        }

        // While this screen displays, immediately push the next screen's animation to the
        // panel (it stores it and only plays it at the boundary). Staging time is subtracted
        // from the slot so the boundary stays on schedule; if staging overruns the slot the
        // previous screen simply holds until it completes (floor keeps a minimum display).
        // stageNext never throws: failures log and fall back to an inline upload.
        final long stagedMillis = animationTransport.stageNext(serial, jobId, screenIdx, activeScreens);
        final long slotMillis = Math.max(AnimationTransport.MIN_ANIMATION_SLOT_MILLIS,
                Duration.ofSeconds(screenConfig.durationSeconds()).toMillis() - stagedMillis);
        scheduleSuccessor(serial, jobId, slotMillis);
    }

    private void scheduleSuccessor(final String serial, final String jobId, final long delayMillis) {
        // The commit is authoritative: under the state lock the running job may mint its
        // successor only while it is still the admitted job — a concurrent config save
        // (kick) or panel delete wins instead, and this run schedules nothing.
        final String successorId = rotationStateService.mintSuccessorJobId(serial, jobId);
        if (successorId == null) {
            log.debug("Panel rotation for {} superseded; successor not scheduled", serial);
            return;
        }
        final int jitterMs = ThreadLocalRandom.current().nextInt(0, 250);
        jobScheduler.schedule(java.util.UUID.fromString(successorId),
                Instant.now().plusMillis(delayMillis + jitterMs),
                () -> execute(serial, successorId));
    }

    void renderScreen(final String serial, final String jobId, final long epoch, final long panelId,
                      final ScreenConfig screenConfig, final List<? extends ScreenConfig> screens,
                      final int screenIdx) {
        final ScreenService<? extends ScreenConfig> screenService = screenServices.get(screenConfig.screenType());
        if (screenService == null) {
            throw new ScreenServiceNotFoundException(screenConfig.screenType());
        }

        log.debug("------> Rendering screen {} for panel serial {}", screenConfig.screenType(), serial);

        // ACMD command path (plan §6): the screen's service implements it and declares this
        // config command-capable (CUSTOM: only parametric designs) → publish the first batch
        // to <serial>/cmd (multi-page screens cycle their further batches at each page dwell;
        // live screens refresh theirs on the refresh grid). This is the only path for every
        // command-capable screen — a batch build failure logs and the slot shows nothing new
        // (the panel holds its previous screen) rather than degrading to an inferior encoding.
        if (screenService instanceof CommandScreenService<?>
                && animationTransport.commandCapable(screenConfig.screenType(), screenConfig)) {
            @SuppressWarnings("unchecked")
            final CommandScreenService<ScreenConfig> capableService =
                    (CommandScreenService<ScreenConfig>) screenService;
            final CommandScreenService.RefreshStream refresh =
                    commandPublisher.buildRefresh(capableService, screenConfig, panelId);
            if (refresh != null) {
                commandPublisher.renderRefreshingCommandScreen(refresh, serial, epoch, screenConfig);
                return;
            }
            final CommandScreenService.BatchStream stream =
                    commandPublisher.buildBatches(capableService, screenConfig, panelId);
            if (stream != null) {
                commandPublisher.renderCommandScreen(stream, serial, epoch, screenConfig);
                return;
            }
        }

        // Frame path: user-uploaded bitmap streams (ANIMATION gifs, multi-frame CUSTOM
        // designs) played back through the panel's animation slots.
        if (screenService instanceof FrameScreenService<?>
                && screenConfig instanceof FrameScreenConfig frameScreenConfig && frameScreenConfig.producesFrames()) {
            @SuppressWarnings("unchecked")
            final FrameScreenService<FrameScreenConfig> frameService =
                    (FrameScreenService<FrameScreenConfig>) screenService;
            renderFrameScreen(serial, jobId, epoch, frameService, frameScreenConfig, screens, screenIdx);
            return;
        }

        // Retained static frame: single-image screens (IMAGE, single-frame CUSTOM designs).
        if (screenService instanceof StaticScreenService<?>) {
            final Optional<BufferedImage> screenImage = staticImage((StaticScreenService<ScreenConfig>) screenService, screenConfig);
            screenImage.ifPresent(image -> sendImageToPanel(serial, epoch, image));
            return;
        }

        log.warn("Screen {} on panel {} has nothing to display (no capable render path); holding the previous screen",
                screenConfig.screenType(), panelId);
    }

    @SuppressWarnings("unchecked")
    private static Optional<BufferedImage> staticImage(final StaticScreenService<ScreenConfig> service,
                                                       final ScreenConfig screenConfig) {
        return service.renderScreen(screenConfig);
    }

    private <T extends FrameScreenConfig> void renderFrameScreen(final String serial, final String jobId,
                                                                 final long epoch,
                                                                 final FrameScreenService<T> screenService,
                                                                 final T screenConfig,
                                                                 final List<? extends ScreenConfig> screens,
                                                                 final int screenIdx) {
        final FrameScreenService.FrameStream stream = screenService.renderFrameStream(screenConfig);
        final List<BufferedImage> frames = stream.frames();
        final long frameDelayMs = stream.frameDelayMs();
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();

        final boolean playbackStarted =
                animationTransport.commitAtBoundary(serial, jobId, screenIdx, screens, frames, frameDelayMs);
        if (playbackStarted) {
            // Clear the retained base-topic image: while an animation is the current display
            // there is no static frame to retain, and a stale one (e.g. no_config_found.png
            // from before the panel was configured — animation-only rotations never publish
            // static frames, so it lingers forever) would be delivered to the panel on boot
            // and stop its auto-resumed slot animation. An empty retained publish clears it;
            // live panels ignore zero-length base-topic payloads.
            mqttTransport.publish(serial, new byte[0], 1, true);
        }

        previewStreamer.archivePng(serial, frames.getFirst());
        try {
            previewStreamer.scaleImageAndPublish(serial, frames.getFirst());
        } catch (IOException e) {
            log.error("Failed to broadcast animation first frame for panel serial {}", serial, e);
        }
        final long deadlineNanos = System.nanoTime() + slotNanos;
        Thread.startVirtualThread(() ->
                previewStreamer.streamAnimationPreview(serial, epoch, frames, frameDelayMs, deadlineNanos));
    }

    private void sendImageToPanel(final String serial, final long epoch, final BufferedImage screenImage) {
        // Safe point: a superseded run must not publish its (now stale) frame.
        if (rotationStateService.currentEpoch(serial) != epoch) {
            log.debug("Superseded render skipped its static frame publish for panel {}", serial);
            return;
        }
        mqttTransport.publish(serial, imageService.bufferedImageToBytes(screenImage, 0, 0), 1, true);
        previewStreamer.archivePng(serial, screenImage);
        try {
            previewStreamer.scaleImageAndPublish(serial, screenImage);
        } catch (IOException e) {
            log.error("Failed to broadcast static frame for panel serial {}", serial, e);
        }
    }

    private void sendNoConfigImage(final String serial, final long epoch) {
        if (rotationStateService.currentEpoch(serial) != epoch) {
            return;
        }
        final BufferedImage missingConfigImage = imageService.imageToBufferedImage("assets/images/no_config_found.png");
        mqttTransport.publish(serial, imageService.bufferedImageToBytes(missingConfigImage, 0, 0), 1, true);
        try {
            previewStreamer.scaleImageAndPublish(serial, missingConfigImage);
        } catch (IOException e) {
            log.error("Failed to broadcast no-config frame for panel serial {}", serial, e);
        }
    }
}
