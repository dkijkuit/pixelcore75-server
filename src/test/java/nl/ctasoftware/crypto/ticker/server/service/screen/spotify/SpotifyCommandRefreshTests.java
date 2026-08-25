package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyAlbumArtClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ACMD live-refresh path (radar's pattern): a playing slot must return a
 * RefreshStream whose republished batches advance the progress bar and time line
 * from fresh fetches, while idle/not-connected slots keep the single-batch path.
 * The 80-frame inline ANIM upload (~10 s per slot) is the failure mode this
 * replaces — no frame traffic at all while a Spotify slot displays.
 */
class SpotifyCommandRefreshTests {

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
                ledBoard, cgPixel, client, mock(SpotifyAlbumArtClient.class),
                SpotifyScreenService.DEFAULT_REFRESH_MS);
        config = new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 20, 250, true);
    }

    private static SpotifyPlayback playing(final String title, final long progressMs, final long durationMs) {
        return new SpotifyPlayback(true, true, title, "Artist", null, progressMs, durationMs, Instant.now());
    }

    /** Green (Spotify bar) pixels in the bar band — one per bar-width column, 2 rows tall. */
    private static int barGreenPixels(final byte[] batch) {
        final BufferedImage frame = AcmdMirror.toBufferedImage(AcmdMirror.parse(batch).baseFrame());
        int green = 0;
        for (int y = SpotifyScreenService.PROGRESS_BAR_Y; y < SpotifyScreenService.CANVAS_HEIGHT; y++) {
            for (int x = 0; x < SpotifyScreenService.CANVAS_WIDTH; x++) {
                final int rgb = frame.getRGB(x, y);
                final Color c = new Color(rgb);
                if (c.getGreen() > 100 && c.getGreen() > c.getRed() && c.getGreen() > c.getBlue()) {
                    green++;
                }
            }
        }
        return green;
    }

    @Test
    void configStagesAheadSoMultiScreenRotationsAvoidTheInlineUpload() {
        assertTrue(config.stageAhead(),
                "the ~330KB frame loop must upload during the previous slot, not inline at the boundary");
    }

    @Test
    void playingSlotReturnsALiveRefreshStream() {
        when(client.getCurrentlyPlaying()).thenReturn(playing("Song", 60_000, 240_000));

        final CommandScreenService.RefreshStream refresh = service.renderCommandRefresh(config);
        assertNotNull(refresh);
        assertEquals(SpotifyScreenService.RENDER_TICK_MS, refresh.refreshMs(),
                "the time line ticks per second, not per (slower) fetch cadence");
        assertEquals(2 * 16, barGreenPixels(refresh.firstBatch()), "60s/240s → 16px bar");
    }

    @Test
    void refreshBatchesAdvanceTheBarFromBudgetedFetches() {
        when(client.getCurrentlyPlaying()).thenReturn(playing("Song", 60_000, 240_000));
        when(client.getCurrentlyPlaying(SpotifyScreenService.DEFAULT_REFRESH_MS))
                .thenReturn(playing("Song", 120_000, 240_000));

        final CommandScreenService.RefreshStream refresh = service.renderCommandRefresh(config);
        final byte[] next = refresh.nextBatches().get();

        // The supplier renders one tick ahead of its grid point (pipelined
        // lead): 120s + 1s projected → 32px, not the fetch-time 32px flat.
        assertEquals(2 * 32, barGreenPixels(next), "120s+1s projected / 240s → 32px bar");
        assertTrue(barGreenPixels(next) > barGreenPixels(refresh.firstBatch()));
        verify(client).getCurrentlyPlaying(SpotifyScreenService.DEFAULT_REFRESH_MS);
    }

    @Test
    void pausedStillRefreshesIdleAndNotConnectedKeepTheSingleBatchPath() {
        // Paused keeps the live stream: play may resume mid-slot and the refresh
        // grid should pick it up within one interval.
        when(client.getCurrentlyPlaying()).thenReturn(
                new SpotifyPlayback(true, false, "Song", "Artist", null, 60_000, 240_000, Instant.now()));
        assertNotNull(service.renderCommandRefresh(config));

        when(client.getCurrentlyPlaying()).thenReturn(SpotifyPlayback.idle());
        assertNull(service.renderCommandRefresh(config), "idle: no live value");

        when(client.getCurrentlyPlaying()).thenReturn(SpotifyPlayback.notConnected());
        assertNull(service.renderCommandRefresh(config), "not connected: no live value");
    }

    @Test
    void longTitleArmsAScrollShortTitleStaysStatic() {
        when(client.getCurrentlyPlaying()).thenReturn(playing("A Very Long Track Title Indeed", 0, 240_000));
        assertTrue(AcmdMirror.parse(service.renderCommandRefresh(config).firstBatch()).hasParametric(),
                "overflowing title becomes a SCROLL the panel ticks locally");

        when(client.getCurrentlyPlaying()).thenReturn(playing("Short", 0, 240_000));
        final byte[] batch = service.renderCommandBatch(config);
        assertTrue(AcmdMirror.parse(batch).baseFrame().length > 0);
        assertEquals(0, AcmdMirror.parse(batch).parametricCount(), "fitting title renders as plain TEXT");
    }
}
