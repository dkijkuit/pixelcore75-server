package nl.ctasoftware.crypto.ticker.server.service.job;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * Per-frame codec for animation protocol v2 (ANIM flags bits 1-2 = PAL_RLE): encodes one
 * frame's raw 4096-byte RGB565 LE payload as a PAL_RLE body — a 16-entry RGB565 palette
 * (32 bytes, entries u16 LE, first-seen order, unused entries zero) followed by (run u8,
 * colorIdx u8) pairs covering all 2048 pixels, where runs never cross the 64-pixel row
 * boundaries (each row's runs sum exactly 64, run 1..64, colorIdx 0..15) — or signals that
 * RAW is the better choice (more than 16 distinct colors in the frame, or the encoded body
 * not strictly smaller than the raw payload). {@link #encode} applies the wire protocol's
 * per-frame minimum rule: a codec-1 upload still carries RAW-bodied frames (frameFlags 0)
 * whenever RLE would not win. {@link #decodePalRle} inverts a body and strictly validates
 * the spec invariants (roundtrip tests, and any future consumer of wire frames such as an
 * SSE preview over the wire path).
 */
final class AnimationFrameCodec {

    static final int ROW_PIXELS = 64;
    static final int FRAME_PIXELS = 2048;
    static final int FRAME_ROWS = FRAME_PIXELS / ROW_PIXELS;
    static final int FRAME_BYTES = FRAME_PIXELS * 2;
    static final int PALETTE_ENTRIES = 16;
    static final int PALETTE_BYTES = PALETTE_ENTRIES * 2;

    static final int FRAME_FLAG_RAW = 0;
    static final int FRAME_FLAG_PAL_RLE = 1;

    private AnimationFrameCodec() {
    }

    record EncodedFrame(int frameFlags, byte[] body) {
        static EncodedFrame raw(final byte[] rgb565) {
            return new EncodedFrame(FRAME_FLAG_RAW, rgb565);
        }
    }

    /** The wire protocol's per-frame choice: PAL_RLE when legal and strictly smaller, else RAW. */
    static EncodedFrame encode(final byte[] rgb565) {
        return palRleBody(rgb565)
                .map(body -> new EncodedFrame(FRAME_FLAG_PAL_RLE, body))
                .orElseGet(() -> EncodedFrame.raw(rgb565));
    }

    /**
     * PAL_RLE body for one raw frame, or empty when RAW wins: more than 16 distinct colors,
     * or a body that is not strictly smaller than the 4096-byte raw payload.
     */
    static Optional<byte[]> palRleBody(final byte[] rgb565) {
        requireFrame(rgb565);

        final int[] palette = new int[PALETTE_ENTRIES];
        int paletteSize = 0;
        final ByteArrayOutputStream pairs = new ByteArrayOutputStream(FRAME_BYTES);

        for (int row = 0; row < FRAME_ROWS; row++) {
            int runIdx = -1;
            int runLength = 0;
            for (int col = 0; col < ROW_PIXELS; col++) {
                final int pixel = row * ROW_PIXELS + col;
                final int color = (rgb565[2 * pixel] & 0xFF) | (rgb565[2 * pixel + 1] & 0xFF) << 8;
                int idx = paletteIndex(palette, paletteSize, color);
                if (idx < 0) {
                    if (paletteSize == PALETTE_ENTRIES) {
                        return Optional.empty();
                    }
                    palette[paletteSize] = color;
                    idx = paletteSize;
                    paletteSize++;
                }
                if (runLength > 0 && idx != runIdx) {
                    pairs.write(runLength);
                    pairs.write(runIdx);
                    runLength = 0;
                }
                runIdx = idx;
                runLength++;
            }
            pairs.write(runLength);
            pairs.write(runIdx);
        }

        final byte[] encoded = pairs.toByteArray();
        if (PALETTE_BYTES + encoded.length >= FRAME_BYTES) {
            return Optional.empty();
        }
        final byte[] body = new byte[PALETTE_BYTES + encoded.length];
        for (int i = 0; i < paletteSize; i++) {
            body[2 * i] = (byte) palette[i];
            body[2 * i + 1] = (byte) (palette[i] >> 8);
        }
        System.arraycopy(encoded, 0, body, PALETTE_BYTES, encoded.length);
        return Optional.of(body);
    }

    /** Decodes a PAL_RLE body back to the raw 4096-byte frame, validating every spec invariant. */
    static byte[] decodePalRle(final byte[] body) {
        if (body.length < PALETTE_BYTES || (body.length - PALETTE_BYTES) % 2 != 0) {
            throw new IllegalArgumentException(
                    "PAL_RLE body must be " + PALETTE_BYTES + " palette bytes + (run, colorIdx) pairs");
        }
        final byte[] frame = new byte[FRAME_BYTES];
        int out = 0;
        int rowPixels = 0;
        for (int i = PALETTE_BYTES; i < body.length; i += 2) {
            final int run = body[i] & 0xFF;
            final int colorIdx = body[i + 1] & 0xFF;
            if (run < 1 || run > ROW_PIXELS) {
                throw new IllegalArgumentException("run length " + run + " outside 1.." + ROW_PIXELS);
            }
            if (colorIdx >= PALETTE_ENTRIES) {
                throw new IllegalArgumentException("colorIdx " + colorIdx + " outside 0.." + (PALETTE_ENTRIES - 1));
            }
            if (rowPixels + run > ROW_PIXELS) {
                throw new IllegalArgumentException("runs must not cross row boundaries");
            }
            final int color = (body[2 * colorIdx] & 0xFF) | (body[2 * colorIdx + 1] & 0xFF) << 8;
            for (int r = 0; r < run; r++) {
                frame[out++] = (byte) color;
                frame[out++] = (byte) (color >> 8);
            }
            rowPixels += run;
            if (rowPixels == ROW_PIXELS) {
                rowPixels = 0;
            }
        }
        if (out != FRAME_BYTES) {
            throw new IllegalArgumentException("pairs cover " + (out / 2) + " pixels, expected " + FRAME_PIXELS);
        }
        return frame;
    }

    private static int paletteIndex(final int[] palette, final int size, final int color) {
        for (int i = 0; i < size; i++) {
            if (palette[i] == color) {
                return i;
            }
        }
        return -1;
    }

    private static void requireFrame(final byte[] rgb565) {
        if (rgb565.length != FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "frame payload must be " + FRAME_BYTES + " bytes, got " + rgb565.length);
        }
    }
}
