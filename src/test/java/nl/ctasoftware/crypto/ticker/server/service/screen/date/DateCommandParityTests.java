package nl.ctasoftware.crypto.ticker.server.service.screen.date;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.DateScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-image parity for the DATE command screen (plan §6): the frame path's AWT
 * render vs the {@link AcmdMirror} of the command batch, both in panel RGB565, from
 * the same frozen timestamp (the {@code now(ZoneId)} hook makes both paths render
 * identical text).
 *
 * <p>Tolerance: &ge;95% pixel equality (mismatch budget 102 of 2048 px), the same
 * family as the clock/aircraft/spotify suites. The calendar-glyph BLIT is byte-exact
 * (7 RGB565 colors, within BLIT's 16-entry palette); the budget guards JDK
 * font-rendering variance, not an expected gap.</p>
 */
class DateCommandParityTests {

    private static final int MISMATCH_BUDGET = 102; // 5% of 2048

    private static final LocalDateTime FROZEN = LocalDateTime.of(2026, 8, 25, 23, 59);

    @BeforeAll
    static void setUp() {
        final Font grinched = FontPageExtractor.loadFont(new File("assets/fonts/grinched-4x7.ttf"), 9f);
        service = new DateScreenService(new PaintToolsService(null, null, grinched), grinched) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return FROZEN;
            }
        };
    }

    private static DateScreenService service;

    @Test
    void dateCommandBatchMatchesGoldenFrameWithinBudget() {
        final DateScreenConfig config = new DateScreenConfig(ScreenType.DATE, 5, "Europe/Amsterdam", "#00FF00");

        final Optional<BufferedImage> golden = service.renderScreen(config);
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);

        final int mismatch = FrameParity.mismatchedPixels(FrameParity.rgb565(golden.orElseThrow()), command);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "DATE parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }
}
