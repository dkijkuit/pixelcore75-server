package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The album-art quantizer must fit any cover into BLIT's 16-color palette while
 * staying deterministic — both render paths paint these exact pixels.
 */
class SpotifyAlbumArtClientTests {

    private static final int GREEN565 = 0x07E0;
    private static final int RED565 = 0xF800;

    @Test
    void nullOrBlankUrlYieldsNoArt() {
        final SpotifyAlbumArtClient client = new SpotifyAlbumArtClient();
        assertNull(client.artFor(null));
        assertNull(client.artFor(""));
        assertNull(client.artFor("   "));
    }

    @Test
    void albumArtRecordRejectsMalformedPixels() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpotifyAlbumArtClient.AlbumArt(16, new int[15]));
        assertThrows(IllegalArgumentException.class, () -> {
            final int[] outOfRange = new int[256];
            java.util.Arrays.fill(outOfRange, 0x10000); // above the RGB565 u16 ceiling
            new SpotifyAlbumArtClient.AlbumArt(16, outOfRange);
        });
        // well-formed passes
        new SpotifyAlbumArtClient.AlbumArt(2, new int[]{0, GREEN565, RED565, 0});
    }

    @Test
    void quantizeNeverExceedsThePaletteLimit() {
        final BufferedImage noisy = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                // pseudo-random gradient soup: far more than 16 distinct colors
                noisy.setRGB(x, y, ((x * 37 + y * 91) << 16) | ((x * y * 13) << 8) | (x * 17 + y * 29));
            }
        }
        final int[] out = SpotifyAlbumArtClient.quantizeDither(noisy);
        assertEquals(256, out.length);
        final Set<Integer> distinct = new HashSet<>();
        for (final int c : out) {
            assertTrue(c >= 0 && c <= 0xFFFF, "color must stay in the RGB565 u16 range");
            distinct.add(c);
        }
        assertTrue(distinct.size() <= SpotifyAlbumArtClient.PALETTE_LIMIT,
                "quantized art must be BLIT-encodable, got " + distinct.size() + " colors");
    }

    @Test
    void quantizeKeepsFlatColorsExactAndDeterministic() {
        final BufferedImage flat = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                flat.setRGB(x, y, x < 8 ? 0xFF0000 : 0x00FF00);
            }
        }
        final int[] first = SpotifyAlbumArtClient.quantizeDither(flat);
        final int[] second = SpotifyAlbumArtClient.quantizeDither(flat);
        assertEquals(Set.of(RED565, GREEN565), distinct(first), "flat colors survive undithered");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(RED565, first[y * 16 + x]);
                assertEquals(GREEN565, first[y * 16 + x + 8]);
            }
        }
        assertEquals(java.util.Arrays.hashCode(second), java.util.Arrays.hashCode(first),
                "quantization is deterministic");
    }

    private static Set<Integer> distinct(final int[] pixels) {
        final Set<Integer> set = new HashSet<>();
        for (final int c : pixels) {
            set.add(c);
        }
        return set;
    }
}
