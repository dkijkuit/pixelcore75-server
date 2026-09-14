package nl.ctasoftware.crypto.ticker.server.service.screen.clock;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden snapshots for the CLOCK command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, from the frozen timestamp (the {@code now(ZoneId)} hook keeps renders
 * deterministic). The BLIT of the 11&times;11 clock face is byte-exact (14 RGB565
 * colors, within BLIT's 16-entry palette).
 */
class ClockCommandParityTests {

    private static final LocalDateTime FROZEN = LocalDateTime.of(2026, 8, 21, 9, 41);

    private static ClockScreenService service;

    @BeforeAll
    static void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        service = new ClockScreenService(ledBoard) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return FROZEN;
            }
        };
    }

    @Test
    void clockCommandBatchMatchesGolden() {
        final ClockScreenConfig config =
                new ClockScreenConfig(ScreenType.CLOCK, 5, "Europe/Amsterdam", true, "#00FF00");

        CommandGolden.assertGolden("clock", CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    @Test
    void twelveHourFormatBatchCarriesTheLocaleFormatter() {
        final LocalDateTime frozenPm = LocalDateTime.of(2026, 8, 21, 13, 7);
        final ClockScreenService pmService = new ClockScreenService(service.ledBoardFont8Px) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return frozenPm;
            }
        };
        final ClockScreenConfig config =
                new ClockScreenConfig(ScreenType.CLOCK, 5, "Europe/Amsterdam", false, "#FFFF00");

        // The AM/PM text is locale-dependent (e.g. "01:07 PM" vs "01:07 p.m.") — the
        // batch must carry exactly what the formatter produces, whatever the locale.
        final byte[] batch = pmService.renderCommandBatch(config);
        final List<AcmdCommand> commands = AcmdParser.parse(batch).commands();

        assertEquals(AcmdCommand.Cls.class, commands.getFirst().getClass());
        assertTrue(commands.stream().anyMatch(c -> c instanceof AcmdCommand.Blit),
                "clock face must BLIT");
        final AcmdCommand.Text text = commands.stream()
                .filter(AcmdCommand.Text.class::isInstance)
                .map(AcmdCommand.Text.class::cast)
                .findFirst().orElseThrow();
        assertEquals(pmService.formatterAmPm.format(frozenPm), text.ascii());
    }
}
