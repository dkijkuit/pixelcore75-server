package nl.ctasoftware.crypto.ticker.server.service.command;

import java.awt.image.BufferedImage;

/**
 * Golden-image parity support: reduces a frame-path {@link BufferedImage} to the panel's
 * RGB565 view — the same <em>nearest</em> 5-6-5 quantization {@code ImageService} applies
 * when publishing frame bytes ({@code (v * levels + 127) / 255}) — so both engines are
 * compared in the exact colors the panel displays, then counts mismatching pixels.
 */
public final class FrameParity {

    public static final int PIXELS = 64 * 32;

    private FrameParity() {
    }

    /** Panel-view RGB565 (row-major 64&times;32 u16 values as ints) of a frame-path image. */
    public static int[] rgb565(final BufferedImage image) {
        if (image.getWidth() != 64 || image.getHeight() != 32) {
            throw new IllegalArgumentException("golden frame must be 64x32");
        }
        final int[] out = new int[PIXELS];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                final int argb = image.getRGB(x, y);
                final int r = (argb >>> 16) & 0xFF;
                final int g = (argb >>> 8) & 0xFF;
                final int b = argb & 0xFF;
                out[y * 64 + x] = ((r * 31 + 127) / 255) << 11
                        | ((g * 63 + 127) / 255) << 5
                        | (b * 31 + 127) / 255;
            }
        }
        return out;
    }

    /** Number of pixels where the two RGB565 frames disagree. */
    public static int mismatchedPixels(final int[] golden, final int[] actual) {
        if (golden.length != PIXELS || actual.length != PIXELS) {
            throw new IllegalArgumentException("frames must hold " + PIXELS + " pixels");
        }
        int mismatch = 0;
        for (int i = 0; i < PIXELS; i++) {
            if ((golden[i] & 0xFFFF) != (actual[i] & 0xFFFF)) {
                mismatch++;
            }
        }
        return mismatch;
    }
}
