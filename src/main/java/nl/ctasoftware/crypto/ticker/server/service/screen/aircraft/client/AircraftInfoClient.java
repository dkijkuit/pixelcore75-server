package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import java.util.Optional;

/**
 * Enriches aircraft with registry details (owner/country/type) and flight route.
 * Lookups return {@link Optional#empty()} when unknown or unreachable; empty results
 * are negatively cached for a short TTL — callers degrade gracefully.
 */
public interface AircraftInfoClient {

    Optional<AdsbdbAircraftData> getAircraftDetails(String hex);

    Optional<AdsbdbRouteData> getRoute(String callsign);
}
