package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class Px75PanelJobScheduler implements PanelJobScheduler {
    public static final String GENERATED_IMAGES_DIR = "generated_images";

    final Px75PanelConfigService px75PanelConfigService;
    final Px75PanelService px75PanelService;
    final List<ScreenService<? extends ScreenConfig>> screenServices;
    final ImageService imageService;
    final IMqttClient mqttClient;
    final ImageBroadcasterService imageBroadcasterService;
    final AnimationLoadAckService animationLoadAckService;
    final JobSchedulerService jobSchedulerService;
    final Duration stepDelay = Duration.ofMillis(250);
    final AtomicInteger index = new AtomicInteger(0);

    /**
     * {@code pixelcore75.command-encoding.enabled} (default false, mixed-fleet safety:
     * old firmware does not subscribe to {@code <serial>/cmd}). Read once at startup —
     * toggling is a deploy-time decision (firmware first, then enable), not runtime.
     */
    final boolean commandEncodingEnabled;

    /** Shared across PanelScreenJob instances so a re-scheduled job supersedes stale preview streams. */
    final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();

    public Px75PanelJobScheduler(final Px75PanelConfigService px75PanelConfigService,
                                 final Px75PanelService px75PanelService,
                                 final List<ScreenService<? extends ScreenConfig>> screenServices,
                                 final ImageService imageService,
                                 final IMqttClient mqttClient,
                                 final ImageBroadcasterService imageBroadcasterService,
                                 final AnimationLoadAckService animationLoadAckService,
                                 final JobSchedulerService jobSchedulerService,
                                 @Value("${pixelcore75.command-encoding.enabled:false}") final boolean commandEncodingEnabled) {
        this.px75PanelConfigService = px75PanelConfigService;
        this.px75PanelService = px75PanelService;
        this.screenServices = screenServices;
        this.imageService = imageService;
        this.mqttClient = mqttClient;
        this.imageBroadcasterService = imageBroadcasterService;
        this.animationLoadAckService = animationLoadAckService;
        this.jobSchedulerService = jobSchedulerService;
        this.commandEncodingEnabled = commandEncodingEnabled;
    }

    @Override
    public void schedulePanelScreenJob(final long panelId, final long userId) {
        final Px75Panel px75PanelForUser = px75PanelService.getPx75PanelForUser(userId, panelId);
        final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(panelId);

        // Force: de oude run mag een lopende render publiceren nadat de nieuwe job al actief is;
        // onderbreek hem dus — de nieuwe job herbouwt en herstaged toch alles zelf.
        jobSchedulerService.stop(px75PanelForUser.getSerial(), true);

        log.info("Scheduling PanelScreenJob for user {} for panel {}", userId, panelId);

        final PanelScreenJob panelScreenJob = new PanelScreenJob(px75PanelForUser, panelConfig, screenServices, imageService, mqttClient, imageBroadcasterService, animationLoadAckService, previewGenerations, commandEncodingEnabled);
        jobSchedulerService.schedule(panelScreenJob, Duration.ZERO);
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStartup() throws IOException {
        log.info("Starting panel jobs...");

        final File tmpImagesDir = Path.of(GENERATED_IMAGES_DIR).toFile();
        if(!tmpImagesDir.exists() && !tmpImagesDir.mkdir()){
            throw new IOException("Failed to create generated images directory");
        }

        px75PanelService.getPx75Panels().forEach(px75Panel -> {
            int i = index.getAndIncrement();
            log.info("--> Starting panel job for panelId: {}", px75Panel.getPanelId());
            final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(px75Panel.getPanelId());
            final PanelScreenJob panelScreenJob = new PanelScreenJob(px75Panel, panelConfig, screenServices, imageService, mqttClient, imageBroadcasterService, animationLoadAckService, previewGenerations, commandEncodingEnabled);

            Duration delay = stepDelay.multipliedBy(i);
            jobSchedulerService.schedule(panelScreenJob, delay);
        });

        log.info("*************************************************************");
        log.info(" ALL PANEL JOBS SCHEDULED");
        log.info("*************************************************************");
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent e) {
        jobSchedulerService.stopAll();
    }
}
