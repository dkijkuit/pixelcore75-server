package nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// Top-level wrapper for the whole payload
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JolpiFormula1CalendarResponse(
        @JsonProperty("MRData") MRData MRData
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MRData(
            @JsonProperty("xmlns") String xmlns,
            @JsonProperty("series") String series,
            @JsonProperty("url") String url,
            @JsonProperty("limit") String limit,
            @JsonProperty("offset") String offset,
            @JsonProperty("total") String total,
            @JsonProperty("RaceTable") RaceTable raceTable
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RaceTable(
            @JsonProperty("season") String season,
            @JsonProperty("Races") List<Race> races
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Race(
            @JsonProperty("season") String season,
            @JsonProperty("round") String round,
            @JsonProperty("url") String url,
            @JsonProperty("raceName") String raceName,
            @JsonProperty("Circuit") Circuit circuit,
            @JsonProperty("date") String date,
            @JsonProperty("time") String time,
            @JsonProperty("FirstPractice") Session firstPractice,
            @JsonProperty("SecondPractice") Session secondPractice,
            @JsonProperty("ThirdPractice") Session thirdPractice,
            @JsonProperty("Qualifying") Session qualifying,
            @JsonProperty("SprintQualifying") Session sprintQualifying,
            @JsonProperty("Sprint") Session sprint
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Circuit(
            @JsonProperty("circuitId") String circuitId,
            @JsonProperty("url") String url,
            @JsonProperty("circuitName") String circuitName,
            @JsonProperty("Location") Location location
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(
            @JsonProperty("lat") String lat,
            @JsonProperty("long") String _long, // "long" is a Java keyword; keep JSON name via @JsonProperty
            @JsonProperty("locality") String locality,
            @JsonProperty("country") String country
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
            @JsonProperty("date") String date,
            @JsonProperty("time") String time
    ) {}
}
