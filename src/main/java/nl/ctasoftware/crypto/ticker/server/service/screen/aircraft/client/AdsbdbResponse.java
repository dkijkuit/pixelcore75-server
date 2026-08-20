package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbdbResponse(AdsbdbResponseBody response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdsbdbResponseBody(
            AdsbdbAircraftData aircraft,
            AdsbdbRouteData flightroute
    ) {
    }
}
