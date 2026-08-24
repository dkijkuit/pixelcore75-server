package nl.ctasoftware.crypto.ticker.server.service.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GFX rasterization fixtures hand-computed by transcribing the vendored Adafruit_GFX.cpp
 * algorithms (writeLine Bresenham L132-173, drawLine L330-346, fillRect L300-307,
 * drawRect L639-647, drawCircle L472-509) — the firmware engine draws from the same
 * source, so these pixel sets are the parity contract.
 */
class GfxCanvasTests {

    private static final int COLOR = 0x1234;

    static Set<String> lit(final GfxCanvas canvas) {
        final Set<String> set = new HashSet<>();
        for (int y = 0; y < GfxCanvas.HEIGHT; y++) {
            for (int x = 0; x < GfxCanvas.WIDTH; x++) {
                if (canvas.pixel(x, y) == COLOR) {
                    set.add(x + "," + y);
                }
            }
        }
        return set;
    }

    static Set<String> px(final String... coords) {
        return Set.of(coords);
    }

    @Test
    void diagonalLine() {
        final GfxCanvas c = new GfxCanvas();
        c.drawLine(0, 0, 5, 5, COLOR);
        // dx=dy=5, err=dx/2=2: err never dips before the step, y advances every x
        assertEquals(px("0,0", "1,1", "2,2", "3,3", "4,4", "5,5"), lit(c));
    }

    @Test
    void shallowLine() {
        final GfxCanvas c = new GfxCanvas();
        c.drawLine(0, 0, 7, 3, COLOR);
        // dx=7, dy=3, err=3: x0:(0,0) err0; x1:(1,0) err-3<0 y1 err4; x2:(2,1) err1;
        // x3:(3,1) err-2<0 y2 err5; x4:(4,2) err2; x5:(5,2) err-1<0 y3 err6; x6:(6,3); x7:(7,3)
        assertEquals(px("0,0", "1,0", "2,1", "3,1", "4,2", "5,2", "6,3", "7,3"), lit(c));
    }

    @Test
    void steepLine() {
        final GfxCanvas c = new GfxCanvas();
        c.drawLine(0, 0, 2, 5, COLOR);
        // steep swap -> x-axis becomes y: Bresenham(0,0)-(5,2): (0,0),(0,1),(1,2),(1,3),(2,4),(2,5)
        assertEquals(px("0,0", "0,1", "1,2", "1,3", "2,4", "2,5"), lit(c));
    }

    @Test
    void steepLineIsDirectionInvariant() {
        final GfxCanvas forward = new GfxCanvas();
        forward.drawLine(0, 0, 2, 5, COLOR);
        final GfxCanvas backward = new GfxCanvas();
        backward.drawLine(2, 5, 0, 0, COLOR);
        assertEquals(lit(forward), lit(backward));
    }

    @Test
    void verticalLineSwapsCoordinates() {
        final GfxCanvas c = new GfxCanvas();
        c.drawLine(5, 7, 5, 3, COLOR);
        assertEquals(px("5,3", "5,4", "5,5", "5,6", "5,7"), lit(c));
    }

    @Test
    void rectBounds() {
        final GfxCanvas c = new GfxCanvas();
        c.drawRect(2, 3, 4, 5, COLOR);
        final Set<String> expected = new HashSet<>();
        for (int x = 2; x <= 5; x++) {
            expected.add(x + ",3");
            expected.add(x + ",7");
        }
        for (int y = 3; y <= 7; y++) {
            expected.add("2," + y);
            expected.add("5," + y);
        }
        assertEquals(expected, lit(c));
        assertEquals(14, lit(c).size(), "4x5 outline = 2*(4+5)-4 pixels");
    }

    @Test
    void rectOfOnePixelIsOnePixel() {
        final GfxCanvas c = new GfxCanvas();
        c.drawRect(10, 10, 1, 1, COLOR);
        assertEquals(px("10,10"), lit(c));
    }

