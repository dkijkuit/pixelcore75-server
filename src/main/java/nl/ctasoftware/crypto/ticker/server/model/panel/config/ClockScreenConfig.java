package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record ClockScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        boolean format24hr,
        String color,
        boolean disabled
) implements ScreenConfig {

    public ClockScreenConfig(final ScreenType screenType, final int durationSeconds,
                             final String timezone, final boolean format24hr, final String color) {
        this(screenType, durationSeconds, timezone, format24hr, color, false);
    }
}
