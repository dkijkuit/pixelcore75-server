package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SoccerMatchScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
public class SoccerMatchService implements CommandScreenService<SoccerMatchScreenConfig> {
    private static final int COLOR_THRESHOLD = 100;

    /** ACMD font page ids (shared convention: 0 = EXEPixelPerfect@16f, 1 = cg-pixel@5f). */
    static final int PAGE_LEDBOARD_ID = 0;
    static final int PAGE_CGPIXEL_ID = 1;

    static final int ABBREV_BASELINE = 9;
    static final int SCORE_BASELINE = 20;
    static final int STATUS_BASELINE = 30;
    static final int[] NO_MATCH_BASELINES = {8, 18, 28};

    final SoccerMatchClient soccerMatchClient;
    final Font ledBoardFont8Px;
    final Font cgPixel5Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM HH:mm");

    /** Extracted FONT pages; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage ledBoardPage;
    private volatile FontPageExtractor.FontPage cgPixelPage;

    public SoccerMatchService(SoccerMatchClient soccerMatchClient, Font ledBoardFont8Px, Font cgPixel5Px) {
        this.soccerMatchClient = soccerMatchClient;
        this.ledBoardFont8Px = ledBoardFont8Px;
        this.cgPixel5Px = cgPixel5Px;
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.SOCCER_MATCH;
    }

    public Color getShirtColor(String color) {
        Color shirtColor = Color.decode("#" + color);
        if(shirtColor.getRed() < COLOR_THRESHOLD && shirtColor.getGreen() < COLOR_THRESHOLD && shirtColor.getBlue() < COLOR_THRESHOLD) {
            int diff = COLOR_THRESHOLD - Math.max(shirtColor.getRed(), Math.max(shirtColor.getGreen(), shirtColor.getBlue()));
            shirtColor = new Color(shirtColor.getRed() + diff, shirtColor.getGreen() + diff, shirtColor.getBlue() + diff);
        }
        return shirtColor;
    }

    /* --------------------------------------------------------------------
     * ACMD command path: the same match state as FONT pages + left/right/
     * center TEXT — shirt colors are dynamic per team, so they quantize to
     * RGB565 per batch exactly like the frame path's pixels do.
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final SoccerMatchScreenConfig screenConfig) {
        final Optional<SoccerMatch> soccerMatchOpt = soccerMatchClient.getSoccerMatch(screenConfig.competitionId(), screenConfig.teamId());
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();

        final CommandBatch batch = CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());

        if (soccerMatchOpt.isPresent()) {
            final SoccerMatch soccerMatch = soccerMatchOpt.get();
            AcmdLayout.left(batch, ledPage, PAGE_LEDBOARD_ID, soccerMatch.home().abbreviation(),
                    1, ABBREV_BASELINE, getShirtColor(soccerMatch.home().color()));
            AcmdLayout.right(batch, ledPage, PAGE_LEDBOARD_ID, soccerMatch.away().abbreviation(),
                    ABBREV_BASELINE, getShirtColor(soccerMatch.away().colorAlternate()));

            if (soccerMatch.started() && !soccerMatch.finished()) {
                AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, soccerMatch.matchTime(),
                        STATUS_BASELINE, Color.CYAN);
            } else if (soccerMatch.finished()) {
                AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, "Full time",
                        STATUS_BASELINE, Color.CYAN);
            } else {
                AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, soccerMatch.date().format(formatter),
                        STATUS_BASELINE, Color.CYAN);
            }
            AcmdLayout.center(batch, ledPage, PAGE_LEDBOARD_ID,
                    soccerMatch.home().score() + " - " + soccerMatch.away().score(),
                    SCORE_BASELINE, Color.WHITE);
        } else {
            AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, "No match for",
                    NO_MATCH_BASELINES[0], Color.BLUE);
            AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, "id: " + screenConfig.teamId(),
                    NO_MATCH_BASELINES[1], Color.BLUE);
            AcmdLayout.center(batch, cgPage, PAGE_CGPIXEL_ID, screenConfig.competitionId(),
                    NO_MATCH_BASELINES[2], Color.BLUE);
        }
        return batch.build();
    }

    private FontPageExtractor.FontPage ledBoardPage() {
        FontPageExtractor.FontPage page = ledBoardPage;
        if (page == null) {
            page = FontPageExtractor.extract(ledBoardFont8Px);
            ledBoardPage = page;
        }
        return page;
    }

    private FontPageExtractor.FontPage cgPixelPage() {
        FontPageExtractor.FontPage page = cgPixelPage;
        if (page == null) {
            page = FontPageExtractor.extract(cgPixel5Px);
            cgPixelPage = page;
        }
        return page;
    }
}
