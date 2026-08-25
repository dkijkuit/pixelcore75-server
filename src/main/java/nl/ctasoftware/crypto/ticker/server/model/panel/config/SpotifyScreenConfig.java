package nl.ctasoftware.crypto.ticker.server.model.panel.config;

/**
 * Spotify Now Playing: title (scrolling marquee when long), artist, elapsed/total
 * time and a progress bar driven by the Web API's playback state. One shared
 * connection per server (OAuth PKCE, see the Spotify service package).
 */
public record SpotifyScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        int frameDelayMs,
        boolean showIdleScreen
) implements FrameScreenConfig {

    @Override
    public boolean producesFrames() {
        // The marquee and progress bar animate.
        return true;
    }

    @Override
    public boolean stageAhead() {
        // The ~330KB frame loop (80 frames × 4096B) takes ~10s inline — the panel
        // drains 256B/loop — so multi-screen rotations must upload during the
        // previous slot (same reasoning as the radar). The command path avoids
        // frame uploads entirely; this covers its frame fallback. A single-screen
        // Spotify rotation still uploads inline (a slot cannot stage itself).
        return true;
    }
}
