package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the radar loop math in {@link AircraftScreenService}: the frame
 * count covers whole sweep revolutions of one full info-page cycle, so the firmware's
 * modulo replay of the slot has no visible seam.
 */
class AircraftScreenServiceRadarLoopTests {

    private static final int[] DELAYS_MS = {10, 100, 150, 999, 4_000, 60_000};

    @Test
    void frameCountMatchesWholeRevolutionsPerDelay() {
        assertEquals(80, AircraftScreenService.radarFrameCount(100)); // 2 × 4000 / 100
        assertEquals(53, AircraftScreenService.radarFrameCount(150)); // max(2, 8000 / 150)
    }

    @Test
    void frameCountClampsToProtocolBounds() {
        assertEquals(200, AircraftScreenService.radarFrameCount(10));    // tiny delay → capped at 200
        assertEquals(2, AircraftScreenService.radarFrameCount(60_000));  // huge delay → floored at 2
    }

    @Test
    void sweepLandsOnWholeRevolutionAtLoopSeam() {
        for (final int delayMs : DELAYS_MS) {
            final int frameCount = AircraftScreenService.radarFrameCount(delayMs);

            // Cumulative angle at frame frameCount (the last rendered frame, 0-based
            // frameCount-1 → sweep step frameCount) must be an exact multiple of 360 so
            // the modulo loop restarts at the sweep's base angle.
            final int seamSweepDeg = AircraftScreenService.radarSweepDeg(frameCount - 1, frameCount);
            assertEquals(0, seamSweepDeg % 360,
                    "delay " + delayMs + " ms: cumulative angle at the seam must be ≡ 0 mod 360");

            // The restarted loop's first frame continues stepping from the same base angle.
            final int firstStepDeg = 360 * AircraftScreenService.SWEEP_LOOP_REVOLUTIONS / frameCount;
            final int restartStep = AircraftScreenService.radarSweepDeg(0, frameCount);
            assertEquals(firstStepDeg, restartStep, "delay " + delayMs + " ms: restart step");
            final int lastStep = AircraftScreenService.radarSweepDeg(frameCount, frameCount)
                    - AircraftScreenService.radarSweepDeg(frameCount - 1, frameCount);
            assertEquals(firstStepDeg, lastStep, "delay " + delayMs + " ms: seam step must not jump");
        }
    }

    @Test
    void routePageAlternatesWithWallClockDwellForEvenDelays() {
        assertRoutePageMatchesWallClock(100);
        assertRoutePageMatchesWallClock(150);
    }

    @Test
    void routePageFlipsOncePerHalfLoopAndWrapsPhaseStable() {
        for (final int delayMs : DELAYS_MS) {
            final int frameCount = AircraftScreenService.radarFrameCount(delayMs);
            assertFalse(AircraftScreenService.radarRoutePage(0, frameCount), "delay " + delayMs + " ms");
            assertTrue(AircraftScreenService.radarRoutePage(frameCount - 1, frameCount), "delay " + delayMs + " ms");

            int flips = 0;
            for (int i = 1; i < frameCount; i++) {
                if (AircraftScreenService.radarRoutePage(i, frameCount)
                        != AircraftScreenService.radarRoutePage(i - 1, frameCount)) {
                    flips++;
                }
            }
            assertEquals(1, flips, "delay " + delayMs + " ms: exactly one page flip per loop");

            // Phase stability across the loop boundary: the frame after the last one
            // (the loop's restart at index frameCount) must show the same page as frame 0.
            assertEquals(AircraftScreenService.radarRoutePage(0, frameCount),
                    AircraftScreenService.radarRoutePage(frameCount, frameCount),
                    "delay " + delayMs + " ms: page phase must wrap cleanly");
        }
    }

    private static void assertRoutePageMatchesWallClock(final int delayMs) {
        final int frameCount = AircraftScreenService.radarFrameCount(delayMs);
        for (int i = 0; i < frameCount; i++) {
            final boolean wallClockRoutePage =
                    (i * (long) delayMs / AircraftScreenService.PAGE_DWELL_MS) % 2 == 1;
            assertEquals(wallClockRoutePage, AircraftScreenService.radarRoutePage(i, frameCount),
                    "delay " + delayMs + " ms, frame " + i);
        }
    }
}
