package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * A ScreenService whose screens render as retained static frames (user-uploaded bitmaps:
 * ANIMATION, IMAGE, and single-frame/non-parametric CUSTOM designs). Screens with an ACMD
 * command rendering do not implement this — the command batch is their only path.
 */
public interface StaticScreenService<T extends ScreenConfig> extends ScreenService<T> {

    Optional<BufferedImage> renderScreen(T screenConfig);
}
