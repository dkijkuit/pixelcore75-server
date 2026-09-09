package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The control plane for panel rotation jobs (config save, panel register, panel delete,
 * startup reconciliation). Every enqueue mints a fresh admission ticket via
 * {@link RotationStateService#kick}; delete removes the pending successor job and
 * tombstones the per-serial state so any waking orphan exits silently.
 *
 * <p>Depends on the {@link PanelRepository} (not {@code Px75PanelService}) so the
 * dependency arrow stays one-directional: the panel service calls into this control for
 * the delete path, while this control only reads panel rows.
 */
@Slf4j
@Service
public class PanelRotationControl {

    static final String GENERATED_IMAGES_DIR = "generated_images";

    private final PanelRepository panelRepository;
    private final RotationStateService rotationStateService;
    private final PanelRotationJob panelRotationJob;
    private final JobScheduler jobScheduler;
    private final ImageBroadcasterService imageBroadcasterService;
    private final PreviewStreamer previewStreamer;

    private final Duration startupStepDelay = Duration.ofMillis(250);
    private final AtomicInteger startupIndex = new AtomicInteger(0);

    public PanelRotationControl(final PanelRepository panelRepository,
                                final RotationStateService rotationStateService,
                                final PanelRotationJob panelRotationJob,
                                final JobScheduler jobScheduler,
                                final ImageBroadcasterService imageBroadcasterService,
                                final PreviewStreamer previewStreamer) {
        this.panelRepository = panelRepository;
        this.rotationStateService = rotationStateService;
        this.panelRotationJob = panelRotationJob;
        this.jobScheduler = jobScheduler;
        this.imageBroadcasterService = imageBroadcasterService;
        this.previewStreamer = previewStreamer;
    }

    /**
     * Config save / panel register (the caller has done the ownership check): admit a fresh
     * immediate run for the serial. The old run (in flight or pending as the next slot's
     * successor) loses admission and exits at its next safe point — replacement without
     * interrupts.
     */
    public void schedulePanelScreenJob(final String serial) {
        final String jobId = rotationStateService.kick(serial);
        log.info("Scheduling panel rotation for panel {} (job {})", serial, jobId);
        jobScheduler.enqueue(UUID.fromString(jobId),
                () -> panelRotationJob.execute(serial, jobId));
    }

    /**
     * Panel delete: stop the rotation BEFORE the rows go (without this the job would keep
     * publishing the meanwhile-deleted config on the panel's serial topic forever), then
     * drop the per-serial transient state (epoch map, staged marker, SSE archive cache).
     */
    public void stopBeforeDelete(final String serial) {
        final RotationStateService.RotationState state = rotationStateService.stateFor(serial);
        final String pendingJobId;
        synchronized (state) {
            pendingJobId = state.allowedJobId.get();
            rotationStateService.stoppedByDelete(serial);
        }
        if (pendingJobId != null) {
            // The pending successor (if any) must not wake up for a deleted panel.
            try {
                jobScheduler.delete(UUID.fromString(pendingJobId));
            } catch (RuntimeException e) {
                log.debug("Pending successor job {} for panel {} already gone", pendingJobId, serial);
            }
        }
        rotationStateService.evict(serial);
        // Per-serial in-memory maps must not leak on panel delete (plan §7 perf 5).
        imageBroadcasterService.removeSerial(serial);
        previewStreamer.forgetArchivedPng(serial);
    }

    /** Serial change on PATCH: the old serial's rotation must die, the new one must start. */
    public void onSerialChanged(final String oldSerial, final String newSerial) {
        log.info("Panel serial changed from {} to {}; restarting its rotation", oldSerial, newSerial);
        stopBeforeDelete(oldSerial);
        schedulePanelScreenJob(newSerial);
    }

    /**
     * Startup reconciliation: for every panel in the DB, mint a fresh admission ticket and
     * enqueue an immediate run. A successor persisted before a crash wakes up later, sees a
     * foreign ticket and dies — reconciliation is idempotent and self-healing. A small
     * stagger spreads the broker connections.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileStartup() throws IOException {
        log.info("Reconciling panel rotation jobs...");

        final File tmpImagesDir = Path.of(GENERATED_IMAGES_DIR).toFile();
        if (!tmpImagesDir.exists() && !tmpImagesDir.mkdir()) {
            throw new IOException("Failed to create generated images directory");
        }

        final List<Px75Panel> panels = panelRepository.findAll();
        for (final Px75Panel px75Panel : panels) {
            final String serial = px75Panel.getSerial();
            final String jobId = rotationStateService.kick(serial);
            final Duration delay = startupStepDelay.multipliedBy(startupIndex.getAndIncrement());
            log.info("--> Scheduling panel job for panelId: {} (job {}, in {} ms)",
                    px75Panel.getPanelId(), jobId, delay.toMillis());
            jobScheduler.schedule(UUID.fromString(jobId), Instant.now().plus(delay),
                    () -> panelRotationJob.execute(serial, jobId));
        }

        log.info("*************************************************************");
        log.info(" ALL PANEL ROTATION JOBS RECONCILED ({} panels)", panels.size());
        log.info("*************************************************************");
    }
}
