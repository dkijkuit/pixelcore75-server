package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.espn;

import com.fasterxml.jackson.annotation.*;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NextMatchResponse {

    private Team team;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        @JsonProperty("nextEvent")
        private List<Event> nextEvent;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Event {
        private String id;
        private String date;
        private String name;

        @JsonProperty("competitions")
        private List<Competition> competitions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Competition {
        @JsonProperty("competitors")
        private List<Competitor> competitors;

        @JsonProperty("recent")
        private boolean recent;

        private Status status;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Competitor {
        @JsonProperty("homeAway")
        private String homeAway;

        @JsonProperty("score")
        @JsonSetter(nulls = Nulls.SKIP)
        private String score;

        @JsonProperty("team")
        private CompetitorTeam team;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompetitorTeam {
        @JsonProperty("location")
        private String location;

        @JsonProperty("displayName")
        private String displayName;

        @JsonProperty("abbreviation")
        private String abbreviation;

        @JsonProperty("color")
        private String color;

        @JsonProperty("alternateColor")
        private String alternateColor;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        @JsonProperty("displayClock")
        private String displayClock;

        @JsonProperty("type")
        private Type type;

        @JsonProperty("period")
        private int period;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Type {
        @JsonProperty("name")
        private String name;

        @JsonProperty("shortDetail")
        private String shortDetail;

        @JsonProperty("completed")
        private boolean completed;
    }
}
