package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.espn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Ref(
        @JsonProperty("$ref") URI ref
) {}
