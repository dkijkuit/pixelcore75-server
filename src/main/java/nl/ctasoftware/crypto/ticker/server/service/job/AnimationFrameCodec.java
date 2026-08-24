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
 *
 * <p>The palette+run encoding itself is row-width generic: the ACMD BLIT command (protocol
 * phase 3) carries the same body shape generalized to {@code w}-wide rows, so
 * {@link #encodePalRleBody(int[], int)} and {@link #decodePalRleBody(byte[], int, int)}
 * expose the core for arbitrary rectangles (over int[] RGB565 pixels) and the 64&times;32
 * frame methods above delegate to them — a single RLE implementation, not a fork.
 */
public final class AnimationFrameCodec {

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

    public record EncodedFrame(int frameFlags, byte[] body) {
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

        final int[] pixels = new int[FRAME_PIXELS];
        for (int i = 0; i < FRAME_PIXELS; i++) {
            pixels[i] = (rgb565[2 * i] & 0xFF) | (rgb565[2 * i + 1] & 0xFF) << 8;
        }
        final byte[] body = encodePalRleOrNull(pixels, ROW_PIXELS);
        if (body == null || body.length >= FRAME_BYTES) {
            return Optional.empty();
        }
        return Optional.of(body);
    }

    /** Decodes a PAL_RLE body back to the raw 4096-byte frame, validating every spec invariant. */
    static byte[] decodePalRle(final byte[] body) {
        final int[] pixels = decodePalRleBody(body, ROW_PIXELS, FRAME_PIXELS);
        final byte[] frame = new byte[FRAME_BYTES];
        for (int i = 0; i < FRAME_PIXELS; i++) {
            frame[2 * i] = (byte) pixels[i];
            frame[2 * i + 1] = (byte) (pixels[i] >> 8);
        }
        return frame;
    }

    /**
     * Row-width-generic PAL_RLE body for {@code pixels} (row-major RGB565 ints,
     * {@code pixels.length} a multiple of {@code rowPixels}): 32-byte palette (first-seen
     * order, unused entries zero) + (run u8, colorIdx u8) pairs where runs never cross the
     * row boundaries (each row's runs sum exactly {@code rowPixels}, run 1..rowPixels).
     * Throws when the pixels hold more than 16 distinct colors — the caller decides whether
     * that is a fallback (frame codec: RAW) or an error (ACMD BLIT: cannot be encoded).
     */
    public static byte[] encodePalRleBody(final int[] pixels, final int rowPixels) {
        final byte[] body = encodePalRleOrNull(pixels, rowPixels);
        if (body == null) {
            throw new IllegalArgumentException(
                    "more than " + PALETTE_ENTRIES + " distinct colors cannot be PAL_RLE encoded");
        }
        return body;
    }

    /**
     * Decodes a row-width-generic PAL_RLE body back to {@code expectedPixels} RGB565 ints,
     * validating every spec invariant (palette presence, pair parity, run 1..rowPixels,
     * colorIdx 0..15, row sums, exact coverage).
     */
    public static int[] decodePalRleBody(final byte[] body, final int rowPixels, final int expectedPixels) {
        if (body.length < PALETTE_BYTES || (body.length - PALETTE_BYTES) % 2 != 0) {
            throw new IllegalArgumentException(
                    "PAL_RLE body must be " + PALETTE_BYTES + " palette bytes + (run, colorIdx) pairs");
        }
        final int[] pixels = new int[expectedPixels];
        int out = 0;
        int rowPixelsSoFar = 0;
        for (int i = PALETTE_BYTES; i < body.length; i += 2) {
            final int run = body[i] & 0xFF;
            final int colorIdx = body[i + 1] & 0xFF;
            if (run < 1 || run > rowPixels) {
                throw new IllegalArgumentException("run length " + run + " outside 1.." + rowPixels);
            }
            if (colorIdx >= PALETTE_ENTRIES) {
                throw new IllegalArgumentException("colorIdx " + colorIdx + " outside 0.." + (PALETTE_ENTRIES - 1));
            }
            if (rowPixelsSoFar + run > rowPixels) {
                throw new IllegalArgumentException("runs must not cross row boundaries");
            }
            final int color = (body[2 * colorIdx] & 0xFF) | (body[2 * colorIdx + 1] & 0xFF) << 8;
            for (int r = 0; r < run; r++) {
                pixels[out++] = color;
            }
            rowPixelsSoFar += run;
            if (rowPixelsSoFar == rowPixels) {
                rowPixelsSoFar = 0;
            }
        }
        if (out != expectedPixels) {
            throw new IllegalArgumentException(
                    "pairs cover " + out + " pixels, expected " + expectedPixels);
        }
        return pixels;
    }

    private static byte[] encodePalRleOrNull(final int[] pixels, final int rowPixels) {
        if (pixels.length == 0 || pixels.length % rowPixels != 0) {
            throw new IllegalArgumentException(
                    "pixel count " + pixels.length + " must be a positive multiple of row width " + rowPixels);
        }
        for (final int color : pixels) {
            if ((color & ~0xFFFF) != 0) {
                throw new IllegalArgumentException("color " + color + " outside RGB565 u16 range");
            }
        }

        final int[] palette = new int[PALETTE_ENTRIES];
        int paletteSize = 0;
        final ByteArrayOutputStream pairs = new ByteArrayOutputStream(pixels.length);

        for (int row = 0; row < pixels.length / rowPixels; row++) {
            int runIdx = -1;
            int runLength = 0;
            for (int col = 0; col < rowPixels; col++) {
                final int color = pixels[row * rowPixels + col];
                int idx = paletteIndex(palette, paletteSize, color);
                if (idx < 0) {
                    if (paletteSize == PALETTE_ENTRIES) {
                        return null;
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
        final byte[] body = new byte[PALETTE_BYTES + encoded.length];
        for (int i = 0; i < paletteSize; i++) {
            body[2 * i] = (byte) palette[i];
            body[2 * i + 1] = (byte) (palette[i] >> 8);
        }
        System.arraycopy(encoded, 0, body, PALETTE_BYTES, encoded.length);
        return body;
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
