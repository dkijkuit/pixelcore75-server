package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Docker-free render tests with a mocked playback client: the elapsed-time line
 * must tick with the interpolated progress instead of showing the slot-start
 * snapshot for the whole slot (the bar already moved — the text has to follow).
 */
class SpotifyScreenServiceFramesTests {

    private SpotifyPlaybackClient client;
    private SpotifyScreenService service;
    private SpotifyScreenConfig config;

    @BeforeEach
    void setUp() throws IOException, FontFormatException {
        final Font ledBoard = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/EXEPixelPerfect.ttf"))
                .deriveFont(16f);
        final Font cgPixel = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/cg-pixel-4x5.ttf"))
                .deriveFont(5f);
        client = mock(SpotifyPlaybackClient.class);
        service = new SpotifyScreenService(new PaintToolsService(null, null, ledBoard),
                ledBoard, cgPixel, client, SpotifyScreenService.DEFAULT_REFRESH_MS);
        config = new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true);
    }

    private SpotifyPlayback playing(final long progressMs, final long durationMs) {
        // Short title/artist: nothing marquees, so the time band below the artist
        // line can only change through the time text itself.
        return new SpotifyPlayback(true, true, "T", "A", progressMs, durationMs, Instant.now());
    }

    /** Vertical band of an image (the time-line rows sit above the progress bar). */
    private static int[] band(final BufferedImage image, final int y0, final int y1) {
        final int[] px = new int[image.getWidth() * (y1 - y0)];
        image.getRGB(0, y0, image.getWidth(), y1 - y0, px, 0, image.getWidth());
        return px;
    }

    @Test
    void rendersSlotAtConfiguredFrameRate() {
        when(client.getCurrentlyPlaying()).thenReturn(playing(5_000, 240_000));
        assertEquals(40, service.renderFrames(config).size()); // 10 s @ 250 ms
    }

    @Test
    void timeLineTicksWithInterpolatedProgressAcrossTheSlot() {
        when(client.getCurrentlyPlaying()).thenReturn(playing(5_000, 240_000));
        final var frames = service.renderFrames(config);
        final BufferedImage first = frames.getFirst();
        final BufferedImage last = frames.getLast();

        // The elapsed/total line ("0:05/4:00" → ~"0:14/4:00") must advance across
        // the slot: with static title/artist, the time band (between the artist
        // line and the progress bar) can only differ through the time text.
        assertFalse(Arrays.equals(band(first, 20, SpotifyScreenService.PROGRESS_BAR_Y),
                band(last, 20, SpotifyScreenService.PROGRESS_BAR_Y)),
                "elapsed-time text must tick, not show the slot-start snapshot");
    }

    @Test
    void pausedPlaybackFreezesBarAndTimeLine() {
        final SpotifyPlayback paused = new SpotifyPlayback(true, false, "T", "A",
                90_000, 240_000, Instant.now());
        when(client.getCurrentlyPlaying()).thenReturn(paused);
        final var frames = service.renderFrames(config);

        assertTrue(Arrays.equals(band(frames.getFirst(), 20, SpotifyScreenService.CANVAS_HEIGHT),
                band(frames.getLast(), 20, SpotifyScreenService.CANVAS_HEIGHT)),
                "paused: time line and bar stay frozen");
    }
}
