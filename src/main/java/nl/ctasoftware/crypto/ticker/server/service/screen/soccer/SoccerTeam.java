package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

public record SoccerTeam(
        String location,
        String displayName,
        String abbreviation,
        String homeAway,
        String score,
        String color,
        String colorAlternate
) {
}
