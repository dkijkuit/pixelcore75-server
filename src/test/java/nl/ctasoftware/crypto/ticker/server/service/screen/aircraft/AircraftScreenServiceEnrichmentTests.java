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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
    void failingDetailsLegDegradesToRouteOnlyPage() {
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenThrow(new RuntimeException("adsbdb down"));
        when(infoClient.getRoute(CALLSIGN)).thenReturn(Optional.of(route()));

        final FrameStream stream = assertDoesNotThrow(() -> service.renderFrameStream(config()));

        assertEquals(2, stream.frames().size()); // identity + route
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
    void bothLegsEmptyYieldIdentityPageOnly() {
        givenAircraft();
        when(infoClient.getAircraftDetails(HEX)).thenReturn(Optional.empty());
        when(infoClient.getRoute(CALLSIGN)).thenReturn(Optional.empty());

        final FrameStream stream = service.renderFrameStream(config());

        assertEquals(2, stream.frames().size()); // single page duplicated to the 2-frame protocol minimum
    }

    private void givenAircraft() {
        when(aircraftClient.getAircraft(any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new NearbyAircraft(HEX, CALLSIGN, "G-TEST", "A319",
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
