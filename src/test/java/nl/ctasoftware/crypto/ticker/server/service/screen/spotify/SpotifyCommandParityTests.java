package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyAlbumArtClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the SPOTIFY command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, pinned per playback state — album-art BLIT, fitting text, the logo mark and
 * the bar FILLs.
 */
class SpotifyCommandParityTests {

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
        service = new SpotifyScreenService(ledBoard, cgPixel, client, albumArt, SpotifyScreenService.DEFAULT_REFRESH_MS);
        config = new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true);
    }

    /** 3-color "cover" — enough structure to prove the BLIT lands the art pixels. */
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
        // boundary, so the time text is stable across renders.
        return new SpotifyPlayback(true, playing, "Song", "Artist", ART_URL,
                61_500, 240_000, Instant.now());
    }

    private void assertGoldenFor(final String name, final SpotifyPlayback playback) {
        when(client.getCurrentlyPlaying()).thenReturn(playback);
        CommandGolden.assertGolden(name, CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    @Test
    void playingWithArtMatchesGolden() {
        assertGoldenFor("spotify-playing", playing(true));
    }

    @Test
    void pausedWithArtMatchesGolden() {
        assertGoldenFor("spotify-paused", playing(false));
    }

    @Test
    void idleLogoPageMatchesGolden() {
        assertGoldenFor("spotify-idle", SpotifyPlayback.idle());
    }

    @Test
    void notConnectedLogoPageMatchesGolden() {
        assertGoldenFor("spotify-notconnected", SpotifyPlayback.notConnected());
    }

    @Test
    void hiddenAlbumArtRendersTheFullWidthLayoutWithoutFetchingArt() {
        when(client.getCurrentlyPlaying()).thenReturn(playing(true));
        final SpotifyScreenConfig noArt =
                new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true, false);

        final int[] command = CommandGolden.frameAt(service.renderCommandBatch(noArt), 0);
        final Set<Integer> artColors = Set.of(
                Rgb565.of(200, 60, 40), Rgb565.of(30, 40, 150), Rgb565.of(20, 130, 110));
        for (final int pixel : command) {
            assertFalse(artColors.contains(pixel & 0xFFFF), "no synthetic-art color may appear");
        }
        CommandGolden.assertGolden("spotify-noart", command);
        verify(albumArt, never()).artFor(ART_URL);
    }
}
