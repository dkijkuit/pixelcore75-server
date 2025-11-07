package nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.Px75ClientException;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1Calendar;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1DriverStandings;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JolpiFormula1Client implements Formula1Client {
    final RestClient jolpiRestClient;

    @Override
    @Cacheable(value = "formula1", key = "'calendar'")
    public Formula1Calendar getFormula1Calendar() {
        final ResponseEntity<JolpiFormula1CalendarResponse> f1CalendarResponse = jolpiRestClient.get()
                .uri("f1/{year}/races/", Year.now().getValue())
                .retrieve()
                .toEntity(JolpiFormula1CalendarResponse.class);

        if (f1CalendarResponse.getStatusCode().is2xxSuccessful()) {
            if (f1CalendarResponse.getBody() != null) {
                return responseToF1Calendar(f1CalendarResponse.getBody());
            } else {
                throw new Px75ClientException("No formula1 body response");
            }
        } else {
            throw new HttpClientErrorException(f1CalendarResponse.getStatusCode());
        }
    }

    @Override
    @Cacheable(value = "formula1", key = "'standings'")
    public Formula1DriverStandings getDriverStandings() {
        final ResponseEntity<JolpiDriverStandings> f1StandingsResponse = jolpiRestClient.get()
                .uri("f1/{year}/driverstandings/", Year.now().getValue())
                .retrieve()
                .toEntity(JolpiDriverStandings.class);

        if (f1StandingsResponse.getStatusCode().is2xxSuccessful()) {
            if (f1StandingsResponse.getBody() != null) {
                return responseToFormula1DriverStandings(f1StandingsResponse.getBody());
            } else {
                throw new Px75ClientException("No formula1 body response");
            }
        } else {
            throw new HttpClientErrorException(f1StandingsResponse.getStatusCode());
        }
    }

    private Formula1DriverStandings responseToFormula1DriverStandings(JolpiDriverStandings jolpiDriverStandings) {
        final List<Formula1DriverStandings.F1Driver> f1Drivers = jolpiDriverStandings.MRData()
                .standingsTable()
                .standingsLists()
                .getFirst()
                .driverStandings()
                .stream()
                .map(driverStanding -> {
                    JolpiDriverStandings.Driver driver = driverStanding.driver();
                    return new Formula1DriverStandings.F1Driver(driver.givenName(), driver.familyName(), driver.code(), Integer.parseInt(driverStanding.position()), Integer.parseInt(driverStanding.points()), driver.nationality(), Integer.parseInt(driver.permanentNumber()), driverStanding.constructors().getFirst().name());
                }).toList();

        return new Formula1DriverStandings(f1Drivers);
    }

    Formula1Calendar responseToF1Calendar(JolpiFormula1CalendarResponse f1CalendarResponse) {
        List<Formula1Calendar.Formula1Race> formula1Races = f1CalendarResponse.MRData().raceTable().races().stream().map(race -> new Formula1Calendar.Formula1Race(
                Integer.parseInt(race.round()),
                race.raceName(),
                race.circuit().circuitName(),
                race.circuit().location().country(),
                Instant.parse(race.date() + "T" + race.time()),
                race.firstPractice() != null ? Instant.parse(race.firstPractice().date() + "T" + race.firstPractice().time()) : null,
                race.secondPractice() != null ? Instant.parse(race.secondPractice().date() + "T" + race.secondPractice().time()) : null,
                race.thirdPractice() != null ? Instant.parse(race.thirdPractice().date() + "T" + race.thirdPractice().time()) : null,
                race.qualifying() != null ? Instant.parse(race.qualifying().date() + "T" + race.qualifying().time()) : null,
                race.sprintQualifying() != null ? Instant.parse(race.sprintQualifying().date() + "T" + race.sprintQualifying().time()) : null,
                race.sprint() != null ? Instant.parse(race.sprint().date() + "T" + race.sprint().time()) : null
        )).toList();

        return new Formula1Calendar(Integer.parseInt(f1CalendarResponse.MRData().raceTable().season()), formula1Races);
    }
}
