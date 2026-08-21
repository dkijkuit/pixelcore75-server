package nl.ctasoftware.crypto.ticker.server.service.command;

import java.awt.Color;

/**
 * {@link Color} &rarr; RGB565 u16 with the same <em>nearest</em> 5-6-5 quantization the
 * frame path applies when publishing panel bytes ({@code ImageService.getRgb565}:
 * {@code (v * levels + 127) / 255}, not truncation) — so command-encoded colors match
 * the frame path's on-panel colors exactly, which is what the parity tests compare.
 */
public final class Rgb565 {

    private Rgb565() {
    }

    public static int of(final Color color) {
        return of(color.getRed(), color.getGreen(), color.getBlue());
    }

    public static int of(final int r, final int g, final int b) {
        return ((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | (b * 31 + 127) / 255;
    }
}
