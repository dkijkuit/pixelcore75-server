package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Optional semantics of the adsbdb client (constructed directly, so the @Cacheable
 * proxy is bypassed): present on success, empty on 404/transport failure/blank input.
 */
class AdsbdbAircraftInfoClientTests {

    private MockRestServiceServer server;
    private AdsbdbAircraftInfoClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.adsbdb.com/v0/");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AdsbdbAircraftInfoClient(builder.build());
    }

    @Test
    void aircraftDetailsPresentOnSuccess() {
        server.expect(requestTo("https://api.adsbdb.com/v0/aircraft/A1F2C3"))
                .andRespond(withSuccess("""
                        {"response":{"aircraft":{"mode_s":"A1F2C3","registration":"G-TEST",
                        "registered_owner":"British Airways","registered_owner_country_name":"United Kingdom"}}}
                        """, MediaType.APPLICATION_JSON));

        final Optional<AdsbdbAircraftData> details = client.getAircraftDetails(" a1f2c3 ");

        assertTrue(details.isPresent());
        assertEquals("A1F2C3", details.orElseThrow().modeS());
        assertEquals("British Airways", details.orElseThrow().registeredOwner());
        server.verify();
    }

    @Test
    void aircraftDetailsEmptyOnNotFound() {
        server.expect(requestTo("https://api.adsbdb.com/v0/aircraft/DEADBEEF"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.getAircraftDetails("DEADBEEF").isEmpty());
        server.verify();
    }

    @Test
    void aircraftDetailsEmptyOnBlankHexWithoutRequest() {
        assertTrue(client.getAircraftDetails(null).isEmpty());
        assertTrue(client.getAircraftDetails("  ").isEmpty());
        server.verify(); // no HTTP exchange was attempted
    }

    @Test
    void routePresentOnSuccess() {
        server.expect(requestTo("https://api.adsbdb.com/v0/callsign/BAW1"))
                .andRespond(withSuccess("""
                        {"response":{"flightroute":{"callsign":"BAW1",
                        "airline":{"name":"British Airways"},
                        "origin":{"iata_code":"AMS","icao_code":"EHAM"},
                        "destination":{"iata_code":"JFK","icao_code":"KJFK"}}}}
                        """, MediaType.APPLICATION_JSON));

        final Optional<AdsbdbRouteData> route = client.getRoute("baw1");

        assertTrue(route.isPresent());
        assertEquals("AMS", route.orElseThrow().origin().iataCode());
        assertEquals("JFK", route.orElseThrow().destination().iataCode());
        server.verify();
    }

    @Test
    void routeEmptyOnNotFound() {
        server.expect(requestTo("https://api.adsbdb.com/v0/callsign/XXX9999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.getRoute("XXX9999").isEmpty());
        server.verify();
    }

    @Test
    void routeEmptyOnBlankCallsignWithoutRequest() {
        assertTrue(client.getRoute(null).isEmpty());
        assertTrue(client.getRoute("  ").isEmpty());
        server.verify();
    }
}
