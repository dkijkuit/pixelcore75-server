package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;

import java.awt.image.BufferedImage;
import java.util.Optional;

public interface ScreenService<T extends ScreenConfig> {
    ScreenType getScreenType();
    Optional<BufferedImage> renderScreen(T screenConfig);
}
