package nl.ctasoftware.crypto.ticker.server.service.screen.date;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.DateScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
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
public class DateScreenService implements CommandScreenService<DateScreenConfig> {
    static final int PAGE_ID = 0;

    static final int CALENDAR_X = 26;
    static final int CALENDAR_Y = 5;
    static final int DATE_BASELINE = 27;

    final PaintToolsService paintToolsService;
    final Font grinched7Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");
    final BufferedImage calendarImage;

    /** The calendar glyph as RGB565 pixels (transparent &rarr; black) for the BLIT. */
    final int[] calendar565;

    /** Extracted FONT page of the date font; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage grinchedPage;

    public DateScreenService(PaintToolsService paintToolsService, Font grinched7Px) {
        this.paintToolsService = paintToolsService;
        this.grinched7Px = grinched7Px;
        try{
            this.calendarImage = ImageIO.read(new File("assets/calendar/calendar11px.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.calendar565 = Rgb565.pixels(calendarImage);
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.DATE;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final DateScreenConfig screenConfig) {
        final LocalDateTime now = now(ZoneId.systemDefault());
        final BufferedImage dateImage = paintToolsService.newImage();
        final String date = formatter.format(now);

        paintToolsService.drawImage(dateImage, calendarImage, CALENDAR_X, CALENDAR_Y);
        paintToolsService.drawTextAlignCenter(dateImage, grinched7Px, date, DATE_BASELINE, Color.decode(screenConfig.color()));

        return Optional.of(dateImage);
    }

    /**
     * Time source of both render paths; protected so parity tests can freeze the date
     * and render golden frames and the command batch from the identical timestamp.
     */
    protected LocalDateTime now(final ZoneId zone) {
        return LocalDateTime.now(zone);
    }

    /* --------------------------------------------------------------------
     * ACMD command path: black canvas + the calendar glyph blitted byte-exactly
     * (7 RGB565 colors, inside BLIT's 16-entry palette) + the date as centered
     * TEXT in the same TTF-derived font page and config color.
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final DateScreenConfig screenConfig) {
        final String date = formatter.format(now(ZoneId.systemDefault()));
        final FontPageExtractor.FontPage page = grinchedPage();

        final CommandBatch batch = CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .blit(CALENDAR_X, CALENDAR_Y, calendarImage.getWidth(), calendarImage.getHeight(), calendar565)
                .fontPage(PAGE_ID, page.glyphs());
        AcmdLayout.center(batch, page, PAGE_ID, date, DATE_BASELINE, Color.decode(screenConfig.color()));
        return batch.build();
    }

    private FontPageExtractor.FontPage grinchedPage() {
        FontPageExtractor.FontPage page = grinchedPage;
        if (page == null) {
            page = FontPageExtractor.extract(grinched7Px);
            grinchedPage = page;
        }
        return page;
    }
}