    @Test
    void fillBounds() {
        final GfxCanvas c = new GfxCanvas();
        c.fillRect(2, 3, 4, 5, COLOR);
        final Set<String> expected = new HashSet<>();
        for (int y = 3; y <= 7; y++) {
            for (int x = 2; x <= 5; x++) {
                expected.add(x + "," + y);
            }
        }
        assertEquals(expected, lit(c));
        assertEquals(20, lit(c).size());
    }

    @Test
    void fillWithZeroHeightKeepsGfxQuirk() {
        final GfxCanvas c = new GfxCanvas();
        c.fillRect(2, 10, 3, 0, COLOR);
        // Adafruit fillRect(w>0,h=0): writeFastVLine(i,y,0) -> writeLine(i,y,i,y-1) -> steep
        // round-trip paints rows y-1 and y. Literal transcription, not a "fix".
        assertEquals(px("2,9", "3,9", "4,9", "2,10", "3,10", "4,10"), lit(c));
    }

    @Test
    void fillWithZeroWidthDrawsNothing() {
        final GfxCanvas c = new GfxCanvas();
        c.fillRect(2, 10, 0, 5, COLOR);
        assertEquals(Set.of(), lit(c));
    }

    @Test
    void circleRadius0() {
        final GfxCanvas c = new GfxCanvas();
        c.drawCircle(10, 10, 0, COLOR);
        assertEquals(px("10,10"), lit(c));
    }

    @Test
    void circleRadius1() {
        final GfxCanvas c = new GfxCanvas();
        c.drawCircle(10, 10, 1, COLOR);
        assertEquals(px("10,9", "10,11", "9,10", "11,10"), lit(c));
    }

    @Test
    void circleRadius3() {
        final GfxCanvas c = new GfxCanvas();
        c.drawCircle(10, 10, 3, COLOR);
        // initial: (0,+-3),(+-3,0); iter1 (x=1,y=3): (+-1,+-3),(+-3,+-1);
        // iter2 (x=2,y=2): (+-2,+-2) + symmetric dups
        assertEquals(px(
                "10,13", "10,7", "13,10", "7,10",
                "11,13", "9,13", "11,7", "9,7",
                "13,11", "7,11", "13,9", "7,9",
                "12,12", "8,12", "12,8", "8,8"), lit(c));
    }

    @Test
    void circleRadius5() {
        final GfxCanvas c = new GfxCanvas();
        c.drawCircle(10, 10, 5, COLOR);
        // iterations: (x=1,y=5),(x=2,y=5),(x=3,y=4),(x=4,y=3)
        assertEquals(px(
                "10,15", "10,5", "15,10", "5,10",
                "11,15", "9,15", "11,5", "9,5", "15,11", "5,11", "15,9", "5,9",
                "12,15", "8,15", "12,5", "8,5", "15,12", "5,12", "15,8", "5,8",
                "13,14", "7,14", "13,6", "7,6", "14,13", "6,13", "14,7", "6,7"), lit(c));
    }

    @Test
    void circlePartiallyOffCanvasClipsPerPixel() {
        final GfxCanvas c = new GfxCanvas();
        c.drawCircle(0, 0, 3, COLOR); // negative/overflow coordinates silently ignored
        assertTrue(lit(c).contains("3,0"));
        assertTrue(lit(c).contains("0,3"));
        assertTrue(lit(c).contains("2,2"));
        assertEquals(5, lit(c).size(), "only the first-quadrant pixels survive");
    }

    @Test
    void outOfBoundsPixelsIgnored() {
        final GfxCanvas c = new GfxCanvas();
        c.drawPixel(-1, 0, COLOR);
        c.drawPixel(64, 0, COLOR);
        c.drawPixel(0, -1, COLOR);
        c.drawPixel(0, 32, COLOR);
        c.drawPixel(200, 200, COLOR);
        assertEquals(Set.of(), lit(c));
    }
}
