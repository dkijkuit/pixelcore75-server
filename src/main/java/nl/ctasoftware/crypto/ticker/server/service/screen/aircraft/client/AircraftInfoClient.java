package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

/**
 * Enriches aircraft with registry details (owner/country/type) and flight route.
 * Lookups return null when unknown or unreachable — callers degrade gracefully.
 */
public interface AircraftInfoClient {

    AdsbdbAircraftData getAircraftDetails(String hex);

    AdsbdbRouteData getRoute(String callsign);
}
