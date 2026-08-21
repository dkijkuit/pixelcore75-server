package nl.ctasoftware.crypto.ticker.server.service.command;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirror execution semantics: TEXT rendering against a hand-built font page (pixel-exact),
 * the parametric primitives over time (SWEEP theta, SCROLL restart/clipping/snapshot,
 * BLINK phase), first-parametric-wins, base immutability, and the RGB565 preview bridge.
 */
class AcmdMirrorTests {

    private static final int BLACK = 0x0000;
    private static final int RED = 0xF800;
    private static final int GREEN = 0x07E0;
    private static final int WHITE = 0xFFFF;

    private static List<AcmdCommand.Glyph> handGlyphs() {
        return List.of(
                new AcmdCommand.Glyph('A', 2, 2, 3, 0, 0, new byte[]{(byte) 0x80, (byte) 0x40}),
                new AcmdCommand.Glyph('B', 1, 1, 2, 0, 0, new byte[]{(byte) 0x80}),
                new AcmdCommand.Glyph('C', 1, 1, 1, -1, 1, new byte[]{(byte) 0x80}));
    }

    static Set<String> where(final int[] frame, final int color) {
        final Set<String> set = new HashSet<>();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                if (frame[y * 64 + x] == color) {
                    set.add(x + "," + y);
                }
            }
        }
        return set;
    }

    @Test
    void textRendersHandBuiltPagePixelExactly() {
        final byte[] framed = CommandBatch.builder()
                .fontPage(0, handGlyphs())
                .text(0, 5, 7, WHITE, "ABC")
                .build();

        final Set<String> lit = where(AcmdMirror.parse(framed).baseFrame(), WHITE);
        // A: (5,7),(6,8) pen 5->8; B: (8,7) pen->10; C at (10-1, 7+1) = (9,8)
        assertEquals(Set.of("5,7", "6,8", "8,7", "9,8"), lit);
    }

    @Test
    void unknownGlyphAdvancesPenByFourWithoutDrawing() {
        final byte[] framed = CommandBatch.builder()
                .fontPage(0, handGlyphs())
                .text(0, 5, 7, WHITE, "A?B") // '?' not in page
                .build();

        final Set<String> lit = where(AcmdMirror.parse(framed).baseFrame(), WHITE);
        // A: pen 5->8; '?': pen 8->12 (no pixels); B: (12,7)
        assertEquals(Set.of("5,7", "6,8", "12,7"), lit);
    }

    @Test
    void textWithUndefinedPageRendersNothing() {
        final byte[] framed = CommandBatch.builder()
                .fontPage(0, handGlyphs())
                .text(1, 5, 7, WHITE, "A") // page 1 never uploaded
                .build();

        assertEquals(Set.of(), where(AcmdMirror.parse(framed).baseFrame(), WHITE));
    }

    @Test
    void sweepThetaAtT0T1000T2000ForSpeed90() {
        final byte[] framed = CommandBatch.builder()
                .cls(BLACK)
                .sweep(30, 16, 5, WHITE, 90)
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(framed);

        // t=0: theta=0 -> endpoint (35,16) -> horizontal line
        assertEquals(Set.of("30,16", "31,16", "32,16", "33,16", "34,16", "35,16"),
                where(mirror.frameAt(0), WHITE));
        // t=1000: theta=90 -> endpoint (30,21) -> vertical line
        assertEquals(Set.of("30,16", "30,17", "30,18", "30,19", "30,20", "30,21"),
                where(mirror.frameAt(1000), WHITE));
        // t=2000: theta=180 -> endpoint (25,16) -> horizontal line back
        assertEquals(Set.of("25,16", "26,16", "27,16", "28,16", "29,16", "30,16"),
                where(mirror.frameAt(2000), WHITE));
        // t=5000: theta=450 mod 360 = 90 -> identical to t=1000
        assertArrayEquals(mirror.frameAt(1000), mirror.frameAt(5000));
    }

    @Test
    void blinkAlternatesContentAndBlackEveryHalfPeriod() {
        final byte[] framed = CommandBatch.builder()
                .cls(RED)
                .blink(2, 3, 4, 5, 1000)
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(framed);

        assertTrue(allRed(mirror.frameAt(0)));
        assertTrue(allRed(mirror.frameAt(499)));
        final int[] dark = mirror.frameAt(500);
        assertEquals(BLACK, dark[3 * 64 + 2], "region top-left is black in dark phase");
        assertEquals(BLACK, dark[7 * 64 + 5], "region bottom-right is black in dark phase");
        assertEquals(RED, dark[3 * 64 + 1], "left of region keeps base content");
        assertEquals(RED, dark[8 * 64 + 5], "below region keeps base content");
        assertTrue(!allRed(mirror.frameAt(999)), "still dark at 999 (half=500, phase 1)");
        assertTrue(allRed(mirror.frameAt(1000)), "content phase restarts at 1000");
        assertFalse(allRed(mirror.frameAt(1500)), "dark again at 1500");
        assertFalse(allRed(mirror.frameAt(1999)), "still dark at 1999");
        assertTrue(allRed(mirror.frameAt(2000)), "content again at 2000");
    }

    private static boolean allRed(final int[] frame) {
        for (final int c : frame) {
            if (c != RED) {
                return false;
            }
        }
        return true;
    }

    @Test
    void scrollStepsClipAndRestart() {
        final byte[] framed = CommandBatch.builder()
                .cls(BLACK)
                .pix(5, 4, RED) // base content inside the region, must survive every step
                .fontPage(0, handGlyphs())
                .scroll(0, 0, 20, 8, 0, WHITE, 10, "A") // textWidth=3, cycle=20+3+1=24
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(framed);

        // t=0: penX=20 -> glyph starts exactly at the region's right edge, clipped out
        assertEquals(Set.of(), where(mirror.frameAt(0), WHITE));
        assertEquals(Set.of("5,4"), where(mirror.frameAt(0), RED), "snapshot content visible");

        // t=10: scrolledPx=1 -> penX=19 -> (19,0) visible, (20,1) clipped by region
        assertEquals(Set.of("19,0"), where(mirror.frameAt(10), WHITE));

        // t=100: scrolledPx=10 -> penX=10 -> both glyph pixels visible
        assertEquals(Set.of("10,0", "11,1"), where(mirror.frameAt(100), WHITE));
        assertEquals(Set.of("5,4"), where(mirror.frameAt(100), RED), "snapshot restored under text");

        // t=230: scrolledPx=23 -> penX=-3 -> fully scrolled out left
        assertEquals(Set.of(), where(mirror.frameAt(230), WHITE));

        // t=240: scrolledPx=24 -> mod cycle 24 -> restart at penX=20 (same as t=0)
        assertEquals(Set.of(), where(mirror.frameAt(240), WHITE));
        assertEquals(Set.of("19,0"), where(mirror.frameAt(250), WHITE), "text re-enters after restart");
    }

    @Test
    void scrollClipsGlyphsToRegionRows() {
        final byte[] framed = CommandBatch.builder()
                .fontPage(0, handGlyphs())
                .scroll(0, 0, 20, 1, 0, WHITE, 10, "A") // region only 1px tall
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(framed);

        // glyph A is 2px tall; only its top row (y=0) may draw inside the h=1 region
        mirror.frameAt(10); // advance to a visible position
        final Set<String> lit = new HashSet<>();
        for (int t = 0; t <= 300; t += 10) {
            final int[] frame = mirror.frameAt(t);
            for (final String px : where(frame, WHITE)) {
                lit.add(px);
            }
        }
        for (final String px : lit) {
            assertEquals('0', px.charAt(px.length() - 1), "only row 0 is inside the region: " + px);
        }
        assertFalse(lit.isEmpty(), "glyph did scroll through the visible band");
    }

    @Test
    void firstParametricPrimitiveWins() {
        final byte[] sweepThenBlink = CommandBatch.builder()
                .cls(RED)
                .sweep(30, 16, 5, WHITE, 90)
                .blink(2, 3, 4, 5, 1000)
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(sweepThenBlink);
        // at t=500 the blink would black its region; the sweep owns the frame instead
        final int[] frame = mirror.frameAt(500);
        assertEquals(RED, frame[3 * 64 + 2], "blink region untouched: sweep won");
        assertTrue(where(frame, WHITE).contains("30,16"), "sweep pivot drawn");
        // theta=(500*90/1000)=45deg -> endpoint (34,20)
        assertTrue(where(frame, WHITE).contains("34,20"));

        final byte[] blinkThenSweep = CommandBatch.builder()
                .cls(RED)
                .blink(2, 3, 4, 5, 1000)
                .sweep(30, 16, 5, WHITE, 90)
                .build();
        final int[] frame2 = AcmdMirror.parse(blinkThenSweep).frameAt(500);
        assertEquals(BLACK, frame2[3 * 64 + 2], "blink active");
        assertEquals(Set.of(), where(frame2, WHITE), "sweep ignored: blink won");
    }

    @Test
    void frameAtNeverMutatesTheBase() {
        final byte[] framed = CommandBatch.builder()
                .cls(RED)
                .fontPage(0, handGlyphs())
                .scroll(0, 0, 20, 8, 0, WHITE, 10, "A")
                .build();
        final AcmdMirror mirror = AcmdMirror.parse(framed);
        final int[] base = mirror.baseFrame();

        mirror.frameAt(10);
        mirror.frameAt(1000000);

        assertArrayEquals(base, mirror.baseFrame());
    }

    @Test
    void toBufferedImageExpandsRgb565ByBitReplication() {
        final int[] frame = new int[2048];
        frame[0] = RED;
        frame[1] = GREEN;
        frame[2] = 0x001F;

        final BufferedImage img = AcmdMirror.toBufferedImage(frame);

        assertEquals(64, img.getWidth());
        assertEquals(32, img.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType());
        assertEquals(0xFF0000, img.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x00FF00, img.getRGB(1, 0) & 0xFFFFFF, "g6=63 expands to 255 (252|3)");
        assertEquals(0x0000FF, img.getRGB(2, 0) & 0xFFFFFF);
        assertEquals(0x000000, img.getRGB(63, 31) & 0xFFFFFF);
    }

    @Test
    void baseCanvasRendersFixedCommands() {
        final byte[] framed = CommandBatch.builder()
                .cls(BLACK)
                .pix(1, 1, RED)
                .line(10, 2, 15, 2, GREEN)
                .rect(20, 3, 3, 3, WHITE)
                .fill(30, 4, 2, 2, WHITE)
                .circ(40, 10, 2, WHITE)
                .nop()
                .build();
        final int[] frame = AcmdMirror.parse(framed).baseFrame();

        assertEquals(RED, frame[1 * 64 + 1]);
        assertEquals(GREEN, frame[2 * 64 + 12]);
        assertEquals(WHITE, frame[3 * 64 + 20], "rect top-left");
        assertEquals(BLACK, frame[4 * 64 + 21], "rect outline not filled");
        assertEquals(WHITE, frame[5 * 64 + 31], "fill interior");
        assertEquals(WHITE, frame[10 * 64 + 42], "circle right of center");
    }
}
