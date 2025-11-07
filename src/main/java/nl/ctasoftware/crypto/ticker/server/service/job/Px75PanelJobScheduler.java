package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class Px75PanelJobScheduler implements PanelJobScheduler {
    public static final String GENERATED_IMAGES_DIR = "generated_images";

    final Px75PanelConfigService px75PanelConfigService;
    final Px75PanelService px75PanelService;
    final List<ScreenService<? extends ScreenConfig>> screenServices;
    final ImageService imageService;
    final IMqttClient mqttClient;
    final ImageBroadcasterService imageBroadcasterService;
    final JobSchedulerService jobSchedulerService;
    final Duration stepDelay = Duration.ofMillis(250);
    final AtomicInteger index = new AtomicInteger(0);

    @Override
    public void schedulePanelScreenJob(final long panelId, final long userId) {
        final Px75Panel px75PanelForUser = px75PanelService.getPx75PanelForUser(userId, panelId);
        final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(panelId);

        jobSchedulerService.stop(px75PanelForUser.getSerial(), false);

        log.info("Scheduling PanelScreenJob for user {} for panel {}", userId, panelId);

        final PanelScreenJob panelScreenJob = new PanelScreenJob(px75PanelForUser, panelConfig, screenServices, imageService, mqttClient, imageBroadcasterService);
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
            final PanelScreenJob panelScreenJob = new PanelScreenJob(px75Panel, panelConfig, screenServices, imageService, mqttClient, imageBroadcasterService);

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
