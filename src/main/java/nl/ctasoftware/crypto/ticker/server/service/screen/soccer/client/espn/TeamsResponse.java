package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.espn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamsResponse(
        List<Sport> sports
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sport(
            List<League> leagues
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record League(
            List<TeamEntry> teams
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamEntry(
            Team team
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Team(
            String id,
            String displayName
    ) {}
}
