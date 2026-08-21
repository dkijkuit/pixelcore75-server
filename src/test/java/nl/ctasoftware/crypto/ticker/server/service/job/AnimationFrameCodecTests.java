package nl.ctasoftware.crypto.ticker.server.service.job;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PAL_RLE frame codec for animation protocol v2 (plan §5): encode/decode roundtrips, the
 * per-frame minimum rule (RLE only when strictly smaller and at most 16 distinct colors),
 * and the wire-format invariants (first-seen palette order, zeroed unused entries, runs
 * never crossing the 64-px row boundaries) verified against hand-computed fixtures.
 */
class AnimationFrameCodecTests {

    private static final int COLOR_A = 0x1234;
    private static final int COLOR_B = 0x5678;

    private static int[] row(final int color) {
        final int[] row = new int[AnimationFrameCodec.ROW_PIXELS];
        Arrays.fill(row, color);
        return row;
    }

    private static int[] row(final int colorA, final int lenA, final int colorB, final int lenB) {
        final int[] row = new int[AnimationFrameCodec.ROW_PIXELS];
        Arrays.fill(row, 0, lenA, colorA);
        Arrays.fill(row, lenA, lenA + lenB, colorB);
        return row;
    }

    private static int[] alternatingRow(final int colorA, final int colorB) {
        final int[] row = new int[AnimationFrameCodec.ROW_PIXELS];
        for (int c = 0; c < AnimationFrameCodec.ROW_PIXELS; c++) {
            row[c] = (c & 1) == 0 ? colorA : colorB;
        }
        return row;
    }

    private static byte[] frame(final int[]... rows) {
        assertEquals(AnimationFrameCodec.FRAME_ROWS, rows.length);
        final byte[] frame = new byte[AnimationFrameCodec.FRAME_BYTES];
        int out = 0;
        for (final int[] row : rows) {
            for (final int color : row) {
                frame[out++] = (byte) color;
                frame[out++] = (byte) (color >> 8);
            }
        }
        return frame;
    }

    private static byte[] bodyOf(final int... runIdxPairs) {
        final byte[] body = new byte[AnimationFrameCodec.PALETTE_BYTES + runIdxPairs.length];
        for (int i = 0; i < runIdxPairs.length; i += 2) {
            body[AnimationFrameCodec.PALETTE_BYTES + i] = (byte) runIdxPairs[i];
            body[AnimationFrameCodec.PALETTE_BYTES + i + 1] = (byte) runIdxPairs[i + 1];
        }
        return body;
    }

    @Test
    void flatFrameEncodesToPalRleWithHandComputedBytes() {
        final byte[] frame = new byte[AnimationFrameCodec.FRAME_BYTES];
        for (int i = 0; i < AnimationFrameCodec.FRAME_PIXELS; i++) {
            frame[2 * i] = (byte) COLOR_A;
            frame[2 * i + 1] = (byte) (COLOR_A >> 8);
        }

        final AnimationFrameCodec.EncodedFrame encoded = AnimationFrameCodec.encode(frame);

        assertEquals(AnimationFrameCodec.FRAME_FLAG_PAL_RLE, encoded.frameFlags());
        final byte[] expected = new byte[AnimationFrameCodec.PALETTE_BYTES + 2 * AnimationFrameCodec.FRAME_ROWS];
        expected[0] = (byte) COLOR_A;
        expected[1] = (byte) (COLOR_A >> 8);
        for (int r = 0; r < AnimationFrameCodec.FRAME_ROWS; r++) {
            expected[AnimationFrameCodec.PALETTE_BYTES + 2 * r] = AnimationFrameCodec.ROW_PIXELS;
            expected[AnimationFrameCodec.PALETTE_BYTES + 2 * r + 1] = 0;
        }
        assertArrayEquals(expected, encoded.body());
        assertArrayEquals(frame, AnimationFrameCodec.decodePalRle(encoded.body()));
    }

    @Test
    void paletteUsesFirstSeenOrderAndZeroesUnusedEntries() {
        final int[][] rows = new int[AnimationFrameCodec.FRAME_ROWS][];
        rows[0] = row(COLOR_A);
        rows[1] = row(COLOR_A, 32, COLOR_B, 32);
        Arrays.fill(rows, 2, AnimationFrameCodec.FRAME_ROWS, row(COLOR_A));
        final byte[] frame = frame(rows);

        final Optional<byte[]> body = AnimationFrameCodec.palRleBody(frame);

        assertTrue(body.isPresent());
        final int[] pairs = new int[2 * 33];
        pairs[0] = AnimationFrameCodec.ROW_PIXELS;
        pairs[1] = 0;
        pairs[2] = 32;
        pairs[3] = 0;
        pairs[4] = 32;
        pairs[5] = 1;
        for (int r = 2; r < AnimationFrameCodec.FRAME_ROWS; r++) {
            pairs[2 * (r + 1)] = AnimationFrameCodec.ROW_PIXELS;
            pairs[2 * (r + 1) + 1] = 0;
        }
        final byte[] expected = bodyOf(pairs);
        expected[0] = (byte) COLOR_A;
        expected[1] = (byte) (COLOR_A >> 8);
        expected[2] = (byte) COLOR_B;
        expected[3] = (byte) (COLOR_B >> 8);
        assertArrayEquals(expected, body.get());
        assertArrayEquals(frame, AnimationFrameCodec.decodePalRle(body.get()));
    }

