package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import java.time.LocalDateTime;

public record SoccerMatch(
        LocalDateTime date,
        boolean started,
        String matchTime,
        int period,
        SoccerTeam home,
        SoccerTeam away,
        boolean finished
) {
}
