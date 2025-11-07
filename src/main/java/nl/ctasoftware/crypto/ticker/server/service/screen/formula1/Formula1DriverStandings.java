package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import java.util.List;

public record Formula1DriverStandings(
        List<F1Driver> standings
) {
    public record F1Driver(
            String firstName,
            String lastName,
            String abbreviation,
            int ranking,
            int points,
            String nationality,
            int permanentNumber,
            String teamName
    ){}
}
