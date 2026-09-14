package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayMode;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayUnits;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
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
import java.io.File;
import java.util.Arrays;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the NEARBY_AIRCRAFT command screens (plan §6): the ACMD batches'
 * {@link AcmdMirror} frames in panel RGB565, pinned per (mocked) aircraft list — no
 * docker/network needed, the adsb/adsbdb clients are interfaces and get fixtures,
 * enrichment legs answer empty unless stubbed.
 */
class AircraftCommandParityTests {

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
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(FIXTURE);
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean())).thenReturn(FIXTURE);
        when(infoClient.getAircraftDetails(any())).thenReturn(Optional.empty());
        when(infoClient.getRoute(any())).thenReturn(Optional.empty());
        service = new AircraftScreenService(ledBoard, cgPixel, aircraftClient, infoClient);
    }

    @Test
    void listMatchesGolden() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.LIST);
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));
        assertFalse(mirror.hasParametric(), "LIST is static");
        CommandGolden.assertGolden("aircraft-list", mirror.frameAt(0));
    }

    @Test
    void closestIdentityPageMatchesGolden() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);

        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));
        assertFalse(mirror.hasParametric(), "identity page is static");

        CommandGolden.assertGolden("aircraft-closest", mirror.frameAt(0));
    }

    @Test
    void closestCommandBatchesCycleRegistryAndRoutePages() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(route()));

        final CommandScreenService.BatchStream stream = service.renderCommandBatches(config);

        assertEquals(3, stream.batches().size(), "identity + registry + route pages");
        assertEquals(10_000, stream.pageDwellMs(), "30 s slot spread evenly over 3 pages");

        CommandGolden.assertGolden("aircraft-registry", CommandGolden.frameAt(stream.batches().get(1), 0));
        CommandGolden.assertGolden("aircraft-route", CommandGolden.frameAt(stream.batches().get(2), 0));
    }

    @Test
    void closestWithoutEnrichmentRendersASingleIdentityBatch() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.CLOSEST);
        // Feed synthesis turns a feed type code into a registry page, so "no
        // enrichment at all" needs a type-less feed aircraft too.
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(List.of(
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
        // 'á' outside ASCII 32..126").
        final CommandScreenService.BatchStream stream = service.renderCommandBatches(config);

        assertEquals(3, stream.batches().size(), "route page still builds");
        final List<String> texts = AcmdParser.parse(stream.batches().get(2)).commands().stream()
                .filter(AcmdCommand.Text.class::isInstance)
                .map(AcmdCommand.Text.class::cast)
                .map(AcmdCommand.Text::ascii)
                .toList();
        assertTrue(texts.contains("GRU -> BOG"), () -> texts.toString());
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
    void radarMatchesGoldenAtSweepSamples() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);

        final byte[] batch = service.renderCommandBatch(config);
        final AcmdMirror mirror = AcmdMirror.parse(batch);
        assertTrue(mirror.hasParametric(), "radar batch carries the SWEEP");

        final AcmdCommand.Sweep sweep = AcmdParser.parse(batch).commands().stream()
                .filter(AcmdCommand.Sweep.class::isInstance)
                .map(AcmdCommand.Sweep.class::cast)
                .findFirst().orElseThrow();
        assertEquals(180, sweep.speedDegPerSec(), "one whole revolution per 2 s refresh");
        assertEquals(AircraftScreenService.SWEEP_DEG_PER_SEC, sweep.speedDegPerSec());

        // The sweep line rotates: the t=0 frame (sweep at east) and the mid-window frame
        // (850 ms in) must both match their goldens — and each other in digest only.
        CommandGolden.assertGolden("aircraft-radar-t0", mirror.frameAt(0));
        CommandGolden.assertGolden("aircraft-radar-t850", mirror.frameAt(850));
    }

    @Test
    void radarCyclesDetailPagesForTheClosestAircraft() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);
        when(infoClient.getAircraftDetails("484507")).thenReturn(Optional.of(details()));
        when(infoClient.getRoute("KLM123")).thenReturn(Optional.of(route()));

        // Enrichment resolves both legs → telemetry + route + registry pages spread
        // evenly over the slot's page windows: the page a refresh batch carries comes
        // from the render lead (render elapsed + one refresh) on the page grid.
        final List<AircraftScreenService.RadarInfoPage> pages = List.of(
                AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.RadarInfoPage.REGISTRY);
        assertEquals(AircraftScreenService.RadarInfoPage.TELEMETRY,
                AircraftScreenService.radarPageForRenderElapsed(0, pages, 2_000, 5));
        assertEquals(AircraftScreenService.RadarInfoPage.ROUTE,
                AircraftScreenService.radarPageForRenderElapsed(10_000, pages, 2_000, 5),
                "10 s in = page window 2");
        assertEquals(AircraftScreenService.RadarInfoPage.REGISTRY,
                AircraftScreenService.radarPageForRenderElapsed(25_000, pages, 2_000, 5),
                "the last page holds to the slot end");

        // The command-path page rotation slices the slot into equal page windows:
        // 30 s slot over 3 pages → 2000 ms cadence, 5 refreshes per page.
        assertEquals(0, AircraftScreenService.radarPageIndex(0, 2_000, 5, 3));
        assertEquals(1, AircraftScreenService.radarPageIndex(10_000, 2_000, 5, 3));
        assertEquals(2, AircraftScreenService.radarPageIndex(30_000, 2_000, 5, 3),
                "the last page holds to the slot end");
    }

    @Test
    void radarOffersALiveRefreshStreamOthersDoNot() {
        // Type-less feed aircraft: with a feed type code the column gains a synthesized
        // registry page (2 pages → adapted cadence); this keeps the single-page branch.
        // The eager first batch renders via the fresh read, the refresh supplier via
        // the SWR read — both stubbed.
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(List.of(
                new NearbyAircraft("484507", "KLM123", "PH-EXM", null, null,
                        8_000, false, 350.0, 90.0, 0, 12.3, 60.0)));
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
    void radarMarksTheTrackedBlipInWhiteInsteadOfABox() {
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);

        final List<AcmdCommand> commands = AcmdParser.parse(service.renderCommandBatch(config)).commands();

        // No selection box, ring, or tail: the tracked marker is a plain 3×3 white
        // blip — unique through size + color alone.
        assertTrue(commands.stream().noneMatch(AcmdCommand.Rect.class::isInstance), "no selection box");
        assertTrue(commands.stream().filter(AcmdCommand.Circ.class::isInstance)
                .map(AcmdCommand.Circ.class::cast)
                .noneMatch(c -> c.color() == Rgb565.of(Color.WHITE)), "no selection ring");
        assertEquals(0, commands.stream().filter(AcmdCommand.Line.class::isInstance).count(), "no vector lines");

        // FIXTURE[0] (the tracked closest) blip (19,14) → white 3×3 FILL at (18,13).
        // The others keep their 2×2 altitude colors: FIXTURE[1] ground → yellow,
        // FIXTURE[2] 20–30k ft → magenta; no green fill remains (that band was
        // FIXTURE[0]'s, replaced by the white marker).
        final List<AcmdCommand.Fill> fills = commands.stream()
                .filter(AcmdCommand.Fill.class::isInstance)
                .map(AcmdCommand.Fill.class::cast)
                .toList();
        assertTrue(fills.stream().anyMatch(f -> f.x() == 18 && f.y() == 13
                        && f.w() == AircraftScreenService.TRACKED_BLIP_SIZE
                        && f.h() == AircraftScreenService.TRACKED_BLIP_SIZE
                        && f.color() == Rgb565.of(Color.WHITE)),
                () -> fills.toString());
        assertTrue(fills.stream().anyMatch(f -> f.color() == Rgb565.of(Color.YELLOW)));
        assertTrue(fills.stream().anyMatch(f -> f.color() == Rgb565.of(Color.MAGENTA)));
        assertTrue(fills.stream().noneMatch(f -> f.color() == Rgb565.of(Color.GREEN)));
    }

    @Test
    void radarScrollsOverflowingInfoColumnLinesAlongsideTheSweep() {
        // 7-char callsign = 35 px > the 30 px column → marquee instead of gibberish
        // truncation, on multi-parametric ACMD (the SWEEP keeps ticking concurrently —
        // the reason the engine grew beyond one parametric per batch).
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(List.of(
                new NearbyAircraft("484507", "DLH8ANA", null, null, null,
                        8_000, false, 350.0, 90.0, 0, 12.3, 60.0)));
        final AircraftScreenConfig config = config(AircraftDisplayMode.RADAR);

        final AcmdParser.Parsed parsed = AcmdParser.parse(service.renderCommandBatch(config));
        assertFalse(parsed.truncated());
        final AcmdCommand.Scroll scroll = parsed.commands().stream()
                .filter(AcmdCommand.Scroll.class::isInstance)
                .map(AcmdCommand.Scroll.class::cast)
                .findFirst().orElseThrow();
        assertEquals("DLH8ANA", scroll.ascii(), "the full callsign scrolls, untruncated");
        assertEquals(AircraftScreenService.INFO_X, scroll.x());
        assertEquals(AircraftScreenService.INFO_COLUMN_WIDTH, scroll.w());
        assertEquals(AircraftScreenService.INFO_SCROLL_REGION_HEIGHT, scroll.h());
        assertEquals(AircraftScreenService.SCROLL_MS_PER_PX, scroll.speedMsPerPx());
        assertTrue(parsed.commands().stream().anyMatch(AcmdCommand.Sweep.class::isInstance),
                "the SWEEP coexists with the marquee");

        // The mirror renders the callsign bouncing across the column while the sweep runs
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config));
        assertEquals(2, mirror.parametricCount(), "sweep + scroll armed");
        // "DLH8ANA" = 35 px over the 30 px column -> travel 5 px: the pass stretches to
        // 12 px-units × 120 ms = 1440 ms (short-overflow glide) after a 960 ms head
        // hold; t=1500 is 540 ms into the pass -> pen 1 px left of the head extreme
        assertFalse(Arrays.equals(mirror.frameAt(0), mirror.frameAt(1500)),
                "the bouncing line moves");
    }

    @Test
    void overflowingLineScrollsItsFullTextInsteadOfTruncating() {
        final NearbyAircraft longType = new NearbyAircraft("484510", "DLH452", "D-ABCD",
                "BOEING 747-8I", null, 36_000, false, 480.0, 90.0, 640, 25.0, 45.0);
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(List.of(longType));

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
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean())).thenReturn(List.of());

        for (final AircraftDisplayMode mode : AircraftDisplayMode.values()) {
            final AcmdParser.Parsed parsed =
                    AcmdParser.parse(service.renderCommandBatch(config(mode)));
            assertFalse(parsed.truncated(), mode + " batch must parse cleanly");
            assertTrue(parsed.commands().stream().anyMatch(c -> c instanceof AcmdCommand.Text),
                    mode + " batch carries the no-aircraft text");
        }
    }

    @Test
    void slotStartRendersFetchFreshDataWhileOnlyTheRefreshSupplierReadsTheCache() {
        // The "closest screen shows older data than the radar screen" regression: a
        // slot-start render must use the foreground fetch (its result is displayed),
        // and only the RADAR refresh supplier may take the instant SWR read.
        final CommandScreenService.RefreshStream radar =
                service.renderCommandRefresh(config(AircraftDisplayMode.RADAR));
        radar.nextBatches().get();
        verify(aircraftClient, times(1)).getAircraftFresh(any(), anyInt(), anyBoolean());
        verify(aircraftClient, times(1)).getAircraft(any(), anyInt(), anyBoolean());

        service.renderCommandBatches(config(AircraftDisplayMode.CLOSEST));
        verify(aircraftClient, times(2)).getAircraftFresh(any(), anyInt(), anyBoolean());
        verify(aircraftClient, times(1)).getAircraft(any(), anyInt(), anyBoolean());
    }

    private static AircraftScreenConfig config(final AircraftDisplayMode mode) {
        return config(mode, 30);
    }

    private static AircraftScreenConfig config(final AircraftDisplayMode mode, final int durationSeconds) {
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, durationSeconds, mode,
                new LatLon(52.3, 4.9), 50, false, AircraftDisplayUnits.AVIATION, 100);
    }
}
