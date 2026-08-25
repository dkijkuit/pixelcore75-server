package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record SoccerMatchScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String competitionId,
        String teamId,
        boolean disabled
) implements ScreenConfig {

    public SoccerMatchScreenConfig(final ScreenType screenType, final int durationSeconds,
                                   final String competitionId, final String teamId) {
        this(screenType, durationSeconds, competitionId, teamId, false);
    }
}
