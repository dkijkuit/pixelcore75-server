package nl.ctasoftware.crypto.ticker.server.model.panel.config;

/**
 * A screen config that can render as a frame stream played back through the panel's
 * animation slots (ANIM/ANIF/ANIP protocol).
 */
public sealed interface FrameScreenConfig extends ScreenConfig permits AnimationScreenConfig, AircraftScreenConfig {

    int MIN_FRAME_DELAY_MS = 10;

    /** Frame delay for playback (u16 millis on the panel). */
    int frameDelayMs();

    /**
     * True when this config currently renders as a frame stream ({@code renderFrames});
     * false means the static {@code renderScreen} path is used instead.
     */
    boolean producesFrames();

    /**
     * True when the job may pre-render and upload this screen during the previous slot
     * (staging). False forces rendering at the slot boundary, for screens whose frames
     * must reflect the latest data at display time.
     */
    boolean stageAhead();
}
