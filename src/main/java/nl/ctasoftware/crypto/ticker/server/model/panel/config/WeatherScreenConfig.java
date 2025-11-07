package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;

import java.util.List;

public record WeatherScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        LatLon latLon
) implements ScreenConfig {}
