package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;

import java.util.List;

public record WeatherScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        LatLon latLon,
        boolean disabled
) implements ScreenConfig {

    public WeatherScreenConfig(final ScreenType screenType, final int durationSeconds,
                               final LatLon latLon) {
        this(screenType, durationSeconds, latLon, false);
    }
}
