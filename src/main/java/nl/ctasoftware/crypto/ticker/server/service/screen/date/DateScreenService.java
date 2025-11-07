package nl.ctasoftware.crypto.ticker.server.service.screen.date;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.DateScreenConfig;
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
public class DateScreenService implements ScreenService<DateScreenConfig> {
    final PaintToolsService paintToolsService;
    final Font grinched7Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");
    final BufferedImage calendarImage;

    public DateScreenService(PaintToolsService paintToolsService, Font grinched7Px) {
        this.paintToolsService = paintToolsService;
        this.grinched7Px = grinched7Px;
        try{
            this.calendarImage = ImageIO.read(new File("assets/calendar/calendar11px.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.DATE;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final DateScreenConfig screenConfig) {
        final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        final BufferedImage dateImage = paintToolsService.newImage();
        final String date = formatter.format(now);

        paintToolsService.drawImage(dateImage, calendarImage, 26, 5);
        paintToolsService.drawTextAlignCenter(dateImage, grinched7Px, date, 27, Color.decode(screenConfig.color()));

        return Optional.of(dateImage);
    }
}
