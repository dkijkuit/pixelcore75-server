package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyAlbumArtClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual design-preview utility (Color565Utils precedent): renders every Spotify
 * screen state, prints an ASCII view to stdout (for reviewing layout in a
 * terminal) and writes 8x-scaled PNGs to generated_images/ for eyeballing.
 */
class SpotifyDesignPreviewDump {

    private static final String ART_URL = "https://example/cover.jpg";

    private SpotifyScreenService service;
    private SpotifyPlaybackClient client;
    private SpotifyAlbumArtClient albumArtClient;

    private void setUp() throws Exception {
        final Font ledBoard = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/EXEPixelPerfect.ttf"))
                .deriveFont(16f);
        final Font cgPixel = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/cg-pixel-4x5.ttf"))
                .deriveFont(5f);
        client = mock(SpotifyPlaybackClient.class);
        albumArtClient = mock(SpotifyAlbumArtClient.class);
        service = new SpotifyScreenService(new PaintToolsService(null, null, ledBoard),
                ledBoard, cgPixel, client, albumArtClient,
                SpotifyScreenService.DEFAULT_REFRESH_MS);
        when(albumArtClient.artFor(ART_URL)).thenReturn(syntheticArt());
    }

    private static SpotifyAlbumArtClient.AlbumArt syntheticArt() {
        // 4-color "cover": orange sky, magenta band, deep blue bottom-left, teal corner.
        final int[] px = new int[32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                final int c;
                if (y < 14) {
                    c = Rgb565.of(235, 120, 40);
                } else if (y < 22) {
                    c = Rgb565.of(190, 40, 150);
                } else if (x < 16) {
                    c = Rgb565.of(30, 40, 140);
                } else {
                    c = Rgb565.of(20, 130, 120);
                }
                px[y * 32 + x] = c;
            }
        }
        return new SpotifyAlbumArtClient.AlbumArt(32, px);
    }

    private static SpotifyPlayback playback(final boolean playing, final String title, final String artist,
                                            final Long progressMs, final Long durationMs, final String artUrl) {
        return new SpotifyPlayback(true, playing, title, artist, artUrl,
                progressMs, durationMs, Instant.now());
    }

    private SpotifyScreenConfig config() {
        return new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, 10, 250, true);
    }

    /* ------------------- ASCII view ------------------- */

    private static char glyph(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        final int max = Math.max(r, Math.max(g, b));
        final int min = Math.min(r, Math.min(g, b));
        if (max < 40) {
            return '.';
        }
        if (max > 190 && max - min < 40) {
            return '#';
        }
        if (max - min < 30) {
            return '+'; // mid grey
        }
        if (g == max) {
            return r > 120 ? 'O' : 'G'; // orange-ish vs green
        }
        if (r == max) {
            return b > 120 ? 'M' : 'R'; // magenta-ish vs red
        }
        return g > 100 ? 'T' : 'B'; // teal-ish vs blue
    }

    private static String ascii(final BufferedImage image) {
        final StringBuilder sb = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                sb.append(glyph(image.getRGB(x, y)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String ascii565(final int[] rgb565) {
        final StringBuilder sb = new StringBuilder();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                sb.append(glyph(SpotifyAlbumArtClientExpand(rgb565[y * 16 + x])));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int SpotifyAlbumArtClientExpand(final int c565) {
        final int r5 = (c565 >> 11) & 0x1F;
        final int g6 = (c565 >> 5) & 0x3F;
        final int b5 = c565 & 0x1F;
        return ((r5 << 3) | (r5 >> 2)) << 16 | ((g6 << 2) | (g6 >> 4)) << 8 | (b5 << 3) | (b5 >> 2);
    }

    /* ------------------- PNG dump ------------------- */

    private static void dump(final String name, final BufferedImage image) throws Exception {
        final var dir = new File("generated_images");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        final var scaled = new BufferedImage(64 * 8, 32 * 8, BufferedImage.TYPE_INT_RGB);
        final Graphics g = scaled.getGraphics();
        g.drawImage(image, 0, 0, 64 * 8, 32 * 8, null);
        g.dispose();
        ImageIO.write(scaled, "png", new File(dir, "spotify-design-" + name + ".png"));
    }

    private void state(final String name, final SpotifyPlayback playback, final int frameIdx) throws Exception {
        when(client.getCurrentlyPlaying()).thenReturn(playback);
        final var frames = service.renderFrames(config());
        final BufferedImage frame = frames.get(Math.min(frameIdx, frames.size() - 1));
        dump(name, frame);
        System.out.println("=== " + name + " ===\n" + ascii(frame));
    }

    @Test
    void dumpStates() throws Exception {
        setUp();
        state("playing-art", playback(true, "Blinding Lights", "The Weeknd", 42_000L, 200_000L, ART_URL), 4);
        state("playing-art-scroll", playback(true, "Everything In Its Right Place", "Radiohead, Johnny Greenwood",
                90_000L, 249_000L, ART_URL), 20);
        state("playing-noart", playback(true, "Blinding Lights", "The Weeknd", 42_000L, 200_000L, null), 0);
        state("paused-art", playback(false, "Everything In Its Right Place", "Radiohead",
                90_000L, 249_000L, ART_URL), 0);
        state("idle", SpotifyPlayback.idle(), 0);
        state("not-connected", SpotifyPlayback.notConnected(), 0);
    }

    /** Circle-aware ASCII: ' ' outside, '.' black-inside, 'G' green. Ruler-indexed. */
    private static String asciiLogo(final int[] rgb565) {
        final StringBuilder sb = new StringBuilder("   |0123456789ABCDEF\n");
        for (int y = 0; y < 16; y++) {
            sb.append(String.format("%2d |", y));
            for (int x = 0; x < 16; x++) {
                final double dx = x - 7.5;
                final double dy = y - 7.5;
                if (dx * dx + dy * dy > 7.5 * 7.5) {
                    sb.append(' ');
                    continue;
                }
                sb.append(rgb565[y * 16 + x] == 0 ? '.' : 'G');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void dumpLogoVariants() {
        System.out.println("=== logo: hand-drawn bitmap (service) ===");
        System.out.println(asciiLogo(SpotifyScreenService.spotifyLogo(green())));
    }

    private static int green() {
        return Rgb565.of(new Color(30, 215, 96));
    }
}
