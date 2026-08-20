package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Flight route from adsbdb.com (GET /v0/callsign/{callsign}): airline plus origin and
 * destination airports. Semi-static (changes per flight of the callsign).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbdbRouteData(
        String callsign,
        Airline airline,
        Airport origin,
        Airport destination
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Airline(String name, String icao, String iata, String country) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Airport(
            String name,
            String municipality,
            @JsonProperty("iata_code") String iataCode,
            @JsonProperty("icao_code") String icaoCode,
            @JsonProperty("country_name") String countryName
    ) {
    }
}
