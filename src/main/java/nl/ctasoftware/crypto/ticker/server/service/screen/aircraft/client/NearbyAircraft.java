package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

/**
 * An aircraft near a reference point, with distance and bearing already resolved.
 *
 * @param hex            transponder hex code (lookup key for registry enrichment)
 * @param callsign       trimmed flight callsign, falls back to the hex code
 * @param registration   e.g. PH-EXM, may be null
 * @param type           aircraft type code e.g. B738, may be null
 * @param typeDesc       manufacturer+model string e.g. "BOEING 737 MAX 8" (adsb.fi
 *                       {@code desc}; null on adsb.lol), may be null
 * @param altitudeFt     barometric altitude in feet, null when unknown or on the ground
 * @param onGround       true when reporting "ground" as altitude
 * @param groundSpeedKt  ground speed in knots, may be null
 * @param trackDeg       track over ground in degrees, may be null
 * @param verticalRateFpm barometric climb rate in ft/min, may be null
 * @param distanceNm     distance from the reference point in nautical miles
 * @param bearingDeg     initial great-circle bearing from the reference point to the aircraft
 */
public record NearbyAircraft(
        String hex,
        String callsign,
        String registration,
        String type,
        String typeDesc,
        Integer altitudeFt,
        boolean onGround,
        Double groundSpeedKt,
        Double trackDeg,
        Integer verticalRateFpm,
        double distanceNm,
        double bearingDeg
) {
}
