package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.ScreenServiceNotFoundException;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.*;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.clock.ClockScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.CryptoScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.date.DateScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.image.ImageScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatchService;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.WeatherScreenService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class PanelScreenJob implements ReschedulableJob {
    final Px75Panel px75Panel;
    final Px75PanelConfig panelConfig;
    final AtomicInteger screenIndex;
    final ImageService imageService;
    final IMqttClient mqttClient;
    final ImageBroadcasterService imageBroadcasterService;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Getter
    final String id;

    private final Map<ScreenType, ScreenService<? extends ScreenConfig>> screenServices;

    public PanelScreenJob(final Px75Panel px75Panel, final Px75PanelConfig panelConfig,
                          final List<ScreenService<? extends ScreenConfig>> screenServices,
                          final ImageService imageService, final IMqttClient mqttClient,
                          final ImageBroadcasterService imageBroadcasterService) {
        this.px75Panel = px75Panel;
        this.panelConfig = panelConfig;
        this.imageService = imageService;
        this.mqttClient = mqttClient;
        this.screenIndex = new AtomicInteger(-1);
        this.screenServices = screenServices.stream()
                .collect(Collectors.toUnmodifiableMap(ScreenService::getScreenType, Function.identity()));
        this.imageBroadcasterService = imageBroadcasterService;
        this.id = px75Panel.getSerial();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent e) {
        running.set(false);
    }

    @Override
    public Optional<Duration> run() {

        final List<? extends ScreenConfig> screensConfig = panelConfig.getScreensConfig();

        if (screensConfig == null || screensConfig.isEmpty()) {
            log.warn("----> No screen configs found, not scheduling screen jobs for panel: {}", panelConfig.getPanelId());
            final BufferedImage missingConfigImage = imageService.imageToBufferedImage("assets/images/no_config_found.png");
            try {
                sendImageToPanel(missingConfigImage);
                scaleImageAndPublish(missingConfigImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return Optional.empty();
        }

        if (!running.get()) {
            log.warn("Not re-scheduling screen jobs for panel, because it's ending: {}", panelConfig.getPanelId());
        }

        final ScreenConfig screenConfig = screensConfig.get(getScreenIndex());

        try {
            log.debug("----> Next screen job for panel: {}", panelConfig.getPanelId());
            renderScreen(screenConfig);
        } catch (Exception e) {
            log.error("Error rendering screen job for panel: {}", panelConfig.getPanelId(), e);
        }

        return Optional.of(Duration.ofSeconds(screenConfig.durationSeconds()));

    }

    void renderScreen(final ScreenConfig screenConfig) {
        final ScreenService<? extends ScreenConfig> screenService = screenServices.get(screenConfig.screenType());
        if (screenService == null) {
            throw new ScreenServiceNotFoundException(screenConfig.screenType());
        }

        log.debug("------> Rendering screen {} for panel {}", screenConfig.screenType(), panelConfig.getPanelId());

        final Optional<BufferedImage> screenImage = switch (screenConfig) {
            case WeatherScreenConfig w -> ((WeatherScreenService) screenService).renderScreen(w);
            case CryptoScreenConfig c -> ((CryptoScreenService) screenService).renderScreen(c);
            case ImageScreenConfig i -> ((ImageScreenService) screenService).renderScreen(i);
            case SoccerMatchScreenConfig i -> ((SoccerMatchService) screenService).renderScreen(i);
            case ClockScreenConfig i -> ((ClockScreenService) screenService).renderScreen(i);
            case DateScreenConfig i -> ((DateScreenService) screenService).renderScreen(i);
            case Formula1ScreenConfig i -> ((Formula1ScreenService) screenService).renderScreen(i);
        };

        screenImage.ifPresent(this::sendImageToPanel);
    }

    private void sendImageToPanel(final BufferedImage screenImage) {
        try {
            final MqttMessage mqttMessage = new MqttMessage(imageService.bufferedImageToBytes(screenImage, 0, 0));
            mqttMessage.setRetained(true);
            mqttClient.publish(px75Panel.getSerial(), mqttMessage);

            final String imageFilename = "generated_images/" + px75Panel.getSerial() + ".png";
            ImageIO.write(screenImage, "PNG", new File(imageFilename));

            scaleImageAndPublish(screenImage);
        } catch (IOException | MqttException e) {
            throw new RuntimeException(e);
        }
    }

    private void scaleImageAndPublish(final BufferedImage image) throws IOException {
        final BufferedImage scaledImage = imageService.scale(image, 640, 320);
        imageBroadcasterService.updateLatest(px75Panel.getSerial(), scaledImage);
    }

    int getScreenIndex() {
        if (screenIndex.incrementAndGet() >= panelConfig.getScreensConfig().size()) {
            screenIndex.set(0);
        }

        return screenIndex.get();
    }
}
