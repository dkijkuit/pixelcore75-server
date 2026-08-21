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

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            new NearbyAircraft("484507", "KLM123", "PH-EXM", "B738",
                    8_000, false, 350.0, 90.0, 0, 12.3, 60.0),
            new NearbyAircraft("484508", "TRA456", null, "A320",
                    null, true, 250.0, 110.0, 0, 3.1, 110.0),
            new NearbyAircraft("484509", "MIL789", null, "F16",
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
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));
        assertTrue(mirror.hasParametric(), "radar batch carries the SWEEP");

        final int frameCount = goldenFrames.size();
        assertEquals(AircraftScreenService.radarFrameCount(100), frameCount);

        // Sample 1 (t=0): the ACMD sweep starts at theta 0 = east; golden frame 9 has the
        // frame sweep at 90 deg from north = the same east line.
        assertRadarSample(goldenFrames.get(9), mirror, 0, "t=0");
        // Sample 2 (mid-slot): golden frame 29 has the sweep at 300 deg from north; the
        // ACMD sweep reads theta = 300 - 90 = 210 deg at elapsedMs with 90*ms/1000 = 210.
        assertRadarSample(goldenFrames.get(29), mirror, 2_334, "mid-slot");
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
                "BOEING 747-8I", 36_000, false, 480.0, 90.0, 640, 25.0, 45.0);
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
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, 30, mode,
                new LatLon(52.3, 4.9), 50, false, AircraftDisplayUnits.AVIATION, 100);
    }
}
