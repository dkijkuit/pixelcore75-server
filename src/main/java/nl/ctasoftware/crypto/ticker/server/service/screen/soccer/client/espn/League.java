package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.espn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record League(
        String uid,
        String name,
        String slug,
        String abbreviation
) {}
