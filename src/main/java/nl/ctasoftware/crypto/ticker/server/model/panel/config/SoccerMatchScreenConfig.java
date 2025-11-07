package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record SoccerMatchScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String competitionId,
        String teamId
) implements ScreenConfig {}
