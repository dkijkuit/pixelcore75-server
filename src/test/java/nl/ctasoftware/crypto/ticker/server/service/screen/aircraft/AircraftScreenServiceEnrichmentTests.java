package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayMode;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayUnits;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService.FrameStream;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbAircraftData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbRouteData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftEnrichment;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftInfoClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.NearbyAircraft;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * enrichClosest runs its two adsbdb legs concurrently and merges the results; a
 * failing leg degrades to an empty enrichment leg instead of failing the render.
 * Observed through the CLOSEST frame stream: identity page + registry page (details
 * leg) + route page (route leg), so pages = 1 + (details present) + (route present),
 * padded to the protocol minimum of 2 frames.
 */
class AircraftScreenServiceEnrichmentTests {

    private static final String HEX = "A1F2C3";
    private static final String CALLSIGN = "BAW1";

    private final PaintToolsService paintTools = mock(PaintToolsService.class);
    private final AircraftClient aircraftClient = mock(AircraftClient.class);
    private final AircraftInfoClient infoClient = mock(AircraftInfoClient.class);

    private AircraftScreenService service;

    @BeforeEach
    void setUp() {
        when(paintTools.newImage()).thenAnswer(inv -> new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        service = new AircraftScreenService(paintTools, null, null, aircraftClient, infoClient);
    }

    @Test
    void bothLegsRunConcurrentlyAndMerge() {
        // Each leg waits at the barrier until the other one started: a sequential
        // implementation would time out, empty both legs and fail the page count.
        final CyclicBarrier bothStarted = new CyclicBarrier(2);
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenAnswer(inv -> {
            bothStarted.await(5, TimeUnit.SECONDS);
            return Optional.of(details());
        });
        when(infoClient.getRoute(CALLSIGN)).thenAnswer(inv -> {
            bothStarted.await(5, TimeUnit.SECONDS);
            return Optional.of(route());
        });

        final FrameStream stream = service.renderFrameStream(config());

        assertEquals(3, stream.frames().size()); // identity + registry + route pages
        verify(infoClient).getAircraftDetails(HEX);
        verify(infoClient).getRoute(CALLSIGN);
    }

    @Test
    void failingDetailsLegFallsBackToThePositionalFeedType() {
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenThrow(new RuntimeException("adsbdb down"));
        when(infoClient.getRoute(CALLSIGN)).thenReturn(Optional.of(route()));

        final FrameStream stream = assertDoesNotThrow(() -> service.renderFrameStream(config()));

        // identity + registry (type synthesized from the positional feed's "A319") + route
        assertEquals(3, stream.frames().size());
        verify(infoClient).getRoute(CALLSIGN);
    }

    @Test
    void failingRouteLegDegradesToRegistryOnlyPage() {
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenReturn(Optional.of(details()));
        when(infoClient.getRoute(CALLSIGN)).thenThrow(new RuntimeException("adsbdb down"));

        final FrameStream stream = assertDoesNotThrow(() -> service.renderFrameStream(config()));

        assertEquals(2, stream.frames().size()); // identity + registry
        verify(infoClient).getAircraftDetails(HEX);
    }

    @Test
    void bothLegsEmptyStillShowTheFeedTypeRegistryPage() {
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenReturn(Optional.empty());
        when(infoClient.getRoute(CALLSIGN)).thenReturn(Optional.empty());

        final FrameStream stream = service.renderFrameStream(config());

        // adsbdb knows nothing, but the feed's type code still yields a registry page
        assertEquals(2, stream.frames().size()); // identity + registry (no route to show)
        // PaintTools is mocked (blank images), so assert on the drawn registry type
        // line instead of pixels: y=27 is registryPage's type row, and the identity
        // page's own type line is "A319 G-TEST" — only the registry line is bare "A319".
        verify(paintTools).drawText(any(), any(), eq("A319"), eq(0), eq(27), any());
    }

    @Test
    void displayTypePrefersTheFullNameWhenItFitsElseTheIcaoCode() {
        final AircraftEnrichment ryr = AircraftEnrichment.of(
                new AdsbdbAircraftData("737NG 8AS/W", "B738", "Boeing", HEX, "EI-DPV",
                        "Ireland", "Ryanair"), null, null);

        assertEquals("737NG 8AS/W", ryr.displayType(12)); // full name fits the CLOSEST column
        assertEquals("B738", ryr.displayType(6));         // radar column: ICAO code instead of "737NG "
    }

    @Test
    void displayTypeFallsBackToTheFullNameForTruncationWhenNothingFits() {
        final AircraftEnrichment falcon = AircraftEnrichment.of(
                new AdsbdbAircraftData("Falcon 2000EX", "F2TH", "Dassault", HEX, null, null, null),
                null, null);

        assertEquals("Falcon 2000EX", falcon.displayType(3)); // caller truncates
    }

    @Test
    void displayTypeIsNullWhenNothingIsKnown() {
        assertNull(AircraftEnrichment.of(
                new AdsbdbAircraftData(null, null, null, HEX, null, null, null), null, null).displayType(6));
    }

    @Test
    void detailsMissBuildsRegistryFromThePositionalFeedAndTheAirline() {
        // RYR93TP shape: adsbdb 404s the hex, but the feed carries type/desc and the
        // route leg resolves the airline.
        final AircraftEnrichment synthesized = AircraftEnrichment.of(null,
                new AdsbdbRouteData("RYR93TP",
                        new AdsbdbRouteData.Airline("Ryanair", "RYR", "FR", "Ireland"),
                        airport("FAO", "Faro"), airport("LBA", "Leeds")),
                new NearbyAircraft("4CAF2B", "RYR93TP", "EI-ILR", "B38M", "BOEING 737 MAX 8",
                        38_000, false, 466.0, 25.0, 0, 100.0, 180.0));

        assertEquals("Ryanair", synthesized.owner());          // airline-name fallback
        assertEquals("BOEING 737 MAX 8", synthesized.typeName()); // feed desc
        assertEquals("B38M", synthesized.icaoType());            // feed type code
        assertEquals("B38M", synthesized.displayType(6));        // radar column
        assertTrue(synthesized.hasRegistry());
        assertTrue(synthesized.hasRoute());
    }

    @Test
    void synthesisFillsOnlyTheMissingDetailsFields() {
        final AircraftEnrichment merged = AircraftEnrichment.of(
                new AdsbdbAircraftData(null, null, "Boeing", HEX, "EI-DPV", "Ireland", "Ryanair"),
                null,
                new NearbyAircraft(HEX, CALLSIGN, "EI-DPV", "B38M", "BOEING 737 MAX 8",
                        38_000, false, 466.0, 25.0, 0, 100.0, 180.0));

        assertEquals("Ryanair", merged.owner());                 // details win over airline
        assertEquals("BOEING 737 MAX 8", merged.typeName());     // positional fills the gap
        assertEquals("B38M", merged.icaoType());
        assertEquals("Boeing", merged.manufacturer());           // details field kept
    }

    private void givenAircraft() {
        when(aircraftClient.getAircraftFresh(any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new NearbyAircraft(HEX, CALLSIGN, "G-TEST", "A319", null,
                        35_000, false, 450.0, 90.0, 0, 12.5, 45.0)));
    }

    private static AircraftScreenConfig config() {
        return new AircraftScreenConfig(ScreenType.NEARBY_AIRCRAFT, 30, AircraftDisplayMode.CLOSEST,
                new LatLon(52.3, 4.9), 50, false, AircraftDisplayUnits.AVIATION, 100);
    }

    private static AdsbdbAircraftData details() {
        return new AdsbdbAircraftData("A319", "A319", "Airbus", HEX, "G-TEST",
                "United Kingdom", "British Airways");
    }

    private static AdsbdbRouteData route() {
        return new AdsbdbRouteData(CALLSIGN,
                new AdsbdbRouteData.Airline("British Airways", "BAW", "BA", "United Kingdom"),
                airport("AMS", "Amsterdam"), airport("JFK", "New York"));
    }

    private static AdsbdbRouteData.Airport airport(final String iata, final String city) {
        return new AdsbdbRouteData.Airport(null, city, iata, null, null);
    }
}
