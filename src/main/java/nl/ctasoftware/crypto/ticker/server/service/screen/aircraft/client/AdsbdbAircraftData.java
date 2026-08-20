package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Aircraft details from adsbdb.com (GET /v0/aircraft/{hex}). Static registry data,
 * heavily cacheable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbdbAircraftData(
        String type,
        @JsonProperty("icao_type") String icaoType,
        String manufacturer,
        @JsonProperty("mode_s") String modeS,
        String registration,
        @JsonProperty("registered_owner_country_name") String registeredOwnerCountryName,
        @JsonProperty("registered_owner") String registeredOwner
) {
}
