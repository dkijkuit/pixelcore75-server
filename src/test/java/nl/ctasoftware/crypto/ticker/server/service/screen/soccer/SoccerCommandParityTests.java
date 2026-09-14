package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SoccerMatchScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the SOCCER command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, pinned per mocked match state. Covers the three status flavors (live with
 * match time, finished, scheduled with the kickoff timestamp) plus the no-match page;
 * shirt colors are dynamic per team and quantize to RGB565 per batch.
 */
class SoccerCommandParityTests {

    private SoccerMatchService service;
    private SoccerMatchClient client;
    private SoccerMatchScreenConfig config;

    @BeforeEach
    void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        final Font cgPixel = FontPageExtractor.loadFont(new File("assets/fonts/cg-pixel-4x5.ttf"), 5f);
        client = mock(SoccerMatchClient.class);
        service = new SoccerMatchService(client, ledBoard, cgPixel);
        config = new SoccerMatchScreenConfig(ScreenType.SOCCER_MATCH, 5, "ned.1", "36");
    }

    private void assertGoldenFor(final String name, final Optional<SoccerMatch> match) {
        when(client.getSoccerMatch(config.competitionId(), config.teamId())).thenReturn(match);
        CommandGolden.assertGolden(name, CommandGolden.frameAt(service.renderCommandBatch(config), 0));
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
    void liveMatchMatchesGolden() {
        assertGoldenFor("soccer-live", Optional.of(match(true, false, "67'", LocalDateTime.of(2026, 8, 23, 14, 30))));
    }

    @Test
    void finishedMatchMatchesGolden() {
        assertGoldenFor("soccer-fulltime", Optional.of(match(false, true, "FT", LocalDateTime.of(2026, 8, 23, 14, 30))));
    }

    @Test
    void scheduledMatchMatchesGolden() {
        assertGoldenFor("soccer-scheduled", Optional.of(match(false, false, "", LocalDateTime.of(2026, 8, 30, 20, 0))));
    }

    @Test
    void noMatchPageMatchesGolden() {
        assertGoldenFor("soccer-nomatch", Optional.empty());
    }
}
