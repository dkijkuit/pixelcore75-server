package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatch;

import java.util.List;
import java.util.Optional;

public interface SoccerMatchClient {
    Optional<SoccerMatch> getSoccerMatch(String competition, String teamId);
    List<String> getLeagues();
}
