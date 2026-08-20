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
}
