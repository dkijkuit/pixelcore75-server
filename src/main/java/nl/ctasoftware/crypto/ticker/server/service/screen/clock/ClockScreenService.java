package nl.ctasoftware.crypto.ticker.server.service.screen.clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
public class ClockScreenService implements ScreenService<ClockScreenConfig> {
    final PaintToolsService paintToolsService;
    final Font ledBoardFont8Px;
    final DateTimeFormatter formatterAmPm = DateTimeFormatter.ofPattern("hh:mm a");
    final DateTimeFormatter formatter24Hr = DateTimeFormatter.ofPattern("HH:mm");
    final BufferedImage clockImage;

    public ClockScreenService(final PaintToolsService paintToolsService, final Font ledBoardFont8Px) {
        this.paintToolsService = paintToolsService;
        this.ledBoardFont8Px = ledBoardFont8Px;
        try{
            this.clockImage = ImageIO.read(new File("assets/clock/clock.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.CLOCK;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final ClockScreenConfig screenConfig) {
        final LocalDateTime now = LocalDateTime.now(ZoneId.of(screenConfig.timezone()));
        final BufferedImage timeImage = paintToolsService.newImage();
        final String time = screenConfig.format24hr() ? formatter24Hr.format(now) : formatterAmPm.format(now);

        paintToolsService.drawImage(timeImage, clockImage, 26, 5);
        paintToolsService.drawTextAlignCenter(timeImage, ledBoardFont8Px, time, 27, Color.decode(screenConfig.color()));

        return Optional.of(timeImage);
    }
}
