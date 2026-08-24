package nl.ctasoftware.crypto.ticker.server.service.command;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Extracts ASCII 32..126 glyphs from a TTF into the ACMD FONT-page payload format, using
 * the same {@code assets/fonts/*.ttf} files {@code PaintToolsService} renders with today —
 * so ACMD TEXT keeps the current TTF look on the panel. Rendering is a pure function of
 * (ttf bytes, size): {@code FontRenderContext(identity, no AA, no fractional metrics)},
 * glyph outline filled with anti-aliasing off, thresholded at alpha &ge; 128 (binary, no
 * gray), bitmap taken from the thresholded bounding box — the same JVM produces the same
 * payload bytes on every run.
 *
 * <p>Geometry conventions (the firmware implements the identical rules): the glyph pen
 * origin is the baseline position; {@code xOff} is the thresholded bbox's left edge
 * relative to the pen; {@code yOff} is the bbox's top edge relative to the page's
 * <em>line-box top</em>, defined as the highest glyph top in the page (min over non-empty
 * glyphs of the bbox top relative to the baseline). TEXT/SCROLL {@code y} is that
 * line-box top, so a page's glyphs share one vertical reference. Blank glyphs (e.g.
 * space) are emitted as 1&times;1 all-zero bitmaps (the wire format requires w,h 1..32)
 * with zero offsets; only their xAdvance matters.
 */
public final class FontPageExtractor {

    private static final char FIRST_CHAR = 32;
    private static final char LAST_CHAR = 126;
    private static final int GLYPH_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    private static final int ORIGIN_X = 48;
    private static final int ORIGIN_Y = 64;
    private static final int RASTER_SIZE = 128;

    private FontPageExtractor() {
    }

    /**
     * An extracted FONT page: the page's glyphs plus the {@code lineTop} offset the
     * extraction normalized the glyph {@code yOff}s to (see the class docs). Services use
     * {@link #lineTop()} to convert a frame-path AWT <em>baseline</em> y into the ACMD
     * line-box-top y ({@code acmdY = baselineY + lineTop()}) and {@link #width(String)}
     * for centering/right-alignment with the panel's own integer advances.
     */
    public record FontPage(int lineTop, java.util.List<AcmdCommand.Glyph> glyphs) {

        /** Advance width of {@code ascii} under this page (unknown glyphs advance 4). */
        public int width(final String ascii) {
            int width = 0;
            for (final char c : ascii.toCharArray()) {
                final AcmdCommand.Glyph g = glyphOf(c);
                width += g == null ? AcmdOpcode.UNKNOWN_GLYPH_ADVANCE : g.xAdvance();
            }
            return width;
        }

        private AcmdCommand.Glyph glyphOf(final char c) {
            for (final AcmdCommand.Glyph g : glyphs) {
                if (g.code() == c) {
                    return g;
                }
            }
            return null;
        }
    }

    /** Loads a TTF (same files/sizes as the {@code PaintConfig} beans) for extraction. */
    public static Font loadFont(final File ttf, final float size) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont(size);
        } catch (final FontFormatException | IOException e) {
            throw new IllegalArgumentException("cannot load TTF " + ttf, e);
        }
    }

    /** FONT command payload for {@code pageId} (0..3): pageId u8 + glyphCount u8 + glyphs. */
    public static byte[] payload(final int pageId, final Font font) {
        return payload(pageId, extract(font));
    }

    /** Payload encoding of an already-extracted page (byte-identical to the TTF path). */
    public static byte[] payload(final int pageId, final FontPage page) {
        requirePageId(pageId);

        final AcmdCommand.Glyph[] glyphs = page.glyphs().toArray(new AcmdCommand.Glyph[0]);
        int size = 2; // pageId + glyphCount
        for (final AcmdCommand.Glyph g : glyphs) {
            size += 6 + g.bitmap().length;
        }

        final byte[] payload = new byte[size];
        int out = 0;
        payload[out++] = (byte) pageId;
        payload[out++] = (byte) GLYPH_COUNT;
        for (final AcmdCommand.Glyph g : glyphs) {
            payload[out++] = (byte) g.code();
            payload[out++] = (byte) g.w();
            payload[out++] = (byte) g.h();
            payload[out++] = (byte) g.xAdvance();
            payload[out++] = (byte) g.xOff();
            payload[out++] = (byte) g.yOff();
            System.arraycopy(g.bitmap(), 0, payload, out, g.bitmap().length);
            out += g.bitmap().length;
        }
        return payload;
    }

    /** Extracts the page (glyphs + lineTop) of a TTF; deterministic per (ttf bytes, size). */
    public static FontPage extract(final Font font) {
        final FontRenderContext frc =
                new FontRenderContext(new AffineTransform(), false, false);
        final Raster[] rasters = new Raster[GLYPH_COUNT];
        int lineTop = Integer.MAX_VALUE;
        for (char c = FIRST_CHAR; c <= LAST_CHAR; c++) {
            final Raster r = rasterize(c, font, frc);
            rasters[c - FIRST_CHAR] = r;
            if (!r.empty) {
                lineTop = Math.min(lineTop, r.yOffBaseline);
            }
        }
        if (lineTop == Integer.MAX_VALUE) {
            lineTop = 0;
        }

        final AcmdCommand.Glyph[] glyphs = new AcmdCommand.Glyph[GLYPH_COUNT];
        for (char c = FIRST_CHAR; c <= LAST_CHAR; c++) {
            glyphs[c - FIRST_CHAR] = toGlyph(c, rasters[c - FIRST_CHAR], lineTop);
        }
        return new FontPage(lineTop, java.util.List.of(glyphs));
    }

    private record Raster(boolean empty, int w, int h, int xOffBaseline, int yOffBaseline,
                          boolean[] bits, int xAdvance) {}

    private static Raster rasterize(final char c, final Font font, final FontRenderContext frc) {
        final char[] chars = {c};
        final GlyphVector gv = font.createGlyphVector(frc, chars);
        final int advance = clampI8((int) Math.floor(gv.getGlyphMetrics(0).getAdvanceX() + 0.5));
        final Rectangle2D vb = gv.getVisualBounds();
        if (vb.isEmpty()) {
            return new Raster(true, 1, 1, 0, 0, new boolean[1], advance);
        }

        final BufferedImage img = new BufferedImage(RASTER_SIZE, RASTER_SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(ORIGIN_X, ORIGIN_Y);
        g.fill(gv.getGlyphOutline(0));
        g.dispose();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < RASTER_SIZE; y++) {
            for (int x = 0; x < RASTER_SIZE; x++) {
                if ((img.getRGB(x, y) >>> 24) >= 128) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return new Raster(true, 1, 1, 0, 0, new boolean[1], advance);
        }
        requireWithinRaster(vb);

        final int w = maxX - minX + 1;
        final int h = maxY - minY + 1;
        final boolean[] bits = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bits[y * w + x] = (img.getRGB(minX + x, minY + y) >>> 24) >= 128;
            }
        }
        return new Raster(false, w, h, minX - ORIGIN_X, minY - ORIGIN_Y, bits, advance);
    }

    private static AcmdCommand.Glyph toGlyph(final char c, final Raster r, final int lineTop) {
        if (r.empty) {
            return new AcmdCommand.Glyph(c, 1, 1, r.xAdvance, 0, 0, new byte[1]);
        }
        if (r.w > AcmdOpcode.GLYPH_MAX_DIM || r.h > AcmdOpcode.GLYPH_MAX_DIM) {
            throw new IllegalArgumentException("glyph '" + c + "' is " + r.w + "x" + r.h
                    + ", exceeds " + AcmdOpcode.GLYPH_MAX_DIM + "px protocol limit");
        }
        final int yOff = clampI8(r.yOffBaseline - lineTop);
        return new AcmdCommand.Glyph(c, r.w, r.h, r.xAdvance, clampI8(r.xOffBaseline), yOff,
                packBitmap(r));
    }

    private static byte[] packBitmap(final Raster r) {
        final int bytesPerRow = (r.w + 7) >> 3;
        final byte[] bitmap = new byte[bytesPerRow * r.h];
        for (int y = 0; y < r.h; y++) {
            for (int x = 0; x < r.w; x++) {
                if (r.bits[y * r.w + x]) {
                    bitmap[y * bytesPerRow + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return bitmap;
    }

    private static void requireWithinRaster(final Rectangle2D vb) {
        if (vb.getMinX() < -ORIGIN_X || vb.getMaxX() > RASTER_SIZE - ORIGIN_X
                || vb.getMinY() < -ORIGIN_Y || vb.getMaxY() > RASTER_SIZE - ORIGIN_Y) {
            throw new IllegalArgumentException(
                    "glyph visual bounds " + vb + " exceed the " + RASTER_SIZE + "px extraction raster");
        }
    }

    private static int clampI8(final int v) {
        if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("value " + v + " outside i8 range");
        }
        return v;
    }

    static void requirePageId(final int pageId) {
        if (pageId < 0 || pageId >= AcmdOpcode.FONT_PAGES) {
            throw new IllegalArgumentException("pageId " + pageId + " outside 0.." + (AcmdOpcode.FONT_PAGES - 1));
        }
    }
}
