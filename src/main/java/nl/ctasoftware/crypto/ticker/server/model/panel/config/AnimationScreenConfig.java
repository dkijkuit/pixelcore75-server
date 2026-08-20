package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import java.util.List;

public record AnimationScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        int frameDelayMs,
        List<String> frames
) implements FrameScreenConfig {

    @Override
    public boolean producesFrames() {
        return true;
    }

    @Override
    public boolean stageAhead() {
        return true;
    }
}
