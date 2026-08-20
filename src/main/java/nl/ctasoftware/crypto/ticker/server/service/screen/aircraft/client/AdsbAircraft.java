package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One aircraft as returned by the adsb.lol v2 endpoints. Fields are wrappers because the
 * API omits/nulls any value it does not have. {@code alt_baro} is polymorphic: a number,
 * or the string {@code "ground"} when the aircraft is on the ground.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbAircraft(
        String hex,
        String flight,
        String r,
        String t,
        @JsonProperty("alt_baro") JsonNode altBaro,
        Double gs,
        Double track,
        @JsonProperty("baro_rate") Integer baroRate,
        Double lat,
        Double lon
) {

    public boolean onGround() {
        return altBaro != null && altBaro.isTextual() && "ground".equals(altBaro.asText());
    }

    /** Barometric altitude in feet, or null when unknown or on the ground. */
    public Integer altitudeFt() {
        if (altBaro == null || !altBaro.isNumber()) {
            return null;
        }
        return altBaro.asInt();
    }
}
