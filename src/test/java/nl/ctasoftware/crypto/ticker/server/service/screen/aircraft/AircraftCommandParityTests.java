package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayMode;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayUnits;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbAircraftData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbRouteData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftInfoClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.NearbyAircraft;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-image parity for the NEARBY_AIRCRAFT command screens (plan §6): frame-path AWT
 * renders vs {@link AcmdMirror} renders of the command batches, both in panel RGB565,
 * from the same (mocked) aircraft list — no docker/network needed, the adsb/adsbdb
 * clients are interfaces and get fixtures, enrichment legs answer empty.
 *
 * <p>Tolerances (both engines reduced to the panel's nearest 5-6-5 quantization):
 * <ul>
 * <li>LIST / CLOSEST: &ge;95% pixel equality (budget 102 of 2048 px; measured 0 — the
 * extractor thresholds the very outlines AWT fills, and these pixel fonts have integer
 * advances, so pen positions agree exactly). The budget guards JDK font-rendering
 * variance, and fixtures use strings that fit, so the TEXT branch renders exactly the
 * frame path's untruncated strings.</li>
 * <li>RADAR: budget 160 px (7.8%; measured &le;96). Three documented ACMD v1 semantics
 * differ from the frame path on purpose: the frame path draws a 6-segment sweep trail
 * (5 extra lines, ~65 px) while SWEEP is a single line; the frame path's rings are AWT
 * drawOval rasterizations (half-pixel-centered boxes) while CIRC is the GFX midpoint
 * circle; and frame blips dim outside the sweep's 90° illumination window while command
 * blips are statically fully lit. Ring placement, blip projection/size and text are
 * otherwise exact.</li>
 * </ul>
 */
class AircraftCommandParityTests {

    private static final int TEXT_MISMATCH_BUDGET = 102; // 5% of 2048; measured 0 (pixel fonts agree exactly)
    private static final int RADAR_MISMATCH_BUDGET = 160; // 7.8%; measured <= 96 (see class docs)

    private static final List<NearbyAircraft> FIXTURE = List.of(
            new NearbyAircraft("484507", "KLM123", "PH-EXM", "B738", null,
                    8_000, false, 350.0, 90.0, 0, 12.3, 60.0),
            new NearbyAircraft("484508", "TRA456", null, "A320", null,
                    null, true, 250.0, 110.0, 0, 3.1, 110.0),
            new NearbyAircraft("484509", "MIL789", null, "F16", null,
                    25_000, false, 400.0, 200.0, 500, 40.0, 200.0));

    private final AircraftClient aircraftClient = mock(AircraftClient.class);
    private final AircraftInfoClient infoClient = mock(AircraftInfoClient.class);

    private AircraftScreenService service;

    @BeforeEach
    void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        final Font cgPixel = FontPageExtractor.loadFont(new File("assets/fonts/cg-pixel-4x5.ttf"), 5f);
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(FIXTURE);
        when(infoClient.getAircraftDetails(any())).thenReturn(Optional.empty());
        when(infoClient.getRoute(any())).thenReturn(Optional.empty());
        service = new AircraftScreenService(
                new PaintToolsService(null, null, ledBoard), ledBoard, cgPixel, aircraftClient, infoClient);
    }

    @Test
    void listCommandBatchMatchesGoldenFrame() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.LIST);

        final BufferedImage golden = service.renderScreen(config).orElseThrow();
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));

        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(golden), mirror.frameAt(0));
        assertTrue(mismatch <= TEXT_MISMATCH_BUDGET,
                "LIST parity: " + mismatch + " mismatched px, budget " + TEXT_MISMATCH_BUDGET);
    }

    @Test
    void closestCommandBatchMatchesGoldenIdentityPage() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);

        // First page of the frame stream = identity page; the batch renders that page.
        final BufferedImage golden = service.renderFrameStream(config).frames().getFirst();
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));
        assertFalse(mirror.hasParametric(), "identity page is static");

        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(golden), mirror.frameAt(0));
        assertTrue(mismatch <= TEXT_MISMATCH_BUDGET,
                "CLOSEST parity: " + mismatch + " mismatched px, budget " + TEXT_MISMATCH_BUDGET);
    }

    @Test
    void closestCommandBatchesCycleRegistryAndRoutePages() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(route()));

        final CommandScreenService.BatchStream stream = service.renderCommandBatches(config);

        assertEquals(3, stream.batches().size(), "identity + registry + route pages");
        assertEquals(10_000, stream.pageDwellMs(), "30 s slot spread evenly over 3 pages");

        // Golden pages come from the frame path (same enrichment); the cycled batches
        // must render pages 2 and 3 like the frames the panel would have played.
        final List<BufferedImage> goldenPages = service.renderFrameStream(config).frames();
        assertTextPageParity(goldenPages.get(1), stream.batches().get(1), "registry");
        assertTextPageParity(goldenPages.get(2), stream.batches().get(2), "route");
    }

    @Test
    void closestWithoutEnrichmentRendersASingleIdentityBatch() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);
        // Feed synthesis turns a feed type code into a registry page, so "no
        // enrichment at all" needs a type-less feed aircraft too.
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(List.of(
                new NearbyAircraft("484507", "KLM123", "PH-EXM", null, null,
                        8_000, false, 350.0, 90.0, 0, 12.3, 60.0)));

        final CommandScreenService.BatchStream stream = service.renderCommandBatches(config);

        assertEquals(1, stream.batches().size(), "identity page only when no enrichment resolves");
        assertEquals(0, stream.pageDwellMs(), "nothing to cycle");
        assertArrayEquals(service.renderCommandBatch(config), stream.batches().getFirst());
    }

    @Test
    void listAndRadarModesKeepASingleBatch() {
        for (final AircraftDisplayMode mode : List.of(AircraftDisplayMode.LIST, AircraftDisplayMode.RADAR)) {
            final CommandScreenService.BatchStream stream = service.renderCommandBatches(config(mode));

            assertEquals(1, stream.batches().size(), mode + " has no pages to cycle");
            assertEquals(0, stream.pageDwellMs());
        }
    }

    @Test
    void nonAsciiEnrichmentTransliteratesInsteadOfFailingTheBatch() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(new AdsbdbRouteData("KLM123",
                new AdsbdbRouteData.Airline("LATAM Chile", "LATAM", "LA", "Chile"),
                new AdsbdbRouteData.Airport(null, "São Paulo", "GRU", null, null),
                new AdsbdbRouteData.Airport(null, "Bogotá", "BOG", null, null))));

        // Before the ascii() sanitizer this threw IllegalArgumentException ("character
        // 'á' outside ASCII 32..126") and dropped the panel to the frame path.
        final CommandScreenService.BatchStream stream = service.renderCommandBatches(config);

        assertEquals(3, stream.batches().size(), "route page still builds");
        final List<String> texts = AcmdParser.parse(stream.batches().get(2)).commands().stream()
                .filter(AcmdCommand.Text.class::isInstance)
                .map(AcmdCommand.Text.class::cast)
                .map(AcmdCommand.Text::ascii)
                .toList();
        assertTrue(texts.contains("GRU>BOG"), () -> texts.toString());
        assertTrue(texts.contains("Sao Paulo"), () -> texts.toString());
        assertTrue(texts.contains("Bogota"), () -> texts.toString());
        assertTrue(texts.stream().noneMatch(t -> t.chars().anyMatch(c -> c < 32 || c > 126)),
                "payloads are spec-ASCII");
    }

    @Test
    void metricUnitsRenderDistancesInKilometers() {
        final AircraftScreenConfig config = new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, 30,
                AircraftDisplayMode.CLOSEST, new LatLon(52.3, 4.9), 50, false,
                AircraftDisplayUnits.METRIC, 100);

        final List<String> texts = AcmdParser.parse(service.renderCommandBatch(config)).commands().stream()
                .filter(AcmdCommand.Text.class::isInstance)
                .map(AcmdCommand.Text.class::cast)
                .map(AcmdCommand.Text::ascii)
                .toList();
        // FIXTURE[0] sits 12.3 NM out = 22.8 km → "23KM" (previously hardcoded "12NM").
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("23KM")), () -> texts.toString());
        assertTrue(texts.stream().noneMatch(t -> t.contains("NM")), () -> texts.toString());
    }

    private static void assertTextPageParity(final BufferedImage golden, final byte[] batch, final String label) {
        final AcmdMirror mirror = AcmdMirror.parse(batch);
        assertFalse(mirror.hasParametric(), label + " page is static");

        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(golden), mirror.frameAt(0));
        assertTrue(mismatch <= TEXT_MISMATCH_BUDGET,
                label + " parity: " + mismatch + " mismatched px, budget " + TEXT_MISMATCH_BUDGET);
    }

    private static AdsbdbAircraftData details() {
        return new AdsbdbAircraftData("A319", "A319", "Airbus", "484507", "PH-EXM",
                "United Kingdom", "British Airways");
    }

    private static AdsbdbRouteData route() {
        return new AdsbdbRouteData("KLM123",
                new AdsbdbRouteData.Airline("KLM", "KLM", "KL", "Netherlands"),
                new AdsbdbRouteData.Airport(null, "Amsterdam", "AMS", null, null),
                new AdsbdbRouteData.Airport(null, "New York", "JFK", null, null));
    }

    @Test
    void radarCommandBatchMatchesGoldenFramesAtSweepSamples() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);

        final List<BufferedImage> goldenFrames = service.renderFrames(config);
        final byte[] batch = service.renderCommandBatch(config);
        final AcmdMirror mirror = AcmdMirror.parse(batch);
        assertTrue(mirror.hasParametric(), "radar batch carries the SWEEP");

        final AcmdCommand.Sweep sweep = AcmdParser.parse(batch).commands().stream()
                .filter(AcmdCommand.Sweep.class::isInstance)
                .map(AcmdCommand.Sweep.class::cast)
                .findFirst().orElseThrow();
        assertEquals(180, sweep.speedDegPerSec(), "one whole revolution per 2 s refresh");
        assertEquals(AircraftScreenService.SWEEP_DEG_PER_SEC, sweep.speedDegPerSec());

        final int frameCount = goldenFrames.size();
        assertEquals(AircraftScreenService.radarFrameCount(100), frameCount);

        // Sample 1 (t=0): the ACMD sweep starts at theta 0 = east; golden frame 9 has the
        // frame sweep at 90 deg from north = the same east line.
        assertRadarSample(goldenFrames.get(9), mirror, 0, "t=0");
        // Sample 2 (mid-window): golden frame 26 has the sweep at 243 deg from north
        // (27 steps × 9 deg); the ACMD sweep starts at east (90 deg from north) and
        // runs 180 deg/s, so elapsed = (243 - 90) / 0.18 = 850 ms. Frames 9 and 26 both
        // stay inside tracked aircraft #0's selection window (frames 0-26 of 80 across
        // 3 fixture aircraft), so the static info column of the t=0 batch still
        // matches the golden telemetry page.
        assertRadarSample(goldenFrames.get(26), mirror, 850, "mid-window");
    }

    @Test
    void radarCyclesDetailPagesForTheClosestAircraft() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(route()));

        final List<BufferedImage> goldenFrames = service.renderFrames(config);
        final int frameCount = goldenFrames.size();

        // Enrichment resolves both legs → telemetry + route + registry pages spread
        // evenly over the loop: frames from different page windows differ (the info
        // column), while the white selection box sits on the same closest blip in
        // every page (the only pure-white pixels in the scope half; the sweep line
        // may transiently overdraw a corner as it passes — both engines draw it over
        // the base — so up to 2 of the 12 box pixels can be missing in a given frame).
        assertTrue(FrameParity.mismatchedPixels(
                        FrameParity.rgb565(goldenFrames.get(frameCount / 6)),
                        FrameParity.rgb565(goldenFrames.get(frameCount / 2))) > 0,
                "the info column cycles detail pages");
        // FIXTURE[0] projects to (19,14) → the 4×4 box outline at (17,12)..(20,15).
        final var expectedBox = new java.util.HashSet<Long>();
        for (int x = 17; x <= 20; x++) {
            expectedBox.add(12L << 8 | x);
            expectedBox.add(15L << 8 | x);
        }
        for (int y = 12; y <= 15; y++) {
            expectedBox.add((long) y << 8 | 17);
            expectedBox.add((long) y << 8 | 20);
        }
        for (final int frameIdx : new int[]{frameCount / 6, frameCount / 2}) {
            final var whites = whiteBoxInScope(goldenFrames.get(frameIdx));
            assertTrue(expectedBox.containsAll(whites), "frame " + frameIdx + ": whites outside the box");
            assertTrue(whites.size() >= expectedBox.size() - 2,
                    "frame " + frameIdx + ": the box is on the closest blip");
        }

        // The command-path page rotation slices the slot into equal page windows
        // (unit-tested in the loop tests); the refresh stream's batches carry one
        // page each. 30 s slot over 3 pages → 2000 ms cadence, 5 refreshes per page.
        assertEquals(0, AircraftScreenService.radarPageIndex(0, 2_000, 5, 3));
        assertEquals(1, AircraftScreenService.radarPageIndex(10_000, 2_000, 5, 3));
        assertEquals(2, AircraftScreenService.radarPageIndex(30_000, 2_000, 5, 3),
                "the last page holds to the slot end");
    }

    /**
     * Positions of the pure-white pixels in the scope half (x &lt; 32) of a radar frame:
     * the selection box is the only white thing there (rings/sweep/blips are green
     * shades or altitude colors), so the set identifies where the box sits.
     */
    private static java.util.Set<Long> whiteBoxInScope(final BufferedImage frame) {
        final int[] rgb565 = FrameParity.rgb565(frame);
        final var whites = new java.util.HashSet<Long>();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                if (rgb565[y * 64 + x] == Rgb565.of(Color.WHITE)) {
                    whites.add((long) y << 8 | x);
                }
            }
        }
        return whites;
    }

    @Test
    void radarOffersALiveRefreshStreamOthersDoNot() {
        // Type-less feed aircraft: with a feed type code the column gains a synthesized
        // registry page (2 pages → adapted cadence); this keeps the single-page branch.
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(List.of(
                new NearbyAircraft("484507", "KLM123", "PH-EXM", null, null,
                        8_000, false, 350.0, 90.0, 0, 12.3, 60.0)));
        final CommandScreenService.RefreshStream radar =
                service.renderCommandRefresh(config(AircraftDisplayMode.RADAR));

        assertNotNull(radar, "RADAR re-renders its batch with live data");
        assertEquals(AircraftScreenService.RADAR_REFRESH_MS, radar.refreshMs());
        assertEquals(2_000, radar.refreshMs(), "2 s refresh cadence for a single-page column");
        // The cadence must be a whole parametric loop: the panel re-arms the sweep at
        // every refresh commit, so only whole revolutions per refresh stay seamless.
        assertEquals(1, radar.refreshMs() * AircraftScreenService.SWEEP_DEG_PER_SEC / 360_000,
                "exactly one sweep revolution per refresh");

        assertFalse(AcmdParser.parse(radar.firstBatch()).truncated(), "first batch parses");
        final AcmdMirror next = AcmdMirror.parse(radar.nextBatches().get());
        assertTrue(next.hasParametric(), "a refresh batch is a full re-render, SWEEP included");

        assertNull(service.renderCommandRefresh(config(AircraftDisplayMode.LIST)),
                "LIST has no live value mid-slot");
        assertNull(service.renderCommandRefresh(config(AircraftDisplayMode.CLOSEST)),
                "CLOSEST has no live value mid-slot");
    }

    @Test
    void radarRefreshCadenceAdaptsToTheDisplayTimeAndPageCount() {
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(route()));

        // Enrichment resolves all three pages → the cadence tiles the slot into three
        // equal page windows: exact for 30 s (2000 ms, 5 refreshes per page), adapted
        // to 1666 ms for a 10 s slot so no page is truncated (the 4/4/2 s bug).
        assertEquals(2_000, service.renderCommandRefresh(config(AircraftDisplayMode.RADAR)).refreshMs());
        assertEquals(1_666, service.renderCommandRefresh(
                config(AircraftDisplayMode.RADAR, 10)).refreshMs());

        // The batch's SWEEP speed always does exactly one whole revolution per refresh
        // interval — including the first batch, which must not out- or under-run the
        // refresh that replaces it (a snap at the first republish would be visible).
        final CommandScreenService.RefreshStream tenSecond =
                service.renderCommandRefresh(config(AircraftDisplayMode.RADAR, 10));
        final AcmdCommand.Sweep sweep = AcmdParser.parse(tenSecond.firstBatch()).commands().stream()
                .filter(AcmdCommand.Sweep.class::isInstance)
                .map(AcmdCommand.Sweep.class::cast)
                .findFirst().orElseThrow();
        assertEquals(AircraftScreenService.radarSweepDegPerSec(1_666), sweep.speedDegPerSec());
    }

    @Test
    void radarMarksTheTrackedBlipWithASelectionBox() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);

        final List<AcmdCommand.Rect> rects = AcmdParser.parse(service.renderCommandBatch(config)).commands().stream()
                .filter(AcmdCommand.Rect.class::isInstance)
                .map(AcmdCommand.Rect.class::cast)
                .toList();

        // FIXTURE[0] (the tracked closest): 12.3 NM of a 50 NM scope, bearing 60° →
        // r = 12.3/50 × 14 ≈ 3.44 → x = 16 + round(3.44·sin60) = 19, y = 16 − round(3.44·cos60) = 14.
        assertEquals(1, rects.size(), "exactly one selection box");
        final AcmdCommand.Rect box = rects.getFirst();
        assertEquals(17, box.x());
        assertEquals(12, box.y());
        assertEquals(4, box.w());
        assertEquals(4, box.h());
        assertEquals(Rgb565.of(Color.WHITE), box.color());
    }

    private static void assertRadarSample(final BufferedImage golden, final AcmdMirror mirror,
                                          final long elapsedMs, final String label) {
        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(golden), mirror.frameAt(elapsedMs));
        assertTrue(mismatch <= RADAR_MISMATCH_BUDGET,
                "RADAR parity (" + label + "): " + mismatch + " mismatched px, budget " + RADAR_MISMATCH_BUDGET);
    }

    @Test
    void overflowingLineScrollsItsFullTextInsteadOfTruncating() {
        final NearbyAircraft longType = new NearbyAircraft("484510", "DLH452", "D-ABCD",
                "BOEING 747-8I", null, 36_000, false, 480.0, 90.0, 640, 25.0, 45.0);
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(List.of(longType));

        final AcmdParser.Parsed parsed = AcmdParser.parse(service.renderCommandBatch(config(AircraftDisplayMode.CLOSEST)));
        assertFalse(parsed.truncated());

        final AcmdCommand.Scroll scroll = parsed.commands().stream()
                .filter(AcmdCommand.Scroll.class::isInstance)
                .map(AcmdCommand.Scroll.class::cast)
                .findFirst().orElseThrow();
        assertEquals("BOEING 747-8I D-ABCD", scroll.ascii(), "the full type line scrolls");
        assertEquals(AircraftScreenService.SCROLL_MS_PER_PX, scroll.speedMsPerPx());
    }

    @Test
    void emptyAirspaceBatchRendersNoAircraftPages() {
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(List.of());

        for (final AircraftDisplayMode mode : AircraftDisplayMode.values()) {
            final AcmdParser.Parsed parsed =
                    AcmdParser.parse(service.renderCommandBatch(config(mode)));
            assertFalse(parsed.truncated(), mode + " batch must parse cleanly");
            assertTrue(parsed.commands().stream().anyMatch(c -> c instanceof AcmdCommand.Text),
                    mode + " batch carries the no-aircraft text");
        }
    }

    private static AircraftScreenConfig config(final AircraftDisplayMode mode) {
        return config(mode, 30);
    }

    private static AircraftScreenConfig config(final AircraftDisplayMode mode, final int durationSeconds) {
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, durationSeconds, mode,
                new LatLon(52.3, 4.9), 50, false, AircraftDisplayUnits.AVIATION, 100);
    }
}
