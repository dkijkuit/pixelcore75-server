package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import java.util.List;

public record AnimationScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        int frameDelayMs,
        List<String> frames,
        boolean disabled
) implements FrameScreenConfig {

    public AnimationScreenConfig(final ScreenType screenType, final int durationSeconds,
                                 final int frameDelayMs, final List<String> frames) {
        this(screenType, durationSeconds, frameDelayMs, frames, false);
    }

    @Override
    public boolean producesFrames() {
        return true;
    }

    @Override
    public boolean stageAhead() {
        return true;
    }
}
