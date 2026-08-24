package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the radar loop math in {@link AircraftScreenService}: the frame
 * count covers whole sweep revolutions of one full info-page cycle, so the firmware's
 * modulo replay of the slot has no visible seam. The info column cycles detail pages
 * (telemetry/route/registry) for the closest aircraft in both paths.
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
    void infoPageSpreadsFramesEvenlyAndCompletesOneCyclePerLoop() {
        final int frameCount = AircraftScreenService.radarFrameCount(100); // 80
        for (final int pageCount : new int[]{1, 2, 3}) {
            assertEquals(0, AircraftScreenService.radarInfoPage(0, frameCount, pageCount));
            assertEquals(pageCount - 1,
                    AircraftScreenService.radarInfoPage(frameCount - 1, frameCount, pageCount));
            // Monotone non-decreasing, advancing by at most one per frame; the cap keeps
            // the index in range at and past the seam, so the modulo replay restarts
            // the cycle on page 0 (telemetry).
            for (int i = 1; i <= frameCount; i++) {
                final int prev = AircraftScreenService.radarInfoPage(i - 1, frameCount, pageCount);
                final int cur = AircraftScreenService.radarInfoPage(i, frameCount, pageCount);
                assertTrue(cur == prev || cur == prev + 1, "info page must advance by at most one");
            }
        }
    }

    @Test
    void twoInfoPagesMatchAWallClockDwellForEvenDelays() {
        for (final int delayMs : new int[]{100, 150}) {
            final int frameCount = AircraftScreenService.radarFrameCount(delayMs);
            for (int i = 0; i < frameCount; i++) {
                final boolean wallClockSecondPage =
                        (i * (long) delayMs / AircraftScreenService.SWEEP_REVOLUTION_MS) % 2 == 1;
                assertEquals(wallClockSecondPage,
                        AircraftScreenService.radarInfoPage(i, frameCount, 2) == 1,
                        "delay " + delayMs + " ms, frame " + i);
            }
        }
    }

    @Test
    void commandPageIndexSlicesTheSlotIntoEqualPageWindows() {
        // 12 s slot over 3 pages: 2000 ms cadence, 2 refreshes per page → page
        // windows [0,4000) [4000,8000) [8000,12000).
        assertEquals(0, AircraftScreenService.radarPageIndex(0, 2_000, 2, 3));
        assertEquals(0, AircraftScreenService.radarPageIndex(3_999, 2_000, 2, 3));
        assertEquals(1, AircraftScreenService.radarPageIndex(4_000, 2_000, 2, 3));
        assertEquals(2, AircraftScreenService.radarPageIndex(8_000, 2_000, 2, 3));
        assertEquals(2, AircraftScreenService.radarPageIndex(11_999, 2_000, 2, 3),
                "the last page holds through the slot tail instead of wrapping mid-slot");
        assertEquals(0, AircraftScreenService.radarPageIndex(123_456, 2_000, 2, 1), "single page never moves");

        // The regression this grid exists for: a 10 s slot over 3 pages must NOT run
        // the old fixed 4000 ms dwell (4/4/2 s — the 3rd page 50% shorter); the
        // cadence adapts to 1666 ms so every page gets a third of the display time.
        final long slot = 10_000;
        final int pageCount = 3;
        final long refreshMs = AircraftScreenService.radarRefreshMs(slot, pageCount);
        final int refreshesPerPage = AircraftScreenService.radarRefreshesPerPage(slot, pageCount, refreshMs);
        assertEquals(1_666, refreshMs);
        assertEquals(2, refreshesPerPage);
        for (int p = 0; p < pageCount; p++) {
            final long pageStart = (long) p * refreshesPerPage * refreshMs;
            final long pageEnd = p == pageCount - 1 ? slot : (long) (p + 1) * refreshesPerPage * refreshMs;
            assertEquals(p, AircraftScreenService.radarPageIndex(pageStart, refreshMs, refreshesPerPage, pageCount));
            assertEquals(p, AircraftScreenService.radarPageIndex(pageEnd - 1, refreshMs, refreshesPerPage, pageCount));
            assertTrue(Math.abs((pageEnd - pageStart) - slot / pageCount) <= refreshMs,
                    "page " + p + " shows " + (pageEnd - pageStart) + " ms, expected ~" + (slot / pageCount));
        }
    }

    @Test
    void refreshCadenceTilesSlotsIntoEqualPageWindows() {
        assertEquals(2_000, AircraftScreenService.radarRefreshMs(12_000, 3));
        assertEquals(2_000, AircraftScreenService.radarRefreshMs(30_000, 3)); // 5 refreshes per page
        assertEquals(2_000, AircraftScreenService.radarRefreshMs(60_000, 3)); // long slot: blips stay 2 s fresh
        assertEquals(1_666, AircraftScreenService.radarRefreshMs(5_000, 3));
        assertEquals(1_666, AircraftScreenService.radarRefreshMs(10_000, 3));
        assertEquals(2_333, AircraftScreenService.radarRefreshMs(7_000, 3), // u8 speed cap: one refresh per page
                "cadence may exceed RADAR_REFRESH_MS when the tighter grid is infeasible");
        assertEquals(2_000, AircraftScreenService.radarRefreshMs(10_000, 1), "single page keeps the plain cadence");
        assertEquals(1_666, AircraftScreenService.radarRefreshMs(10_000, 2), "3 refreshes per page");

        for (final long slot : new long[]{5_000, 6_000, 7_000, 10_000, 12_000, 15_000, 30_000, 60_000}) {
            for (final int pageCount : new int[]{2, 3}) {
                final long refreshMs = AircraftScreenService.radarRefreshMs(slot, pageCount);
                final int refreshesPerPage =
                        AircraftScreenService.radarRefreshesPerPage(slot, pageCount, refreshMs);
                assertTrue(refreshMs >= AircraftScreenService.MIN_RADAR_REFRESH_MS,
                        slot + " ms slot: cadence feasible for a u8 sweep speed");

                // One whole sweep revolution per refresh (± the integer-degree speed rounding):
                // the republish re-arms the sweep exactly where the previous one wrapped.
                final int speed = AircraftScreenService.radarSweepDegPerSec(refreshMs);
                assertTrue(Math.abs(refreshMs * (long) speed - 360_000) <= refreshMs / 2.0,
                        slot + " ms slot: whole revolution per refresh");

                // Every page window within one grid step of slot/pageCount — no page
                // truncated by the slot end (the 4/4/2 s bug).
                for (int p = 0; p < pageCount; p++) {
                    final long pageStart = (long) p * refreshesPerPage * refreshMs;
                    final long pageEnd = p == pageCount - 1 ? slot : (long) (p + 1) * refreshesPerPage * refreshMs;
                    assertTrue(Math.abs((pageEnd - pageStart) - slot / pageCount) <= refreshMs,
                            slot + " ms slot, " + pageCount + " pages: page " + p + " shows "
                                    + (pageEnd - pageStart) + " ms");
                }
            }
        }
    }

    @Test
    void refreshBatchesSelectThePageOfTheirTargetGridPoint() {
        final List<AircraftScreenService.RadarInfoPage> pages = List.of(
                AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.RadarInfoPage.REGISTRY);

        // 12 s slot (2000 ms cadence, 2 refreshes per page): pipelining — a batch
        // rendered during [0,2000) publishes at grid 2000 (still telemetry); one
        // rendered during [2000,4000) publishes at grid 4000 — where the ROUTE page
        // begins — so it must already carry ROUTE. Without the lead the whole
        // rotation trails the grid by one refresh interval and a rotation's last
        // page (REGISTRY) can be pushed past the slot end.
        assertEquals(AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.radarPageForRenderElapsed(0, pages, 2_000, 2));
        assertEquals(AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.radarPageForRenderElapsed(1_999, pages, 2_000, 2));
        assertEquals(AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.radarPageForRenderElapsed(2_000, pages, 2_000, 2), "targets grid 4000 = ROUTE");
        assertEquals(AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.radarPageForRenderElapsed(5_999, pages, 2_000, 2));
        assertEquals(AircraftScreenService.RadarInfoPage.REGISTRY,
                AircraftScreenService.radarPageForRenderElapsed(6_000, pages, 2_000, 2), "targets grid 8000 = REGISTRY");
        assertEquals(AircraftScreenService.RadarInfoPage.REGISTRY,
                AircraftScreenService.radarPageForRenderElapsed(10_500, pages, 2_000, 2),
                "a target past the last boundary clamps to the page that holds to the slot end");

        // The 10 s regression slot: 1666 ms cadence, 2 refreshes per page → ROUTE
        // begins at grid 3332 and REGISTRY at grid 6664 (not the old fixed
        // 4000/8000, which cut REGISTRY to the slot's final 2 s).
        assertEquals(AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.radarPageForRenderElapsed(1_665, pages, 1_666, 2));
        assertEquals(AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.radarPageForRenderElapsed(1_666, pages, 1_666, 2), "targets grid 3332 = ROUTE");
        assertEquals(AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.radarPageForRenderElapsed(3_332, pages, 1_666, 2), "targets grid 4998, still ROUTE");
        assertEquals(AircraftScreenService.RadarInfoPage.REGISTRY,
                AircraftScreenService.radarPageForRenderElapsed(4_999, pages, 1_666, 2), "targets grid 6665 = REGISTRY");
    }
}
