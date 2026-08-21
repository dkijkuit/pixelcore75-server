package nl.ctasoftware.crypto.ticker.server.service.command;

import nl.ctasoftware.crypto.ticker.server.service.job.AnimationFrameCodec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BLIT is the phase-2 PAL_RLE body generalized to w-wide rows: encode&rarr;decode roundtrips
 * (through the real wire: CommandBatch bytes &rarr; AcmdParser), exact body bytes with
 * row-boundary runs, canvas clipping, and the 16-color palette limit.
 */
class AcmdBlitTests {

    private static final int RED = 0xF800;
    private static final int GREEN = 0x07E0;
    private static final int BLUE = 0x001F;

    @Test
    void roundtripsThroughWireBytes() {
        final int[] pixels = {RED, GREEN, BLUE, GREEN, RED, RED}; // 3x2
        final byte[] framed = CommandBatch.builder().blit(7, 9, 3, 2, pixels).build();

        final var parsed = AcmdParser.parse(framed);
        assertEquals(1, parsed.commands().size());
        final AcmdCommand.Blit blit = (AcmdCommand.Blit) parsed.commands().get(0);
        assertEquals(7, blit.x());
        assertEquals(9, blit.y());
        assertEquals(3, blit.w());
        assertEquals(2, blit.h());
        assertArrayEquals(pixels, blit.pixels());
    }

    @Test
    void bodyBytesAreHandComputed() {
        final int[] pixels = {RED, GREEN, RED, RED}; // 2x2: row0 = R|G, row1 = R|R
        final byte[] framed = CommandBatch.builder().blit(0, 0, 2, 2, pixels).build();

        // frame = header(7) + opcode(1) + payloadLen(2) + x y w h(4) + palette(32) + 3 pairs(6)
        final byte[] expected = new byte[7 + 1 + 2 + 4 + 32 + 6];
        expected[0] = 'A'; expected[1] = 'C'; expected[2] = 'M'; expected[3] = 'D';
        expected[4] = 0x01;   // version
        expected[5] = 0x01;   // cmdCount LE
        expected[6] = 0x00;
        expected[7] = AcmdOpcode.BLIT;
        expected[8] = 38;
        expected[9] = 0;
        expected[10] = 0; // x
        expected[11] = 0; // y
        expected[12] = 2; // w
        expected[13] = 2; // h
        expected[14] = (byte) RED;
        expected[15] = (byte) (RED >> 8);
        expected[16] = (byte) GREEN;
        expected[17] = (byte) (GREEN >> 8);
        expected[46] = 1; // row 0: (1, RED)
        expected[47] = 0;
        expected[48] = 1; // row 0: (1, GREEN)
        expected[49] = 1;
        expected[50] = 2; // row 1: (2, RED)
        expected[51] = 0;
        assertArrayEquals(expected, framed);
    }

    @Test
    void runsNeverCrossRowBoundaries() {
        // 2-wide rows of alternating colors force exactly 2 runs per row
        final int[] pixels = {RED, GREEN, GREEN, RED, RED, GREEN, GREEN, RED}; // 2x4
        final byte[] body = AnimationFrameCodec.encodePalRleBody(pixels, 2);

        assertEquals(32 + 2 * 8, body.length, "2 runs x 4 rows");
        final int pairs = 32;
        for (int row = 0; row < 4; row++) {
            assertEquals(1, body[pairs + 4 * row] & 0xFF, "row " + row + " run0 len");
            assertEquals(1, body[pairs + 4 * row + 2] & 0xFF, "row " + row + " run1 len");
        }
        assertArrayEquals(pixels, AnimationFrameCodec.decodePalRleBody(body, 2, 8));
    }

    @Test
    void rejectsMoreThanSixteenColors() {
        final int[] pixels = new int[17];
        for (int i = 0; i < 17; i++) {
            pixels[i] = i << 6; // 17 distinct colors
        }
        assertThrows(IllegalArgumentException.class,
                () -> CommandBatch.builder().blit(0, 0, 17, 1, pixels).build());
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.encodePalRleBody(pixels, 17));
    }

    @Test
    void longRunsAreSplitAtRowBoundariesOnDecode() {
        // a run crossing rows must be rejected by the decoder: 1-wide rows can only carry
        // run=1 pairs; run=2 would cross
        final byte[] body = new byte[32 + 2];
        body[0] = (byte) RED;
        body[1] = (byte) (RED >> 8);
        body[32] = 2;
        body[32 + 1] = 0;
        assertThrows(IllegalArgumentException.class,
                () -> AnimationFrameCodec.decodePalRleBody(body, 1, 2));
    }

    @Test
    void mirrorClipsBlitToCanvas() {
        final int[] pixels = new int[8];
        java.util.Arrays.fill(pixels, RED); // 4x2 block
        final byte[] framed = CommandBatch.builder().blit(62, 30, 4, 2, pixels).build();

        final AcmdMirror mirror = AcmdMirror.parse(framed);
        final int[] frame = mirror.baseFrame();

        // only columns 62..63 of the 4-wide block and rows 30..31 of the block at y=30 fit
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                final boolean inCanvas = x >= 62 && x <= 63 && (y == 30 || y == 31);
                assertEquals(inCanvas ? RED : 0, frame[y * 64 + x], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void wireRejectsSpecViolatingBlitBody() {
        // payload shorter than the 32-byte palette -> command skipped, batch prefix intact
        final byte[] batch = new byte[7 + 1 + 2 + 4 + 4 + 5];
        batch[0] = 'A'; batch[1] = 'C'; batch[2] = 'M'; batch[3] = 'D';
        batch[4] = 0x01;
        batch[5] = 0x02; batch[6] = 0x00;
        batch[7] = AcmdOpcode.BLIT;
        batch[8] = 0x04; batch[9] = 0x00; // payloadLen 4 (< 32-byte palette)
        batch[10] = 0; batch[11] = 0; batch[12] = 1; batch[13] = 1;
        batch[14] = 0; batch[15] = 0; batch[16] = 0; batch[17] = 0; // malformed payload
        batch[18] = AcmdOpcode.PIX;
        batch[19] = 1; batch[20] = 2;
        batch[21] = 0x00; batch[22] = 0x00;

        final var parsed = AcmdParser.parse(batch);
        assertEquals(1, parsed.commands().size());
        assertEquals(new AcmdCommand.Pix(1, 2, 0), parsed.commands().get(0));
    }
}
