package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A ScreenService whose screens can render as a frame stream, see {@link FrameScreenConfig}.
 */
public interface FrameScreenService<T extends FrameScreenConfig> extends ScreenService<T> {

    List<BufferedImage> renderFrames(T screenConfig);

    /**
     * Playback delay the panel uses between frames for this screen. Defaults to the
     * config's {@code frameDelayMs}; services with coarse frames (e.g. info pages that
     * only change every few seconds) override it so playback runs at the page rate
     * regardless of the smoothness value configured for animation-heavy modes.
     */
    default long playbackFrameDelayMs(final T screenConfig) {
        return Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS, screenConfig.frameDelayMs());
    }

    /** Rendered frames plus the delay they are meant to be played back at. */
    record FrameStream(List<BufferedImage> frames, long frameDelayMs) {
    }

    /**
     * Renders the frame stream in one go: services whose playback delay depends on the
     * rendered content (e.g. page counts resolved from live data) override this so the
     * delay always matches the frames actually produced.
     */
    default FrameStream renderFrameStream(final T screenConfig) {
        return new FrameStream(renderFrames(screenConfig), playbackFrameDelayMs(screenConfig));
    }
}
