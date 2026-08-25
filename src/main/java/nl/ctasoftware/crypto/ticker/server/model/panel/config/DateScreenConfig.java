package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record DateScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        String color,
        boolean disabled
) implements ScreenConfig {

    public DateScreenConfig(final ScreenType screenType, final int durationSeconds,
                            final String timezone, final String color) {
        this(screenType, durationSeconds, timezone, color, false);
    }
}
