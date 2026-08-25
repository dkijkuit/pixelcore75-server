package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;

public record AircraftScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        AircraftDisplayMode displayMode,
        LatLon latLon,
        int radiusNm,
        boolean militaryOnly,
        AircraftDisplayUnits units,
        int frameDelayMs,
        boolean disabled
) implements FrameScreenConfig {

    public enum AircraftDisplayMode {
        RADAR,
        CLOSEST,
        LIST
    }

    /** Altitude/speed display units: flight levels + knots, or meters + km/h. */
    public enum AircraftDisplayUnits {
        AVIATION,
        METRIC
    }

    public AircraftScreenConfig(final ScreenType screenType, final int durationSeconds,
                                final AircraftDisplayMode displayMode, final LatLon latLon,
                                final int radiusNm, final boolean militaryOnly,
                                final AircraftDisplayUnits units, final int frameDelayMs) {
        this(screenType, durationSeconds, displayMode, latLon, radiusNm, militaryOnly,
                units, frameDelayMs, false);
    }

    @Override
    public boolean producesFrames() {
        // RADAR animates the sweep; CLOSEST cycles its info pages; LIST is static.
        return displayMode != AircraftDisplayMode.LIST;
    }

    @Override
    public boolean stageAhead() {
        // RADAR's ~200-frame stream (protocol cap, ~820KB) takes many seconds inline —
        // the panel drains frames at 256B/loop while an animation plays — so it must
        // upload during the previous screen. Staleness is invisible: blips move <1px
        // per slot, and the sweep's fixed-base whole-revolution phase chains slots.
        // CLOSEST stays fresh: its telemetry readout should be current, and at <=3
        // frames its inline upload is instant. LIST is static.
        return displayMode == AircraftDisplayMode.RADAR;
    }
}