    @Test
    void moreThanSixteenColorsFallsBackToRaw() {
        final int[][] rows = new int[AnimationFrameCodec.FRAME_ROWS][];
        for (int r = 0; r < 17; r++) {
            rows[r] = row(0x0100 * (r + 1));
        }
        Arrays.fill(rows, 17, AnimationFrameCodec.FRAME_ROWS, row(0x0100));
        final byte[] frame = frame(rows);

        assertTrue(AnimationFrameCodec.palRleBody(frame).isEmpty(), "17 distinct colors cannot be PAL_RLE");
        final AnimationFrameCodec.EncodedFrame encoded = AnimationFrameCodec.encode(frame);
        assertEquals(AnimationFrameCodec.FRAME_FLAG_RAW, encoded.frameFlags());
        assertSame(frame, encoded.body());
    }

    @Test
    void rleNotStrictlySmallerFallsBackToRaw() {
        final int[][] alternating = new int[AnimationFrameCodec.FRAME_ROWS][];
        Arrays.fill(alternating, alternatingRow(COLOR_A, COLOR_B));
        final byte[] worstCase = frame(alternating);

        assertTrue(AnimationFrameCodec.palRleBody(worstCase).isEmpty(),
                "2048 runs encode to 4128 body bytes >= 4096 raw");
        assertEquals(AnimationFrameCodec.FRAME_FLAG_RAW, AnimationFrameCodec.encode(worstCase).frameFlags());

        final int[][] boundary = new int[AnimationFrameCodec.FRAME_ROWS][];
        Arrays.fill(boundary, 0, 31, alternatingRow(COLOR_A, COLOR_B));
        final int[] lastRow = new int[AnimationFrameCodec.ROW_PIXELS];
        for (int c = 0; c < 47; c++) {
            lastRow[c] = (c & 1) == 0 ? COLOR_A : COLOR_B;
        }
        Arrays.fill(lastRow, 47, AnimationFrameCodec.ROW_PIXELS, COLOR_B);
        boundary[31] = lastRow;
        final byte[] exactlyRawSized = frame(boundary);

        assertTrue(AnimationFrameCodec.palRleBody(exactlyRawSized).isEmpty(),
                "2032 runs encode to exactly 4096 body bytes, which is not strictly smaller");
        assertEquals(AnimationFrameCodec.FRAME_FLAG_RAW, AnimationFrameCodec.encode(exactlyRawSized).frameFlags());
    }

    @Test
    void blockyFrameRoundtripsThroughPalRle() {
        final int[][] rows = new int[AnimationFrameCodec.FRAME_ROWS][];
        for (int r = 0; r < AnimationFrameCodec.FRAME_ROWS; r++) {
            final int[] row = new int[AnimationFrameCodec.ROW_PIXELS];
            for (int c = 0; c < AnimationFrameCodec.ROW_PIXELS; c++) {
                final int colorIdx = ((r / 2) * 5 + c / 8) % 16;
                row[c] = 0x1111 * (colorIdx + 1);
            }
            rows[r] = row;
        }
        final byte[] frame = frame(rows);

        final Optional<byte[]> body = AnimationFrameCodec.palRleBody(frame);
        assertTrue(body.isPresent());
        assertTrue(body.get().length < AnimationFrameCodec.FRAME_BYTES);
        assertEquals(AnimationFrameCodec.PALETTE_BYTES + 2 * 256, body.get().length,
                "8-px blocks: 8 runs per row x 32 rows");
        assertArrayEquals(frame, AnimationFrameCodec.decodePalRle(body.get()));
    }

    @Test
    void decoderRejectsSpecViolations() {
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(new byte[AnimationFrameCodec.PALETTE_BYTES - 2]));
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(new byte[AnimationFrameCodec.PALETTE_BYTES + 1]),
                "odd pair region");
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(bodyOf(0, 0)), "run length 0");
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(bodyOf(64, 16)), "colorIdx 16");
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(bodyOf(33, 0, 33, 0)), "runs cross the 64-px row");
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRle(bodyOf(64, 0)), "pairs cover one row, not 2048 px");
    }

    @Test
    void encodeRejectsNonFrameSizedInput() {
        assertThrows(IllegalArgumentException.class, () -> AnimationFrameCodec.encode(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> AnimationFrameCodec.encode(new byte[4095]));
    }
}
