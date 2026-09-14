package nl.ctasoftware.crypto.ticker.server.service.screen.date;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.DateScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Golden snapshots for the DATE command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, from the frozen timestamp (the {@code now(ZoneId)} hook keeps renders
 * deterministic). The calendar-glyph BLIT is byte-exact (7 RGB565 colors, within BLIT's
 * 16-entry palette).
 */
class DateCommandParityTests {

    private static final LocalDateTime FROZEN = LocalDateTime.of(2026, 8, 25, 23, 59);

    private static DateScreenService service;

    @BeforeAll
    static void setUp() {
        final Font grinched = FontPageExtractor.loadFont(new File("assets/fonts/grinched-4x7.ttf"), 9f);
        service = new DateScreenService(grinched) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return FROZEN;
            }
        };
    }

    @Test
    void dateCommandBatchMatchesGolden() {
        final DateScreenConfig config = new DateScreenConfig(ScreenType.DATE, 5, "Europe/Amsterdam", "#00FF00");
        CommandGolden.assertGolden("date", CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }
}
