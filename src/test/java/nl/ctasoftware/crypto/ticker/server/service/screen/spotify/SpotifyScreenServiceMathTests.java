package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the Now Playing layout math: frame budget within the
 * protocol's 2-200 wire range, an invisible marquee seam (both wrap extremes
 * off-canvas), frozen/clamped progress, and the bar/time formatting.
 */
class SpotifyScreenServiceMathTests {

    @Test
    void frameCountFollowsSlotAndDelay() {
        assertEquals(120, SpotifyScreenService.frameCount(30_000, 250)); // 30s @ 250ms
        assertEquals(60, SpotifyScreenService.frameCount(30_000, 500));
        assertEquals(40, SpotifyScreenService.frameCount(10_000, 250)); // default slot @ default delay
    }

    @Test
    void frameCountClampsToWireRange() {
        assertEquals(200, SpotifyScreenService.frameCount(30_000, 100)); // 300 wanted → capped
        assertEquals(4, SpotifyScreenService.frameCount(1_000, 250));    // small slots stay exact
        assertEquals(2, SpotifyScreenService.frameCount(400, 250));      // 1.6 → floored to 2
        assertEquals(2, SpotifyScreenService.frameCount(0, 250));        // degenerate slot
    }

    @Test
    void fittingTextStaysLeftAligned() {
        assertEquals(0, SpotifyScreenService.marqueeX(0, 64, 64));
        assertEquals(0, SpotifyScreenService.marqueeX(60_000, 30, 64));
    }

    @Test
    void overflowingTextSlidesOutLeftThenReentersFromTheRight() {
        final int textWidth = 100; // cycle = 100 + 64 + 12 = 176 px; at 12 px/s = 1 px per 83⅓ ms
        final int cycle = textWidth + 64 + SpotifyScreenService.SCROLL_GAP_PX;

        // t=0: text left-aligned, head visible at the canvas edge.
        assertEquals(0, SpotifyScreenService.marqueeX(0, textWidth, 64));

        // Slides left while the tail crosses the canvas: x = -offset.
        assertEquals(-12, SpotifyScreenService.marqueeX(1_000, textWidth, 64)); // 12px/s × 1s
        assertEquals(-48, SpotifyScreenService.marqueeX(4_000, textWidth, 64));

        // Just past fully-out (offset > textWidth): re-entering from the right,
        // still off-canvas → the wrap is invisible.
        final long justPastOutMs = (long) Math.ceil((textWidth + 1) * 1000.0 / SpotifyScreenService.SCROLL_PX_PER_SEC);
        final int xPastOut = SpotifyScreenService.marqueeX(justPastOutMs, textWidth, 64);
        assertTrue(xPastOut > 0, "past fully-out the text re-enters from the right (positive x)");
        assertTrue(xPastOut <= cycle - textWidth, "re-entry x stays within the off-canvas window");

        // The modulo seam: elapsed = cycle lands exactly like elapsed = 0.
        final long cycleMs = (long) Math.round(cycle * 1000.0 / SpotifyScreenService.SCROLL_PX_PER_SEC);
        assertEquals(SpotifyScreenService.marqueeX(0, textWidth, 64),
                SpotifyScreenService.marqueeX(cycleMs, textWidth, 64));
    }

    @Test
    void progressFreezesWhenPausedAndClampsToTrackLength() {
        assertEquals(42_000, SpotifyScreenService.progressAtFrame(42_000, false, 60_000, 200_000));
        assertEquals(102_000, SpotifyScreenService.progressAtFrame(42_000, true, 60_000, 200_000));
        assertEquals(200_000, SpotifyScreenService.progressAtFrame(190_000, true, 60_000, 200_000));
        assertEquals(0, SpotifyScreenService.progressAtFrame(-5, true, 0, 200_000));
    }

    @Test
    void barWidthMapsProgressToCanvas() {
        assertEquals(32, SpotifyScreenService.barWidthPx(120_000, 240_000, 64));
        assertEquals(64, SpotifyScreenService.barWidthPx(240_000, 240_000, 64));
        assertEquals(64, SpotifyScreenService.barWidthPx(999_999, 240_000, 64)); // clamped
        assertEquals(0, SpotifyScreenService.barWidthPx(0, 240_000, 64));
        assertEquals(0, SpotifyScreenService.barWidthPx(10_000, 0, 64)); // unknown duration
    }

    @Test
    void formatTimeUsesMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", SpotifyScreenService.formatTime(0));
        assertEquals("0:05", SpotifyScreenService.formatTime(5_000));
        assertEquals("1:00", SpotifyScreenService.formatTime(60_000));
        assertEquals("4:59", SpotifyScreenService.formatTime(299_000));
        assertEquals("75:03", SpotifyScreenService.formatTime(4_503_000));
        assertEquals("0:00", SpotifyScreenService.formatTime(-1_000));
    }
}
