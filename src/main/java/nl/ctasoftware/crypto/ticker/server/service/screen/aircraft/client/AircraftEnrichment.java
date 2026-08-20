package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

/**
 * Enrichment for the closest aircraft: registry details (owner/country/type) and its
 * flight route. Built via {@link #of} from the two adsbdb lookups.
 */
public record AircraftEnrichment(
        String owner,
        String ownerCountry,
        String manufacturer,
        String typeName,
        String airlineName,
        String originCode,
        String originCity,
        String destinationCode,
        String destinationCity
) {

    public static AircraftEnrichment of(final AdsbdbAircraftData details, final AdsbdbRouteData route) {
        // adsbdb sometimes returns identical origin and destination for suffixed/positioning
        // callsigns (e.g. TAP31TY -> LGW>LGW): a data artifact, not a real route. Treat it
        // as unknown so the display skips the route instead of showing a nonsense leg.
        final AdsbdbRouteData effectiveRoute = isDegenerate(route) ? null : route;

        if (details == null && effectiveRoute == null) {
            return null;
        }
        return new AircraftEnrichment(
                orNull(details, AdsbdbAircraftData::registeredOwner),
                orNull(details, AdsbdbAircraftData::registeredOwnerCountryName),
                orNull(details, AdsbdbAircraftData::manufacturer),
                orNull(details, AdsbdbAircraftData::type),
                effectiveRoute != null && effectiveRoute.airline() != null ? blankToNull(effectiveRoute.airline().name()) : null,
                airportCode(effectiveRoute, true),
                airportField(effectiveRoute, true, AdsbdbRouteData.Airport::municipality),
                airportCode(effectiveRoute, false),
                airportField(effectiveRoute, false, AdsbdbRouteData.Airport::municipality)
        );
    }

    private static boolean isDegenerate(final AdsbdbRouteData route) {
        if (route == null || route.origin() == null || route.destination() == null) {
            return false;
        }
        final String originKey = airportKey(route.origin());
        final String destinationKey = airportKey(route.destination());
        return originKey != null && originKey.equals(destinationKey);
    }

    /** Unique airport key: ICAO when present, else IATA. */
    private static String airportKey(final AdsbdbRouteData.Airport airport) {
        if (airport.icaoCode() != null && !airport.icaoCode().isBlank()) {
            return airport.icaoCode().trim();
        }
        return blankToNull(airport.iataCode());
    }

    public boolean hasRegistry() {
        return owner != null || ownerCountry != null || manufacturer != null || typeName != null;
    }

    public boolean hasRoute() {
        return originCode != null && destinationCode != null;
    }

    /** IATA code when present (shorter on the display), else ICAO. */
    private static String airportCode(final AdsbdbRouteData route, final boolean origin) {
        final AdsbdbRouteData.Airport airport = airport(route, origin);
        if (airport == null) {
            return null;
        }
        if (airport.iataCode() != null && !airport.iataCode().isBlank()) {
            return airport.iataCode();
        }
        return blankToNull(airport.icaoCode());
    }

    private static String airportField(final AdsbdbRouteData route, final boolean origin,
                                       final java.util.function.Function<AdsbdbRouteData.Airport, String> getter) {
        final AdsbdbRouteData.Airport airport = airport(route, origin);
        return airport == null ? null : blankToNull(getter.apply(airport));
    }

    private static AdsbdbRouteData.Airport airport(final AdsbdbRouteData route, final boolean origin) {
        if (route == null) {
            return null;
        }
        return origin ? route.origin() : route.destination();
    }

    private static <T> String orNull(final T data, final java.util.function.Function<T, String> getter) {
        return data == null ? null : blankToNull(getter.apply(data));
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
