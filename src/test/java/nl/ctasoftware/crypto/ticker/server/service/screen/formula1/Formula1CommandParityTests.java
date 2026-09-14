package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig.Formula1DetailsType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client.Formula1Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the FORMULA1 command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, pinned per mocked client data. Covers all four detail types; the country flags
 * (5&times;5) and the F1 logo (19&times;7) are committed pixel assets inside BLIT's
 * 16-entry RGB565 palette. Race timestamps are far in the future so
 * {@code getNextFormula1Races}/{@code getNextSession}'s internal {@code Instant.now()}
 * filters are deterministic.
 */
class Formula1CommandParityTests {

    private Formula1ScreenService service;
    private Formula1Client client;

    @BeforeEach
    void setUp() {
        final Font cgPixel = FontPageExtractor.loadFont(new File("assets/fonts/cg-pixel-4x5.ttf"), 5f);
        client = mock(Formula1Client.class);
        service = new Formula1ScreenService(client, new ImageService(), cgPixel);
    }

    private void assertGoldenFor(final String name, final Formula1DetailsType detailsType,
                                 final Formula1Calendar calendar) {
        when(client.getFormula1Calendar()).thenReturn(calendar);
        final Formula1ScreenConfig config = new Formula1ScreenConfig(
                ScreenType.FORMULA1, 5, "Europe/Amsterdam", detailsType);
        CommandGolden.assertGolden(name, CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    private static Formula1Calendar.Formula1Race race(final int round, final String name, final String country,
                                                      final Instant date, final Instant fp1, final Instant qualifying) {
        return new Formula1Calendar.Formula1Race(round, name, name + " Circuit", country,
                date, fp1, fp1.plusSeconds(7200), fp1.plusSeconds(14400), qualifying, null, null);
    }

    @Test
    void standingsMatchGolden() {
        when(client.getDriverStandings()).thenReturn(new Formula1DriverStandings(List.of(
                driver(1, "VER", 301, "Red Bull"),
                driver(2, "NOR", 275, "McLaren"),
                driver(3, "PIA", 256, "McLaren"),
                driver(4, "LEC", 217, "Ferrari"),
                driver(5, "RUS", 186, "Mercedes"))));
        final Formula1ScreenConfig config =
                new Formula1ScreenConfig(ScreenType.FORMULA1, 5, "Europe/Amsterdam", Formula1DetailsType.STANDINGS);
        CommandGolden.assertGolden("f1-standings", CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    private static Formula1DriverStandings.F1Driver driver(final int ranking, final String abbreviation,
                                                           final int points, final String teamName) {
        return new Formula1DriverStandings.F1Driver("First", "Driver", abbreviation, ranking, points,
                "Dutch", ranking, teamName);
    }

    @Test
    void calendarMatchesGolden() {
        final Instant base = Instant.parse("2027-06-01T12:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", base, base.minusSeconds(172800), base.minusSeconds(86400)),
                race(8, "French Grand Prix", "France", base.plusSeconds(604800), base.plusSeconds(432000), base.plusSeconds(518400)),
                race(9, "Brazilian Grand Prix", "Brazil", base.plusSeconds(1209600), base.plusSeconds(1036800), base.plusSeconds(1123200)),
                race(10, "Japanese Grand Prix", "Japan", base.plusSeconds(1814400), base.plusSeconds(1641600), base.plusSeconds(1728000))));
        assertGoldenFor("f1-calendar", Formula1DetailsType.CALENDAR, calendar);
    }

    @Test
    void nextEventMatchesGolden() {
        final Instant race = Instant.parse("2027-06-01T14:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", race, race.minusSeconds(259200), race.minusSeconds(86400))));
        assertGoldenFor("f1-next-event", Formula1DetailsType.NEXT_EVENT, calendar);
    }

    @Test
    void nextSessionMatchesGolden() {
        final Instant fp1 = Instant.parse("2027-05-29T11:30:00Z");
        final Instant race = Instant.parse("2027-05-31T14:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", race, fp1, race.minusSeconds(86400))));
        assertGoldenFor("f1-next-session", Formula1DetailsType.NEXT_SESSION, calendar);
    }
}
