package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbLolResponse(java.util.List<AdsbAircraft> ac) {
}
