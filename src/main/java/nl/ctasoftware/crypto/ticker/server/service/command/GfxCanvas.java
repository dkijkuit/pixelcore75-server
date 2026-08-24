package nl.ctasoftware.crypto.ticker.server.service.command;

/**
 * 64&times;32 RGB565 canvas whose rasterization semantics are transcribed line-for-line
 * from the vendored {@code Adafruit_GFX.cpp} in the sibling firmware repo
 * ({@code .pio/libdeps/<env>/Adafruit GFX Library}). The firmware's ACMD engine draws from
 * the same C++ source, so every deviation here breaks pixel parity — including the quirks
 * (e.g. {@code fillRect} with {@code h == 0} still paints two pixel rows via the vline
 * round-trip in {@code writeLine}); transcribe, never "fix".
 *
 * <p>Fidelity notes (source line numbers from the vendored Adafruit_GFX.cpp):
 * <ul>
 *   <li>{@code drawPixel} silently ignores out-of-bounds coordinates — the ESP32 matrix
 *       panel driver's drawPixel does the same, which is what makes the unclipped
 *       intermediate coordinates the GFX algorithms produce (e.g. {@code x0 - r}) safe.</li>
 *   <li>{@code writeLine} (L132-173): Bresenham with {@code steep} axis swap, x0/x1 sort,
 *       {@code err = dx / 2} and the {@code err -= dy; if (err < 0)} step — all arithmetic
 *       stays within int16 range for u8 ACMD coordinates, so Java {@code int} matches C
 *       {@code int16_t} exactly.</li>
 *   <li>{@code drawLine} (L330-346): vertical/horizontal fast paths with coordinate swap;
 *       {@code drawFastVLine}/{@code drawFastHLine} (L265-287) are the base-class
 *       writeLine round-trips, not subclass-optimized DMA variants.</li>
 *   <li>{@code fillRect} (L300-307): per-column {@code writeFastVLine}; {@code drawRect}
 *       (L639-647): four fast lines; {@code drawCircle} (L472-509): midpoint circle with
 *       the four initial pixels and eight symmetric writes per iteration (duplicate
 *       writes are idempotent).</li>
 * </ul>
 */
final class GfxCanvas {

    static final int WIDTH = 64;
    static final int HEIGHT = 32;
    static final int PIXELS = WIDTH * HEIGHT;

    private final int[] pixels;

    GfxCanvas() {
        this.pixels = new int[PIXELS];
    }

    /** Wraps an existing RGB565 pixel array (row-major, 64&times;32) for in-place drawing. */
    GfxCanvas(final int[] pixels) {
        if (pixels.length != PIXELS) {
            throw new IllegalArgumentException("canvas must hold " + PIXELS + " pixels");
        }
        this.pixels = pixels;
    }

    int[] copy() {
        return pixels.clone();
    }

    int pixel(final int x, final int y) {
        return pixels[y * WIDTH + x];
    }

    void drawPixel(final int x, final int y, final int color) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
            return;
        }
        pixels[y * WIDTH + x] = color & 0xFFFF;
    }

    /** Adafruit_GFX::writeLine — Bresenham, literal transcription. */
    private void writeLine(int x0, int y0, int x1, int y1, final int color) {
        final boolean steep = Math.abs(y1 - y0) > Math.abs(x1 - x0);
        if (steep) {
            int t = x0; x0 = y0; y0 = t;
            t = x1; x1 = y1; y1 = t;
        }
        if (x0 > x1) {
            int t = x0; x0 = x1; x1 = t;
            t = y0; y0 = y1; y1 = t;
        }
        final int dx = x1 - x0;
        final int dy = Math.abs(y1 - y0);
        int err = dx / 2;
        final int ystep = y0 < y1 ? 1 : -1;
        for (; x0 <= x1; x0++) {
            if (steep) {
                drawPixel(y0, x0, color);
            } else {
                drawPixel(x0, y0, color);
            }
            err -= dy;
            if (err < 0) {
                y0 += ystep;
                err += dx;
            }
        }
    }

    /** Adafruit_GFX::drawFastVLine (base class) — writeLine round-trip. */
    private void drawFastVLine(final int x, final int y, final int h, final int color) {
        writeLine(x, y, x, y + h - 1, color);
    }

    /** Adafruit_GFX::drawFastHLine (base class) — writeLine round-trip. */
    private void drawFastHLine(final int x, final int y, final int w, final int color) {
        writeLine(x, y, x + w - 1, y, color);
    }

    /** Adafruit_GFX::drawLine — vertical/horizontal fast paths, else writeLine. */
    void drawLine(int x0, int y0, int x1, int y1, final int color) {
        if (x0 == x1) {
            if (y0 > y1) {
                final int t = y0; y0 = y1; y1 = t;
            }
            drawFastVLine(x0, y0, y1 - y0 + 1, color);
        } else if (y0 == y1) {
            if (x0 > x1) {
                final int t = x0; x0 = x1; x1 = t;
            }
            drawFastHLine(x0, y0, x1 - x0 + 1, color);
        } else {
            writeLine(x0, y0, x1, y1, color);
        }
    }

    /** Adafruit_GFX::fillRect — per-column vertical lines (h &le; 0 quirks included). */
    void fillRect(final int x, final int y, final int w, final int h, final int color) {
        for (int i = x; i < x + w; i++) {
            drawFastVLine(i, y, h, color);
        }
    }

    /** Adafruit_GFX::drawRect — four fast lines. */
    void drawRect(final int x, final int y, final int w, final int h, final int color) {
        drawFastHLine(x, y, w, color);
        drawFastHLine(x, y + h - 1, w, color);
        drawFastVLine(x, y, h, color);
        drawFastVLine(x + w - 1, y, h, color);
    }

    /** Adafruit_GFX::drawCircle — midpoint circle, literal transcription. */
    void drawCircle(final int x0, final int y0, final int r, final int color) {
        int f = 1 - r;
        int ddF_x = 1;
        int ddF_y = -2 * r;
        int x = 0;
        int y = r;

        drawPixel(x0, y0 + r, color);
        drawPixel(x0, y0 - r, color);
        drawPixel(x0 + r, y0, color);
        drawPixel(x0 - r, y0, color);

        while (x < y) {
            if (f >= 0) {
                y--;
                ddF_y += 2;
                f += ddF_y;
            }
            x++;
            ddF_x += 2;
            f += ddF_x;

            drawPixel(x0 + x, y0 + y, color);
            drawPixel(x0 - x, y0 + y, color);
            drawPixel(x0 + x, y0 - y, color);
            drawPixel(x0 - x, y0 - y, color);
            drawPixel(x0 + y, y0 + x, color);
            drawPixel(x0 - y, y0 + x, color);
            drawPixel(x0 + y, y0 - x, color);
            drawPixel(x0 - y, y0 - x, color);
        }
    }

    /** Plain rectangle fill (BLINK's dark phase, not a GFX primitive — no w/h quirks). */
    void fillRegionDirect(final int x, final int y, final int w, final int h, final int color) {
        for (int yy = Math.max(0, y); yy < Math.min(HEIGHT, y + h); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(WIDTH, x + w); xx++) {
                pixels[yy * WIDTH + xx] = color & 0xFFFF;
            }
        }
    }
}
