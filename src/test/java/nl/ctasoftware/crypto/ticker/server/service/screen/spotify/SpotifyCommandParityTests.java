package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyAlbumArtClient;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden-image parity between the frame path and the ACMD command path for the
 * states where both render the same content: album art (BLIT vs painted RGB565
 * block — identical by construction), static fitting text (AWT drawString vs the
 * extracted glyph pages — measured 0 on the pixel fonts), the logo mark and the
 * bar FILLs. Scrolling marquees intentionally differ (frame wrap-around vs ACMD
 * ping-pong) and are covered by the refresh tests instead.
 */
class SpotifyCommandParityTests {

    /** Same tolerance budget family as the clock/aircraft parity suites (95%). */
    private static final int BUDGET_PX = 102;

    private static final String ART_URL = "https://example/cover.jpg";

    private SpotifyScreenService service;
    private SpotifyPlaybackClient client;
    private SpotifyAlbumArtClient albumArt;
    private SpotifyScreenConfig config;

    @BeforeEach
    void setUp() throws IOException, FontFormatException {
        final Font ledBoard = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/EXEPixelPerfect.ttf"))
                .deriveFont(16f);
        final Font cgPixel = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/cg-pixel-4x5.ttf"))
                .deriveFont(5f);
        client = mock(SpotifyPlaybackClient.class);
        albumArt = mock(SpotifyAlbumArtClient.class);
        when(albumArt.artFor(ART_URL)).thenReturn(syntheticArt());
        service = new SpotifyScreenService(new PaintToolsService(null, null, ledBoard),
                ledBoard, cgPixel, client, albumArt, SpotifyScreenService.DEFAULT_REFRESH_MS);
        config = new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true);
    }

    /** 3-color "cover" — enough structure to prove the BLIT/paint paths align. */
    private static SpotifyAlbumArtClient.AlbumArt syntheticArt() {
        final int[] px = new int[32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                px[y * 32 + x] = y < 16
                        ? Rgb565.of(200, 60, 40)
                        : x < 16 ? Rgb565.of(30, 40, 150) : Rgb565.of(20, 130, 110);
            }
        }
        return new SpotifyAlbumArtClient.AlbumArt(32, px);
    }

    private static SpotifyPlayback playing(final boolean playing) {
        // 61.5s in: a few ms of fetch-to-render lag cannot cross the second
        // boundary, so the time text is stable across both render paths.
        return new SpotifyPlayback(true, playing, "Song", "Artist", ART_URL,
                61_500, 240_000, Instant.now());
    }

    private int mismatches(final SpotifyPlayback playback) {
        when(client.getCurrentlyPlaying()).thenReturn(playback);
        final BufferedImage frame = service.renderFrames(config).getFirst();
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);
        return FrameParity.mismatchedPixels(FrameParity.rgb565(frame), command);
    }

    @Test
    void playingWithArtMatchesAcrossPaths() {
        final int mismatched = mismatches(playing(true));
        assertTrue(mismatched <= BUDGET_PX, "playing state: " + mismatched + " px differ (budget " + BUDGET_PX + ")");
    }

    @Test
    void pausedWithArtMatchesAcrossPaths() {
        final int mismatched = mismatches(playing(false));
        assertTrue(mismatched <= BUDGET_PX, "paused state: " + mismatched + " px differ (budget " + BUDGET_PX + ")");
    }

    @Test
    void idleAndNotConnectedLogoPagesMatchAcrossPaths() {
        when(client.getCurrentlyPlaying()).thenReturn(SpotifyPlayback.idle());
        int mismatched = FrameParity.mismatchedPixels(
                FrameParity.rgb565(service.renderFrames(config).getFirst()),
                AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0));
        assertTrue(mismatched <= BUDGET_PX, "idle logo page: " + mismatched + " px differ");

        when(client.getCurrentlyPlaying()).thenReturn(SpotifyPlayback.notConnected());
        mismatched = FrameParity.mismatchedPixels(
                FrameParity.rgb565(service.renderFrames(config).getFirst()),
                AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0));
        assertTrue(mismatched <= BUDGET_PX, "not-connected logo page: " + mismatched + " px differ");
    }

    @Test
    void hiddenAlbumArtRendersTheFullWidthLayoutWithoutFetchingArt() {
        when(client.getCurrentlyPlaying()).thenReturn(playing(true));
        final SpotifyScreenConfig noArt =
                new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true, false);

        final int[] command = AcmdMirror.parse(service.renderCommandBatch(noArt)).frameAt(0);
        final Set<Integer> artColors = Set.of(
                Rgb565.of(200, 60, 40), Rgb565.of(30, 40, 150), Rgb565.of(20, 130, 110));
        for (final int pixel : command) {
            assertFalse(artColors.contains(pixel & 0xFFFF), "no synthetic-art color may appear");
        }
        assertEquals(0, FrameParity.mismatchedPixels(
                FrameParity.rgb565(service.renderFrames(noArt).getFirst()), command),
                "hidden art: frame and command paths render the same full-width layout");
        verify(albumArt, never()).artFor(ART_URL);
    }
}
