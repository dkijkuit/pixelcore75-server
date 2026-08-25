package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record Formula1ScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        Formula1DetailsType detailsType,
        boolean disabled
) implements ScreenConfig {
    public enum Formula1DetailsType {
        CALENDAR,
        NEXT_EVENT,
        NEXT_SESSION,
        STANDINGS
    }

    public Formula1ScreenConfig(final ScreenType screenType, final int durationSeconds,
                                final String timezone, final Formula1DetailsType detailsType) {
        this(screenType, durationSeconds, timezone, detailsType, false);
    }
}
