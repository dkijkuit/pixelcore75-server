package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SoccerMatchScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
public class SoccerMatchService implements ScreenService<SoccerMatchScreenConfig> {
    private static final int COLOR_THRESHOLD = 100;

    final SoccerMatchClient soccerMatchClient;
    final PaintToolsService paintToolsService;
    final Font ledBoardFont8Px;
    final Font cgPixel5Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM HH:mm");

    public SoccerMatchService(SoccerMatchClient soccerMatchClient, PaintToolsService paintToolsService, Font ledBoardFont8Px, Font cgPixel5Px) {
        this.soccerMatchClient = soccerMatchClient;
        this.paintToolsService = paintToolsService;
        this.ledBoardFont8Px = ledBoardFont8Px;
        this.cgPixel5Px = cgPixel5Px;
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.SOCCER_MATCH;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final SoccerMatchScreenConfig screenConfig) {
        final Optional<SoccerMatch> soccerMatchOpt = soccerMatchClient.getSoccerMatch(screenConfig.competitionId(), screenConfig.teamId());
        final BufferedImage matchImage = paintToolsService.newImage();

        if (soccerMatchOpt.isPresent()) {
            final SoccerMatch soccerMatch = soccerMatchOpt.get();
            paintToolsService.drawText(matchImage, ledBoardFont8Px, soccerMatch.home().abbreviation(), 1, 9, getShirtColor(soccerMatch.home().color()));
            paintToolsService.drawTextAlignRight(matchImage, ledBoardFont8Px, soccerMatch.away().abbreviation(), 9, getShirtColor(soccerMatch.away().colorAlternate()));

            if (soccerMatch.started() && !soccerMatch.finished()) {
                paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, soccerMatch.matchTime(), 30, Color.CYAN);
            } else if (soccerMatch.finished()) {
                paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, "Full time", 30, Color.CYAN);
            } else {
                paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, soccerMatch.date().format(formatter), 30, Color.CYAN);
            }
            paintToolsService.drawTextAlignCenter(matchImage, ledBoardFont8Px, soccerMatch.home().score() + " - " + soccerMatch.away().score(), 20, Color.WHITE);
        } else {
            paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, "No match for", 8, Color.BLUE);
            paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, "id: " + screenConfig.teamId(), 18, Color.BLUE);
            paintToolsService.drawTextAlignCenter(matchImage, cgPixel5Px, screenConfig.competitionId(), 28, Color.BLUE);
        }

        return Optional.of(matchImage);
    }

    public Color getShirtColor(String color) {
        Color shirtColor = Color.decode("#" + color);
        if(shirtColor.getRed() < COLOR_THRESHOLD && shirtColor.getGreen() < COLOR_THRESHOLD && shirtColor.getBlue() < COLOR_THRESHOLD) {
            int diff = COLOR_THRESHOLD - Math.max(shirtColor.getRed(), Math.max(shirtColor.getGreen(), shirtColor.getBlue()));
            shirtColor = new Color(shirtColor.getRed() + diff, shirtColor.getGreen() + diff, shirtColor.getBlue() + diff);
        }
        return shirtColor;
    }
}
