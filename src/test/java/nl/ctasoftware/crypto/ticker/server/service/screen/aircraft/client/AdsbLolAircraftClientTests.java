package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AdsbLolCacheLoader;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AdsbLolRequest;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AircraftApiProvider;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Provider rotation of the cache loader (constructed directly, so no cache or Spring
 * context is involved): a rate-limited primary fails over to the secondary within one
 * load, the loader sticks to the provider that last answered, and total failure
 * degrades to an empty list instead of throwing.
 */
class AdsbLolAircraftClientTests {

    private static final String LOL_POINT_URI = "https://lol.test/v2/point/52.0/4.0/25";
    private static final String FI_POINT_URI = "https://fi.test/v2/lat/52.0/lon/4.0/dist/25";

    private MockRestServiceServer lolServer;
    private MockRestServiceServer fiServer;
    private AdsbLolCacheLoader loader;

    @BeforeEach
    void setUp() {
        final RestClient.Builder lolBuilder = RestClient.builder().baseUrl("https://lol.test/v2/");
        lolServer = MockRestServiceServer.bindTo(lolBuilder).build();
        final RestClient.Builder fiBuilder = RestClient.builder().baseUrl("https://fi.test/v2/");
        fiServer = MockRestServiceServer.bindTo(fiBuilder).build();
        loader = new AdsbLolCacheLoader(List.of(
                new AircraftApiProvider("adsb.lol", lolBuilder.build()),
                new AircraftApiProvider("adsb.fi", fiBuilder.build(), AircraftApiProvider.PointPathStyle.LAT_LON_DIST)));
    }

    private static AdsbLolRequest pointRequest() {
        return new AdsbLolRequest(new LatLon(52.0, 4.0), 25, false);
    }

    @Test
    void failsOverToSecondProviderOnRateLimit() {
        lolServer.expect(requestTo(LOL_POINT_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("<html>429</html>"));
        // adsb.fi: lat/lon/dist path shape and "aircraft" list key instead of "ac"
        fiServer.expect(requestTo(FI_POINT_URI))
                .andRespond(withSuccess("""
                        {"now":1,"aircraft":[{"hex":"484f7a","flight":"KLM123  ","r":"PH-EXM","t":"B738",
                        "alt_baro":12000,"gs":250.0,"track":90.0,"baro_rate":0,
                        "lat":52.0,"lon":4.01,"nic":8,"seen_pos":0.5},
                        {"hex":"3c66b2","alt_baro":"ground","lat":52.02,"lon":4.03}]}
                        """, MediaType.APPLICATION_JSON));

        final List<NearbyAircraft> aircraft = loader.load(pointRequest());

        assertEquals(2, aircraft.size());
        final NearbyAircraft first = aircraft.get(0);
        assertEquals("484F7A", first.hex());
        assertEquals("KLM123", first.callsign());
        assertEquals("PH-EXM", first.registration());
        assertEquals("B738", first.type());
        assertEquals(12000, first.altitudeFt());
        assertTrue(first.distanceNm() < 1.0);
        assertTrue(aircraft.get(1).onGround());
        lolServer.verify();
        fiServer.verify();
    }

    @Test
    void sticksToProviderThatLastAnswered() {
        lolServer.expect(requestTo(LOL_POINT_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        fiServer.expect(times(2), requestTo(FI_POINT_URI))
                .andRespond(withSuccess("{\"aircraft\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), loader.load(pointRequest()));
        // second load must start at the failover provider: adsb.lol sees no further request
        assertEquals(List.of(), loader.load(pointRequest()));

        lolServer.verify();
        fiServer.verify();
    }

    @Test
    void emptyListWhenAllProvidersFail() {
        // attempts rotate adsb.lol → adsb.fi → adsb.lol
        lolServer.expect(times(2), requestTo(LOL_POINT_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        fiServer.expect(requestTo(FI_POINT_URI))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertEquals(List.of(), loader.load(pointRequest()));

        lolServer.verify();
        fiServer.verify();
    }

    @Test
    void militaryFeedUsesMilPathOnPrimaryProvider() {
        lolServer.expect(requestTo("https://lol.test/v2/mil"))
                .andRespond(withSuccess("{\"ac\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), loader.load(new AdsbLolRequest(new LatLon(52.0, 4.0), 25, true)));

        lolServer.verify();
    }
}
