package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Registry/route lookups via adsbdb.com (free, keyless): aircraft details by transponder
 * hex (near-static, cached 24h) and flight route by callsign (cached 1h). Misses and
 * failures return {@link Optional#empty()} and are negatively cached for 15 minutes
 * (variable expiry in {@code ClientCacheConfiguration}) so unknown hexes/callsigns are
 * re-checked periodically without re-fetching every slot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdsbdbAircraftInfoClient implements AircraftInfoClient {

    final RestClient adsbdbRestClient;

    @Override
    @Cacheable("adsbdbAircraft")
    public Optional<AdsbdbAircraftData> getAircraftDetails(final String hex) {
        if (hex == null || hex.isBlank()) {
            return Optional.empty();
        }
        try {
            final AdsbdbResponse body = adsbdbRestClient.get()
                    .uri(b -> b.path("aircraft/{hex}").build(hex.trim().toUpperCase()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AdsbdbResponse.class);
            final AdsbdbAircraftData data = body != null && body.response() != null ? body.response().aircraft() : null;
            return data != null && data.modeS() != null ? Optional.of(data) : Optional.empty();
        } catch (final RuntimeException e) {
            log.debug("adsbdb aircraft lookup failed for {}: {}", hex, rootMessage(e));
            return Optional.empty();
        }
    }

    @Override
    @Cacheable("adsbdbRoute")
    public Optional<AdsbdbRouteData> getRoute(final String callsign) {
        if (callsign == null || callsign.isBlank()) {
            return Optional.empty();
        }
        try {
            final AdsbdbResponse body = adsbdbRestClient.get()
                    .uri(b -> b.path("callsign/{callsign}").build(callsign.trim().toUpperCase()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AdsbdbResponse.class);
            return Optional.ofNullable(body != null && body.response() != null ? body.response().flightroute() : null);
        } catch (final RuntimeException e) {
            log.debug("adsbdb route lookup failed for {}: {}", callsign, rootMessage(e));
            return Optional.empty();
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
