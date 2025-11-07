package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record ClockScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        boolean format24hr,
        String color
) implements ScreenConfig {}
