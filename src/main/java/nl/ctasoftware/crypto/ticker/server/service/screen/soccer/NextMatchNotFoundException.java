package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

public class NextMatchNotFoundException extends RuntimeException {
    public NextMatchNotFoundException(String team, String competition) {
        super(String .format("no next match for team %s and competition %s", team, competition));
    }
}
