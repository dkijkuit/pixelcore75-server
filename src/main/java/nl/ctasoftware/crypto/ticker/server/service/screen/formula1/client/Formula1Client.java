package nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1Calendar;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1DriverStandings;

public interface Formula1Client {
    Formula1Calendar getFormula1Calendar();
    Formula1DriverStandings getDriverStandings();
}
