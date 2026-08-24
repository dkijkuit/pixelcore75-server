package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * readsb-style v2 envelope. The providers disagree on the list key: adsb.lol names it
 * {@code ac}, adsb.fi {@code aircraft} — the alias accepts both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdsbLolResponse(@JsonAlias("aircraft") java.util.List<AdsbAircraft> ac) {
}
