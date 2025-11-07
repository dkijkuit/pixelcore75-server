package nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JolpiDriverStandings(
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
            @JsonProperty("StandingsTable") StandingsTable standingsTable
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StandingsTable(
            @JsonProperty("season") String season,
            @JsonProperty("round") String round,
            @JsonProperty("StandingsLists") List<StandingsList> standingsLists
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StandingsList(
            @JsonProperty("season") String season,
            @JsonProperty("round") String round,
            @JsonProperty("DriverStandings") List<DriverStanding> driverStandings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriverStanding(
            @JsonProperty("position") String position,
            @JsonProperty("positionText") String positionText,
            @JsonProperty("points") String points,
            @JsonProperty("wins") String wins,
            @JsonProperty("Driver") Driver driver,
            // Named 'ConstructorEntry' to avoid confusion with java.lang.reflect.Constructor
            @JsonProperty("Constructors") List<ConstructorEntry> constructors
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Driver(
            @JsonProperty("driverId") String driverId,
            @JsonProperty("permanentNumber") String permanentNumber,
            @JsonProperty("code") String code,
            @JsonProperty("url") String url,
            @JsonProperty("givenName") String givenName,
            @JsonProperty("familyName") String familyName,
            @JsonProperty("dateOfBirth") String dateOfBirth,
            @JsonProperty("nationality") String nationality
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConstructorEntry(
            @JsonProperty("constructorId") String constructorId,
            @JsonProperty("url") String url,
            @JsonProperty("name") String name,
            @JsonProperty("nationality") String nationality
    ) {
    }
}
