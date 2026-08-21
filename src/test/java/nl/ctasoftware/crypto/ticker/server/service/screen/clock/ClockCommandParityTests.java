package nl.ctasoftware.crypto.ticker.server.service.screen.clock;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-image parity for the CLOCK command screen (plan §6): the frame path's AWT render
 * vs the {@link AcmdMirror} of the command batch, both in panel RGB565, from the same
 * frozen timestamp (the {@code now(ZoneId)} hook makes both paths render identical text).
 *
 * <p>Tolerance: &ge;95% pixel equality (mismatch budget 102 of 2048 px; measured 0 — the
 * extractor thresholds the very outlines AWT fills and this pixel font's advances are
 * integers). The BLIT of the 11&times;11 clock face is byte-exact (14 RGB565 colors,
 * within BLIT's 16-entry palette); the budget guards JDK font-rendering variance, not
 * an expected gap.</p>
 */
class ClockCommandParityTests {

    private static final int MISMATCH_BUDGET = 102; // 5% of 2048; measured 0

    private static final LocalDateTime FROZEN = LocalDateTime.of(2026, 8, 21, 9, 41);

    private static ClockScreenService service;

    @BeforeAll
    static void setUp() {
        final Font ledBoard = nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor
                .loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        service = new ClockScreenService(new PaintToolsService(null, null, ledBoard), ledBoard) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return FROZEN;
            }
        };
    }

    @Test
    void clockCommandBatchMatchesGoldenFrameWithinBudget() {
        final ClockScreenConfig config =
                new ClockScreenConfig(ScreenType.CLOCK, 5, "Europe/Amsterdam", true, "#00FF00");

        final Optional<BufferedImage> golden = service.renderScreen(config);
        final byte[] batch = service.renderCommandBatch(config);

        final AcmdMirror mirror = AcmdMirror.parse(batch);
        final int mismatch = FrameParity.mismatchedPixels(
                FrameParity.rgb565(golden.orElseThrow()), mirror.frameAt(0));
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "CLOCK parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void twelveHourFormatBatchMatchesTheFramePathFormatter() {
        final LocalDateTime frozenPm = LocalDateTime.of(2026, 8, 21, 13, 7);
        final ClockScreenService pmService = new ClockScreenService(
                service.paintToolsService, service.ledBoardFont8Px) {
            @Override
            protected LocalDateTime now(final ZoneId zone) {
                return frozenPm;
            }
        };
        final ClockScreenConfig config =
                new ClockScreenConfig(ScreenType.CLOCK, 5, "Europe/Amsterdam", false, "#FFFF00");

        // The AM/PM text is locale-dependent (e.g. "01:07 PM" vs "01:07 p.m.") — the batch
        // must carry exactly what the frame path renders, whatever the locale produces.
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
