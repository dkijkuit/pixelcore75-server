package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdsbLolAircraftClient implements AircraftClient {

    private static final double EARTH_RADIUS_NM = 3440.065;

    /** Fetch attempts before giving up (ingress nodes are intermittently dead). */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 500;

    final RestClient adsbLolRestClient;

    @Override
    @Cacheable("adsbLolNearby")
    public List<NearbyAircraft> getAircraft(final LatLon latLon, final int radiusNm, final boolean militaryOnly) {
        log.info("AdsbLolAircraftClient getAircraft: {} radius={}nm mil={}", latLon, radiusNm, militaryOnly);

        final AdsbLolResponse response = militaryOnly
                ? fetchWithRetry(uriBuilder -> uriBuilder.path("mil").build())
                : fetchWithRetry(uriBuilder -> uriBuilder.path("point/{lat}/{lon}/{radius}")
                        .build(latLon.lat(), latLon.lon(), radiusNm));

        if (response == null || response.ac() == null) {
            return List.of();
        }

        return response.ac().stream()
                .filter(a -> a.lat() != null && a.lon() != null)
                .map(a -> toNearby(a, latLon))
                .filter(a -> a.distanceNm() <= radiusNm)
                .sorted(Comparator.comparingDouble(NearbyAircraft::distanceNm))
                .toList();
    }

    /**
     * Retries transient transport failures (dns round-robin regularly hands out an ingress
     * node that resets the TLS handshake or hangs). Returns null after the last attempt so
     * callers can degrade to an empty aircraft list instead of failing the screen slot.
     */
    private AdsbLolResponse fetchWithRetry(final Function<UriBuilder, URI> uriFunction) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetch(uriFunction);
            } catch (final RuntimeException e) {
                log.warn("adsb.lol fetch attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, rootMessage(e));
                if (attempt < MAX_ATTEMPTS && !sleep(RETRY_BACKOFF_MS * attempt)) {
                    return null;
                }
            }
        }
        log.error("adsb.lol unreachable after {} attempts; rendering no aircraft", MAX_ATTEMPTS);
        return null;
    }

    private static boolean sleep(final long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private AdsbLolResponse fetch(final Function<UriBuilder, URI> uriFunction) {
        return adsbLolRestClient.get()
                .uri(uriFunction)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AdsbLolResponse.class);
    }

    private static NearbyAircraft toNearby(final AdsbAircraft ac, final LatLon origin) {
        final String callsign = ac.flight() != null && !ac.flight().isBlank()
                ? ac.flight().trim()
                : (ac.hex() != null ? ac.hex().toUpperCase() : "?");
        return new NearbyAircraft(
                ac.hex() != null ? ac.hex().toUpperCase() : null,
                callsign,
                blankToNull(ac.r()),
                blankToNull(ac.t()),
                ac.altitudeFt(),
                ac.onGround(),
                ac.gs(),
                ac.track(),
                ac.baroRate(),
                haversineNm(origin.lat(), origin.lon(), ac.lat(), ac.lon()),
                initialBearingDeg(origin.lat(), origin.lon(), ac.lat(), ac.lon())
        );
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Great-circle distance between two points, in nautical miles. */
    private static double haversineNm(final double lat1, final double lon1, final double lat2, final double lon2) {
        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_NM * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees clockwise from north. */
    private static double initialBearingDeg(final double lat1, final double lon1, final double lat2, final double lon2) {
        final double phi1 = Math.toRadians(lat1);
        final double phi2 = Math.toRadians(lat2);
        final double deltaLon = Math.toRadians(lon2 - lon1);
        final double y = Math.sin(deltaLon) * Math.cos(phi2);
        final double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
}
