package nl.ctasoftware.crypto.ticker.server.service.command;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Java mirror of the firmware's ACMD v1 command engine: renders a batch to a 64&times;32
 * RGB565 base canvas using {@link GfxCanvas} (Adafruit_GFX-exact rasterization), then
 * models the parametric primitives over time via {@link #frameAt(long)} — the single
 * preview/parity renderer for SSE and golden-image tests.
 *
 * <p>Execution semantics (mirroring the firmware):
 * <ul>
 *   <li>The batch renders <b>atomically</b> to a base canvas, in command order; FONT pages
 *       must precede the TEXT/SCROLL commands that reference them.</li>
 *   <li><b>All</b> parametric primitives (SWEEP/SCROLL/BLINK) arm, in command order, up to
 *       {@link AcmdOpcode#PARAMS_MAX}; further ones are ignored. Each frame starts from a
 *       fresh copy of the base (the per-overlay region-snapshot restore), then applies the
 *       overlays in command order — a later overlay draws over an earlier one where their
 *       regions overlap.</li>
 *   <li>SWEEP: &theta; = (elapsedMs &times; speed / 1000) mod 360; endpoint
 *       (cx + r&middot;cos&theta;, cy + r&middot;sin&theta;) as doubles, C {@code lround}
 *       (half away from zero), GFX line drawn over the base each frame.</li>
 *   <li>SCROLL: ping-pong marquee with pacing — the text draws with glyph tops at
 *       {@code y} and penX oscillating between the head-visible extreme (penX = x) and
 *       the tail-visible extreme (penX = x + w &minus; textW), glyph pixels clipped to
 *       the region; each extreme is held for {@link AcmdOpcode#SCROLL_HOLD_PX}
 *       px-units before reversing, and one pass travels {@code textW - w} px in at
 *       least {@link AcmdOpcode#SCROLL_MIN_PASS_PX} px-units ({@code speedMsPerPx} per
 *       unit); phase 0 = the head hold; a fitting text (textW &le; w) renders
 *       statically at x; textWidth = &Sigma; xAdvance (unknown glyph &rarr; 4).</li>
 *   <li>BLINK: alternates content (the base region) &harr; black every periodMs/2 ms.</li>
 *   <li>Epoch carry-over is firmware state across <b>commits</b> — two mirrors never share
 *       it; each {@code parse} models one fresh commit (epoch 0).</li>
 * </ul>
 */
public final class AcmdMirror {

    public static final int WIDTH = GfxCanvas.WIDTH;
    public static final int HEIGHT = GfxCanvas.HEIGHT;
    public static final int BLACK = 0x0000;

    private final GfxCanvas base = new GfxCanvas();
    private final AcmdCommand.Glyph[][] pages = new AcmdCommand.Glyph[AcmdOpcode.FONT_PAGES][128];
    private final boolean[] pageDefined = new boolean[AcmdOpcode.FONT_PAGES];
    private final List<Parametric> parametrics = new ArrayList<>();

    private AcmdMirror() {
    }

    /** Parses a framed batch and renders its commands to the base canvas. */
    public static AcmdMirror parse(final byte[] framed) {
        final AcmdMirror mirror = new AcmdMirror();
        for (final AcmdCommand cmd : AcmdParser.parse(framed).commands()) {
            mirror.apply(cmd);
        }
        return mirror;
    }

    /** The batch's base canvas (no parametric overlays applied), 2048 RGB565 ints. */
    public int[] baseFrame() {
        return base.copy();
    }

    /**
     * Base canvas + all armed parametric overlays at {@code elapsedMs} after commit
     * (clamped to &ge; 0), applied in command order. Returns a fresh frame every call;
     * the base is never mutated.
     */
    public int[] frameAt(long elapsedMs) {
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
        final int[] frame = baseFrame();
        final GfxCanvas canvas = new GfxCanvas(frame);
        for (final Parametric parametric : parametrics) {
            parametric.apply(canvas, elapsedMs);
        }
        return frame;
    }

    /** True when the batch armed at least one SWEEP/SCROLL/BLINK. */
    public boolean hasParametric() {
        return !parametrics.isEmpty();
    }

    /** Number of armed parametric overlays (at most {@link AcmdOpcode#PARAMS_MAX}). */
    public int parametricCount() {
        return parametrics.size();
    }

    /** RGB565 &rarr; TYPE_INT_RGB bridge (5/6/5 bits expanded by replicating the high bits). */
    public static BufferedImage toBufferedImage(final int[] rgb565) {
        if (rgb565.length != GfxCanvas.PIXELS) {
            throw new IllegalArgumentException("frame must hold " + GfxCanvas.PIXELS + " pixels");
        }
        final BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                final int c = rgb565[y * WIDTH + x] & 0xFFFF;
                final int r5 = (c >> 11) & 0x1F;
                final int g6 = (c >> 5) & 0x3F;
                final int b5 = c & 0x1F;
                final int rgb = ((r5 << 3) | (r5 >> 2)) << 16 | ((g6 << 2) | (g6 >> 4)) << 8 | ((b5 << 3) | (b5 >> 2));
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private void apply(final AcmdCommand cmd) {
        switch (cmd) {
            case AcmdCommand.Nop nop -> { /* no-op */
            }
            case AcmdCommand.Cls c -> base.fillRect(0, 0, WIDTH, HEIGHT, c.color());
            case AcmdCommand.Pix p -> base.drawPixel(p.x(), p.y(), p.color());
            case AcmdCommand.Line l -> base.drawLine(l.x0(), l.y0(), l.x1(), l.y1(), l.color());
            case AcmdCommand.Rect r -> base.drawRect(r.x(), r.y(), r.w(), r.h(), r.color());
            case AcmdCommand.Fill f -> base.fillRect(f.x(), f.y(), f.w(), f.h(), f.color());
            case AcmdCommand.Circ c -> base.drawCircle(c.cx(), c.cy(), c.r(), c.color());
            case AcmdCommand.Blit b -> blit(b);
            case AcmdCommand.FontPage f -> installPage(f);
            case AcmdCommand.Text t -> drawText(t.fontId(), t.x(), t.y(), t.color(), t.ascii(), null);
            case AcmdCommand.Sweep s -> registerParametric(new SweepState(s));
            case AcmdCommand.Scroll s -> registerParametric(new ScrollState(s, glyphsOf(s.fontId())));
            case AcmdCommand.Blink b -> registerParametric(new BlinkState(b));
        }
    }

    private void blit(final AcmdCommand.Blit b) {
        for (int row = 0; row < b.h(); row++) {
            for (int col = 0; col < b.w(); col++) {
                base.drawPixel(b.x() + col, b.y() + row, b.pixels()[row * b.w() + col]); // clipped per pixel
            }
        }
    }

    private void installPage(final AcmdCommand.FontPage f) {
        FontPageExtractor.requirePageId(f.pageId());
        final AcmdCommand.Glyph[] page = new AcmdCommand.Glyph[128];
        for (final AcmdCommand.Glyph g : f.glyphs()) {
            page[g.code() & 0x7F] = g;
        }
        pages[f.pageId()] = page;
        pageDefined[f.pageId()] = true;
    }

    /** TEXT/SCROLL referencing an undefined page renders nothing (documented semantics). */
    private void drawText(final int fontId, final int penStartX, final int lineTop, final int color,
                          final String ascii, final int[] clipRegion) {
        if (!pageDefined[fontId]) {
            return;
        }
        final AcmdCommand.Glyph[] page = pages[fontId];
        int penX = penStartX;
        for (final char c : ascii.toCharArray()) {
            final AcmdCommand.Glyph g = page[c & 0x7F];
            if (g == null) {
                penX += AcmdOpcode.UNKNOWN_GLYPH_ADVANCE;
                continue;
            }
            drawGlyph(g, penX + g.xOff(), lineTop + g.yOff(), color, clipRegion);
            penX += g.xAdvance();
        }
    }

    private void drawGlyph(final AcmdCommand.Glyph g, final int gx, final int gy, final int color,
                           final int[] clipRegion) {
        final int bytesPerRow = (g.w() + 7) >> 3;
        for (int row = 0; row < g.h(); row++) {
            for (int col = 0; col < g.w(); col++) {
                if ((g.bitmap()[row * bytesPerRow + (col >> 3)] & (0x80 >> (col & 7))) != 0) {
                    final int x = gx + col;
                    final int y = gy + row;
                    if (clipRegion != null
                            && (x < clipRegion[0] || x >= clipRegion[0] + clipRegion[2]
                            || y < clipRegion[1] || y >= clipRegion[1] + clipRegion[3])) {
                        continue;
                    }
                    base.drawPixel(x, y, color);
                }
            }
        }
    }

    private void registerParametric(final Parametric candidate) {
        if (parametrics.size() < AcmdOpcode.PARAMS_MAX) {
            parametrics.add(candidate); // all parametrics arm, in command order, up to the cap
        }
    }

    private AcmdCommand.Glyph[] glyphsOf(final int fontId) {
        return pageDefined[fontId] ? pages[fontId] : null;
    }

    /** textWidth = sum of xAdvance, unknown glyphs advance 4 (matches the render path). */
    static int textWidth(final String ascii, final AcmdCommand.Glyph[] page) {
        int width = 0;
        for (final char c : ascii.toCharArray()) {
            final AcmdCommand.Glyph g = page == null ? null : page[c & 0x7F];
            width += g == null ? AcmdOpcode.UNKNOWN_GLYPH_ADVANCE : g.xAdvance();
        }
        return width;
    }

    /** C lround: rounds half away from zero (NOT Java Math.round's floor(x + 0.5)). */
    static int lround(final double v) {
        return (int) (v >= 0 ? Math.floor(v + 0.5) : Math.ceil(v - 0.5));
    }

    /**
     * One armed overlay. Every {@link #frameAt} starts from a fresh copy of the committed
     * base — that copy IS the firmware's per-tick full-canvas restore, so an overlay only
     * ever DRAWS; it never clears or snapshots its region itself.
     */
    private sealed interface Parametric permits SweepState, ScrollState, BlinkState {
        void apply(GfxCanvas frame, long elapsedMs);
    }

    private static final class SweepState implements Parametric {
        private final int cx;
        private final int cy;
        private final int r;
        private final int color;
        private final int speedDegPerSec;

        SweepState(final AcmdCommand.Sweep s) {
            this.cx = s.cx();
            this.cy = s.cy();
            this.r = s.r();
            this.color = s.color();
            this.speedDegPerSec = s.speedDegPerSec();
        }

        @Override
        public void apply(final GfxCanvas frame, final long elapsedMs) {
            final double thetaDeg = Math.floorMod(elapsedMs * speedDegPerSec / 1000L, 360L);
            final double rad = thetaDeg * Math.PI / 180.0;
            final int ex = lround(cx + r * Math.cos(rad));
            final int ey = lround(cy + r * Math.sin(rad));
            frame.drawLine(cx, cy, ex, ey, color);
        }
    }

    private static final class ScrollState implements Parametric {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int color;
        private final int speedMsPerPx;
        private final String ascii;
        private final AcmdCommand.Glyph[] page;

        ScrollState(final AcmdCommand.Scroll s, final AcmdCommand.Glyph[] page) {
            this.x = s.x();
            this.y = s.y();
            this.w = s.w();
            this.h = s.h();
            this.color = s.color();
            this.speedMsPerPx = s.speedMsPerPx();
            this.ascii = s.ascii();
            this.page = page;
        }

        @Override
        public void apply(final GfxCanvas frame, final long elapsedMs) {
            // Ping-pong marquee with readability pacing: penX oscillates between the
            // head-visible extreme (penX = x, tail clipped right) and the tail-visible
            // extreme (penX = x + w − textW, head clipped left) — the text never leaves
            // the region — HOLDING SCROLL_HOLD_PX px-units of time at each extreme
            // before reversing. One pass travels textW − w px in at least
            // SCROLL_MIN_PASS_PX px-units (a barely-overflowing text glides instead of
            // rattling); one px-unit is speedMsPerPx. Phase 0 = the head hold. A text
            // that fits (textW ≤ w) renders statically at x.
            final int textW = textWidth(ascii, page);
            final int travel = textW - w;
            final int penX;
            if (travel < 1) {
                penX = x;
            } else {
                final long vt = Math.max(travel, AcmdOpcode.SCROLL_MIN_PASS_PX);
                final long passMs = vt * Math.max(1, speedMsPerPx);
                final long holdMs = (long) AcmdOpcode.SCROLL_HOLD_PX * Math.max(1, speedMsPerPx);
                final long cyc = Math.floorMod(elapsedMs, 2 * (holdMs + passMs));
                long pen; // px left of the head extreme, 0..travel
                if (cyc < holdMs) {
                    pen = 0; // head hold
                } else if (cyc < holdMs + passMs) {
                    pen = (cyc - holdMs) * travel / passMs; // right → left pass
                } else if (cyc < 2 * holdMs + passMs) {
                    pen = travel; // tail hold
                } else {
                    pen = travel - (cyc - 2 * holdMs - passMs) * travel / passMs; // left → right pass
                }
                penX = x - (int) pen;
            }
            if (page == null) {
                return; // page undefined at commit: nothing to draw, the phase still advances
            }
            final int[] clip = {x, y, w, h};
            int px = penX;
            for (final char c : ascii.toCharArray()) {
                final AcmdCommand.Glyph g = page[c & 0x7F];
                if (g == null) {
                    px += AcmdOpcode.UNKNOWN_GLYPH_ADVANCE;
                    continue;
                }
                drawClippedGlyph(frame, g, px + g.xOff(), y + g.yOff(), color, clip);
                px += g.xAdvance();
            }
        }

        private void drawClippedGlyph(final GfxCanvas frame, final AcmdCommand.Glyph g, final int gx,
                                      final int gy, final int color, final int[] clip) {
            final int bytesPerRow = (g.w() + 7) >> 3;
            for (int row = 0; row < g.h(); row++) {
                for (int col = 0; col < g.w(); col++) {
                    if ((g.bitmap()[row * bytesPerRow + (col >> 3)] & (0x80 >> (col & 7))) != 0) {
                        final int px = gx + col;
                        final int py = gy + row;
                        if (px < clip[0] || px >= clip[0] + clip[2] || py < clip[1] || py >= clip[1] + clip[3]) {
                            continue;
                        }
                        frame.drawPixel(px, py, color);
                    }
                }
            }
        }
    }

    private static final class BlinkState implements Parametric {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final long periodMs;

        BlinkState(final AcmdCommand.Blink b) {
            this.x = b.x();
            this.y = b.y();
            this.w = b.w();
            this.h = b.h();
            this.periodMs = Math.max(1, b.periodMs());
        }

        @Override
        public void apply(final GfxCanvas frame, final long elapsedMs) {
            // Firmware phase: black during the second half of each period-boundary cycle
            // ((elapsed % period) >= period/2) — NOT toggle-every-floor(p/2), which drifts
            // for odd periods. Content phase = the base copy already in the frame.
            if (Math.floorMod(elapsedMs, periodMs) >= periodMs / 2) {
                frame.fillRegionDirect(x, y, w, h, BLACK); // dark phase
            }
        }
    }
}
