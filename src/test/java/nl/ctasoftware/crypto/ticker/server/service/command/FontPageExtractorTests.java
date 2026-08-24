package nl.ctasoftware.crypto.ticker.server.service.command;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTF&rarr;FONT-page extraction: deterministic byte output (same JVM, same bytes every
 * run), wire-shape conformance (pageId, glyphCount, ascending ASCII 32..126, dims within
 * the 32px protocol limit), using the same TTFs the {@code PaintConfig} beans load.
 */
class FontPageExtractorTests {

    @Test
    void extractionIsDeterministicAcrossRuns() {
        final File ttf = new File("assets/fonts/cg-pixel-4x5.ttf");
        final byte[] first = FontPageExtractor.payload(0, FontPageExtractor.loadFont(ttf, 5f));
        final byte[] second = FontPageExtractor.payload(0, FontPageExtractor.loadFont(ttf, 5f));

        assertArrayEquals(first, second);
        assertTrue(first.length > 2, "page must carry glyphs");
    }

    @Test
    void payloadFollowsWireShape() {
        final File ttf = new File("assets/fonts/cg-pixel-4x5.ttf");
        final byte[] payload = FontPageExtractor.payload(2, FontPageExtractor.loadFont(ttf, 5f));

        int pos = 0;
        assertEquals(2, payload[pos++], "pageId");
        final int glyphCount = payload[pos++] & 0xFF;
        assertEquals(95, glyphCount, "ASCII 32..126");

        int prevCode = -1;
        for (int i = 0; i < glyphCount; i++) {
            final int code = payload[pos] & 0xFF;
            final int w = payload[pos + 1] & 0xFF;
            final int h = payload[pos + 2] & 0xFF;
            final int bitmapLen = h * ((w + 7) >> 3);
            assertTrue(code > prevCode, "glyphs in ascending ASCII order");
            assertTrue(code >= 32 && code <= 126, "ASCII 32..126");
            assertTrue(w >= 1 && w <= AcmdOpcode.GLYPH_MAX_DIM, "w 1..32, got " + w);
            assertTrue(h >= 1 && h <= AcmdOpcode.GLYPH_MAX_DIM, "h 1..32, got " + h);
            pos += 6 + bitmapLen;
            prevCode = code;
        }
        assertEquals(payload.length, pos, "payload consumed exactly");
    }

    @Test
    void blankGlyphsBecomeEmptyOnePixelBitmaps() {
        final File ttf = new File("assets/fonts/cg-pixel-4x5.ttf");
        final byte[] payload = FontPageExtractor.payload(0, FontPageExtractor.loadFont(ttf, 5f));

        // space (code 32) is the first glyph: w=h=1, all-zero bitmap, offsets irrelevant
        assertEquals(32, payload[2]);
        assertEquals(1, payload[3]);
        assertEquals(1, payload[4]);
        final int bitmapLen = 1 * ((1 + 7) >> 3);
        for (int i = 0; i < bitmapLen; i++) {
            assertEquals(0, payload[8 + i], "blank glyph bitmap must be zero");
        }
    }

    @Test
    void extractedPageRoundtripsThroughTheWire() {
        final File ttf = new File("assets/fonts/cg-pixel-4x5.ttf");
        final byte[] payload = FontPageExtractor.payload(1, FontPageExtractor.loadFont(ttf, 5f));
        final byte[] framed = CommandBatch.builder()
                .nop()
                .fontPagePayload(1, payload)
                .build();

        final var parsed = AcmdParser.parse(framed);
        assertEquals(2, parsed.commands().size());
        final AcmdCommand.FontPage page = (AcmdCommand.FontPage) parsed.commands().get(1);
        assertEquals(1, page.pageId());
        assertEquals(95, page.glyphs().size());
    }
}
