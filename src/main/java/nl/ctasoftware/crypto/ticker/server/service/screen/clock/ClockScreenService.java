package nl.ctasoftware.crypto.ticker.server.service.screen.clock;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
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

@Slf4j
@Service
public class ClockScreenService implements CommandScreenService<ClockScreenConfig> {

    /** ACMD font page id of the big time font (shared convention: 0 = EXEPixelPerfect@16f). */
    static final int LEDBOARD_PAGE_ID = 0;

    static final int CLOCK_FACE_X = 26;
    static final int CLOCK_FACE_Y = 5;
    static final int CLOCK_TEXT_BASELINE = 27;

    final Font ledBoardFont8Px;
    final DateTimeFormatter formatterAmPm = DateTimeFormatter.ofPattern("hh:mm a");
    final DateTimeFormatter formatter24Hr = DateTimeFormatter.ofPattern("HH:mm");
    final BufferedImage clockImage;

    /**
     * The clock face as w&times;h RGB565 pixels (transparent pixels &rarr; black) — 14
     * distinct colors after quantization, inside BLIT's 16-entry palette, so the face
     * blits byte-exactly.
     */
    final int[] clockFace565;

    /** Extracted FONT page of the time font; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage ledBoardPage;

    public ClockScreenService(final Font ledBoardFont8Px) {
        this.ledBoardFont8Px = ledBoardFont8Px;
        try {
            this.clockImage = ImageIO.read(new File("assets/clock/clock.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.clockFace565 = clockFacePixels(clockImage);
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.CLOCK;
    }

    /**
     * Time source of the render; protected so tests can freeze the clock and render
     * golden batches from a deterministic timestamp.
     */
    protected LocalDateTime now(final ZoneId zone) {
        return LocalDateTime.now(zone);
    }

    /* --------------------------------------------------------------------
     * ACMD command path: black canvas + the clock face blitted byte-exactly
     * + the time as TEXT in the same TTF-derived font page and config color.
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final ClockScreenConfig screenConfig) {
        final LocalDateTime now = now(ZoneId.of(screenConfig.timezone()));
        final String time = screenConfig.format24hr() ? formatter24Hr.format(now) : formatterAmPm.format(now);
        final FontPageExtractor.FontPage page = ledBoardPage();
        final int color = Rgb565.of(Color.decode(screenConfig.color()));

        return CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .blit(CLOCK_FACE_X, CLOCK_FACE_Y, clockImage.getWidth(), clockImage.getHeight(), clockFace565)
                .fontPage(LEDBOARD_PAGE_ID, page.glyphs())
                // ACMD TEXT y is the glyph line-box top; baseline 27 + lineTop lands the
                // glyphs on the same baseline the AWT frame path draws at.
                .text(LEDBOARD_PAGE_ID, 32 - page.width(time) / 2, CLOCK_TEXT_BASELINE + page.lineTop(),
                        color, time)
                .build();
    }

    private FontPageExtractor.FontPage ledBoardPage() {
        FontPageExtractor.FontPage page = ledBoardPage;
        if (page == null) {
            page = FontPageExtractor.extract(ledBoardFont8Px);
            ledBoardPage = page;
        }
        return page;
    }

    private static int[] clockFacePixels(final BufferedImage image) {
        final int[] pixels = new int[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = image.getRGB(x, y);
                pixels[y * image.getWidth() + x] = (argb >>> 24) < 128
                        ? AcmdMirror.BLACK
                        : Rgb565.of(new Color(argb, true));
            }
        }
        return pixels;
    }
}
