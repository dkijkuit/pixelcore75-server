package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;

import java.util.List;

public interface AircraftClient {

    /**
     * Aircraft within {@code radiusNm} of {@code latLon}, nearest first. When
     * {@code militaryOnly} the global military feed is fetched and filtered by radius
     * server-side.
     */
    List<NearbyAircraft> getAircraft(LatLon latLon, int radiusNm, boolean militaryOnly);

    /**
     * Same query for a caller that renders the result right away (a slot-start render)
     * and must show data fetched at display time. The stale-while-revalidate read
     * above returns the cached snapshot instantly and re-fetches only in the
     * background — the first reader after a quiet period (a once-per-slot screen such
     * as CLOSEST/LIST, whose key nothing polled since the previous rotation) would
     * show the previous fetch's data while the fresh value lands for the next reader.
     * Implementations fetch in the foreground instead (retry envelope and all), keep
     * the cache warmed with the result, and fall back to the last good snapshot when
     * every provider fails. Callers on a tight republish grid (the RADAR live refresh
     * supplier) must keep the non-blocking {@link #getAircraft} instead.
     */
    default List<NearbyAircraft> getAircraftFresh(LatLon latLon, int radiusNm, boolean militaryOnly) {
        return getAircraft(latLon, radiusNm, militaryOnly);
    }
}
