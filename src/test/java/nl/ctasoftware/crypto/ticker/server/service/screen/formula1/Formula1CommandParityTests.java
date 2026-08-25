package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig.Formula1DetailsType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client.Formula1Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-image parity for the FORMULA1 command screen (plan §6): the frame path's AWT
 * render vs the {@link AcmdMirror} of the command batch, both in panel RGB565, from
 * the same mocked client data. Covers all four detail types; the country flags
 * (5&times;5) and the F1 logo (19&times;7) are committed pixel assets inside BLIT's
 * 16-entry RGB565 palette, so their BLITs are byte-exact. Race timestamps are far in
 * the future so {@code getNextFormula1Races}/{@code getNextSession}'s internal
 * {@code Instant.now()} filters are deterministic.
 *
 * <p>Tolerance: &ge;95% pixel equality (mismatch budget 102 of 2048 px), the same
 * family as the clock/aircraft/spotify suites; the budget guards JDK font-rendering
 * variance, not an expected gap.</p>
 */
class Formula1CommandParityTests {

    private static final int MISMATCH_BUDGET = 102; // 5% of 2048

    private Formula1ScreenService service;
    private Formula1Client client;

    @BeforeEach
    void setUp() {
        final Font cgPixel = FontPageExtractor.loadFont(new File("assets/fonts/cg-pixel-4x5.ttf"), 5f);
        client = mock(Formula1Client.class);
        service = new Formula1ScreenService(client, new PaintToolsService(null, null, cgPixel),
                new ImageService(), cgPixel);
    }

    private int mismatches(final Formula1DetailsType detailsType, final Formula1Calendar calendar) {
        when(client.getFormula1Calendar()).thenReturn(calendar);
        final Formula1ScreenConfig config = new Formula1ScreenConfig(
                ScreenType.FORMULA1, 5, "Europe/Amsterdam", detailsType);
        final BufferedImage frame = service.renderScreen(config).orElseThrow();
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);
        return FrameParity.mismatchedPixels(FrameParity.rgb565(frame), command);
    }

    private static Formula1Calendar.Formula1Race race(final int round, final String name, final String country,
                                                      final Instant date, final Instant fp1, final Instant qualifying) {
        return new Formula1Calendar.Formula1Race(round, name, name + " Circuit", country,
                date, fp1, fp1.plusSeconds(7200), fp1.plusSeconds(14400), qualifying, null, null);
    }

    @Test
    void standingsMatchAcrossPaths() {
        when(client.getDriverStandings()).thenReturn(new Formula1DriverStandings(List.of(
                driver(1, "VER", 301, "Red Bull"),
                driver(2, "NOR", 275, "McLaren"),
                driver(3, "PIA", 256, "McLaren"),
                driver(4, "LEC", 217, "Ferrari"),
                driver(5, "RUS", 186, "Mercedes"))));
        final Formula1ScreenConfig config =
                new Formula1ScreenConfig(ScreenType.FORMULA1, 5, "Europe/Amsterdam", Formula1DetailsType.STANDINGS);
        final BufferedImage frame = service.renderScreen(config).orElseThrow();
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);
        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(frame), command);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "F1 standings parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    private static Formula1DriverStandings.F1Driver driver(final int ranking, final String abbreviation,
                                                           final int points, final String teamName) {
        return new Formula1DriverStandings.F1Driver("First", "Driver", abbreviation, ranking, points,
                "Dutch", ranking, teamName);
    }

    @Test
    void calendarMatchesAcrossPaths() {
        final Instant base = Instant.parse("2027-06-01T12:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", base, base.minusSeconds(172800), base.minusSeconds(86400)),
                race(8, "French Grand Prix", "France", base.plusSeconds(604800), base.plusSeconds(432000), base.plusSeconds(518400)),
                race(9, "Brazilian Grand Prix", "Brazil", base.plusSeconds(1209600), base.plusSeconds(1036800), base.plusSeconds(1123200)),
                race(10, "Japanese Grand Prix", "Japan", base.plusSeconds(1814400), base.plusSeconds(1641600), base.plusSeconds(1728000))));
        final int mismatch = mismatches(Formula1DetailsType.CALENDAR, calendar);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "F1 calendar parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void nextEventMatchesAcrossPaths() {
        final Instant race = Instant.parse("2027-06-01T14:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", race, race.minusSeconds(259200), race.minusSeconds(86400))));
        final int mismatch = mismatches(Formula1DetailsType.NEXT_EVENT, calendar);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "F1 next-event parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void nextSessionMatchesAcrossPaths() {
        final Instant fp1 = Instant.parse("2027-05-29T11:30:00Z");
        final Instant race = Instant.parse("2027-05-31T14:00:00Z");
        final Formula1Calendar calendar = new Formula1Calendar(2027, List.of(
                race(7, "Belgian Grand Prix", "Belgium", race, fp1, race.minusSeconds(86400))));
        final int mismatch = mismatches(Formula1DetailsType.NEXT_SESSION, calendar);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "F1 next-session parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }
}
