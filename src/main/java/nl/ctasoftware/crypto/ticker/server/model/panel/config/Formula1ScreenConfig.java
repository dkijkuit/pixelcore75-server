package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record Formula1ScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String timezone,
        Formula1DetailsType detailsType
) implements ScreenConfig {
    public enum Formula1DetailsType {
        CALENDAR,
        NEXT_EVENT,
        NEXT_SESSION,
        STANDINGS
    }
}
