package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record DateScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        String color
) implements ScreenConfig {
}
