package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Live positions from api.adsb.lol, served through a stale-while-revalidate
 * {@link LoadingCache} (wired in {@code ClientCacheConfiguration}). The first
 * {@code get()} for an area blocks for the full retry envelope; afterwards reads
 * return the cached list instantly while {@code refreshAfterWrite} re-fetches on a
 * background virtual-thread executor, so a render slot never blocks on a dead
 * ingress node. Callers keep the old contract: total failure degrades to an empty
 * aircraft list instead of failing the screen slot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdsbLolAircraftClient implements AircraftClient {

    /** Cache key: every input of the adsb.lol query (record equality). */
    public record AdsbLolRequest(LatLon latLon, int radiusNm, boolean militaryOnly) {
    }

    final LoadingCache<AdsbLolRequest, List<NearbyAircraft>> adsbLolNearbyCache;

    @Override
    public List<NearbyAircraft> getAircraft(final LatLon latLon, final int radiusNm, final boolean militaryOnly) {
        log.info("AdsbLolAircraftClient getAircraft: {} radius={}nm mil={}", latLon, radiusNm, militaryOnly);
        try {
            return adsbLolNearbyCache.get(new AdsbLolRequest(latLon, radiusNm, militaryOnly));
        } catch (final RuntimeException e) {
            // Caffeine wraps loader failures in CompletionException; the loader itself
            // never throws, this is defense in depth for the empty-list contract.
            log.warn("adsb.lol nearby lookup failed: {}", rootMessage(e));
            return List.of();
        }
    }

    /**
     * Loader for the stale-while-revalidate cache. {@link #load} is the original
     * fetch-with-retry body; Caffeine runs {@code refreshAfterWrite} reloads through
     * {@code asyncReload} on the cache's executor (never the reading thread), so a
     * reload failure simply keeps the previous value until the entry expires.
     */
    public static final class AdsbLolCacheLoader implements CacheLoader<AdsbLolRequest, List<NearbyAircraft>> {

        private static final double EARTH_RADIUS_NM = 3440.065;

        /** Fetch attempts before giving up (ingress nodes are intermittently dead). */
        private static final int MAX_ATTEMPTS = 3;
        private static final long RETRY_BACKOFF_MS = 500;

        private final RestClient adsbLolRestClient;

        public AdsbLolCacheLoader(final RestClient adsbLolRestClient) {
            this.adsbLolRestClient = adsbLolRestClient;
        }

        @Override
        public List<NearbyAircraft> load(final AdsbLolRequest request) {
            final AdsbLolResponse response = request.militaryOnly()
                    ? fetchWithRetry(uriBuilder -> uriBuilder.path("mil").build())
                    : fetchWithRetry(uriBuilder -> uriBuilder.path("point/{lat}/{lon}/{radius}")
                            .build(request.latLon().lat(), request.latLon().lon(), request.radiusNm()));

            if (response == null || response.ac() == null) {
                return List.of();
            }

            return response.ac().stream()
                    .filter(a -> a.lat() != null && a.lon() != null)
                    .map(a -> toNearby(a, request.latLon()))
                    .filter(a -> a.distanceNm() <= request.radiusNm())
                    .sorted(Comparator.comparingDouble(NearbyAircraft::distanceNm))
                    .toList();
        }

        /**
         * Retries transient transport failures (dns round-robin regularly hands out an ingress
         * node that resets the TLS handshake or hangs). Returns null after the last attempt so
         * the load degrades to an empty aircraft list instead of failing the screen slot.
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

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
