package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SoccerMatchScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-image parity for the SOCCER command screen (plan §6): the frame path's AWT
 * render vs the {@link AcmdMirror} of the command batch, both in panel RGB565, from
 * the same mocked match state. Covers the three status flavors (live with match time,
 * finished, scheduled with the kickoff timestamp) plus the no-match page; shirt colors
 * are dynamic per team and quantize to RGB565 identically on both paths.
 *
 * <p>Tolerance: &ge;95% pixel equality (mismatch budget 102 of 2048 px), the same
 * family as the clock/aircraft/spotify suites; the budget guards JDK font-rendering
 * variance, not an expected gap.</p>
 */
class SoccerCommandParityTests {

    private static final int MISMATCH_BUDGET = 102; // 5% of 2048

    private SoccerMatchService service;
    private SoccerMatchClient client;
    private SoccerMatchScreenConfig config;

    @BeforeEach
    void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        final Font cgPixel = FontPageExtractor.loadFont(new File("assets/fonts/cg-pixel-4x5.ttf"), 5f);
        client = mock(SoccerMatchClient.class);
        service = new SoccerMatchService(client, new PaintToolsService(null, null, ledBoard), ledBoard, cgPixel);
        config = new SoccerMatchScreenConfig(ScreenType.SOCCER_MATCH, 5, "ned.1", "36");
    }

    private int mismatches(final Optional<SoccerMatch> match) {
        when(client.getSoccerMatch(config.competitionId(), config.teamId())).thenReturn(match);
        final BufferedImage frame = service.renderScreen(config).orElseThrow();
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);
        return FrameParity.mismatchedPixels(FrameParity.rgb565(frame), command);
    }

    private static SoccerTeam team(final String abbreviation, final String score,
                                   final String color, final String colorAlternate) {
        return new SoccerTeam("Eindhoven", "PSV " + abbreviation, abbreviation, "home", score, color, colorAlternate);
    }

    private static SoccerMatch match(final boolean started, final boolean finished,
                                     final String matchTime, final LocalDateTime date) {
        return new SoccerMatch(date, started, matchTime, 2,
                team("PSV", "2", "0A1EAA", "FFFFFF"),
                team("AJA", "1", "D2122E", "FFE500"),
                finished);
    }

    @Test
    void liveMatchMatchesAcrossPaths() {
        final int mismatch = mismatches(Optional.of(match(true, false, "67'", LocalDateTime.of(2026, 8, 23, 14, 30))));
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "SOCCER live parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void finishedMatchMatchesAcrossPaths() {
        final int mismatch = mismatches(Optional.of(match(false, true, "FT", LocalDateTime.of(2026, 8, 23, 14, 30))));
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "SOCCER full-time parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void scheduledMatchMatchesAcrossPaths() {
        final int mismatch = mismatches(Optional.of(match(false, false, "", LocalDateTime.of(2026, 8, 30, 20, 0))));
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "SOCCER scheduled parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void noMatchPageMatchesAcrossPaths() {
        final int mismatch = mismatches(Optional.empty());
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "SOCCER no-match parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }
}
