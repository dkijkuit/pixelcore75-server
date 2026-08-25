package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.client.Formula1Client;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class Formula1ScreenService implements CommandScreenService<Formula1ScreenConfig> {
    /** ACMD font page id of the standings/labels font (shared convention: 1 = cg-pixel@5f). */
    static final int PAGE_CGPIXEL_ID = 1;

    final Formula1Client formula1Client;
    final PaintToolsService paintToolsService;
    final ImageService imageService;
    final Font cgPixel5Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM HH:mm");
    final DateTimeFormatter formatterDateOnly = DateTimeFormatter.ofPattern("dd-MM");

    /** Extracted FONT page of the calendar/standings font; computed lazily (deterministic). */
    private volatile FontPageExtractor.FontPage cgPixelPage;

    @Override
    public ScreenType getScreenType() {
        return ScreenType.FORMULA1;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final Formula1ScreenConfig screenConfig) {
        return switch (screenConfig.detailsType()) {
            case CALENDAR -> drawF1CalendarImage(screenConfig);
            case NEXT_EVENT -> drawNextEventImage(screenConfig);
            case NEXT_SESSION -> drawNextSessionImage(screenConfig);
            case STANDINGS -> drawF1StandingsImage();
        };
    }

    private Optional<BufferedImage> drawF1StandingsImage() {
        final Formula1DriverStandings driverStandings = formula1Client.getDriverStandings();
        final BufferedImage driverStandingsImage = paintToolsService.newImage();
        IntStream.range(0, 5).forEach(i -> {
            final Formula1DriverStandings.F1Driver f1Driver = driverStandings.standings().get(i);
            final Color teamColor = F1TeamUtils.constructorColor(f1Driver.teamName());
            paintToolsService.drawText(driverStandingsImage, cgPixel5Px, "" + f1Driver.ranking(), 0, (i+1) * 6, Color.ORANGE);
            paintToolsService.drawText(driverStandingsImage, cgPixel5Px, "-", 6, (i+1) * 6, Color.WHITE);
            paintToolsService.drawText(driverStandingsImage, cgPixel5Px, f1Driver.abbreviation(), 11, (i+1) * 6, teamColor);
            paintToolsService.drawTextAlignRight(driverStandingsImage, cgPixel5Px, "" + f1Driver.points(), (i+1) * 6, Color.WHITE);
        });
        return Optional.of(driverStandingsImage);
    }

    private Optional<BufferedImage> drawF1CalendarImage(final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final List<Formula1Calendar.Formula1Race> nextFormula1Races = formula1Calendar.getNextFormula1Races(4);
        final BufferedImage calendarImage = paintToolsService.newImage();
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        IntStream.range(0, nextFormula1Races.size()).forEach(index -> {
            final String countryCode = getCountryCode(nextFormula1Races.get(index).country());
            final BufferedImage flagImage = imageService.imageToBufferedImage("assets/flags/countries/" + countryCode + ".png");

            paintToolsService.drawImage(calendarImage, flagImage, 4, (index * 7) + 2);
            paintToolsService.drawText(calendarImage, cgPixel5Px, countryCode, 11, (index * 7) + 7, Color.ORANGE);
            paintToolsService.drawText(calendarImage, cgPixel5Px, nextFormula1Races.get(index).date().atZone(zoneId).format(formatterDateOnly), 36, (index * 7) + 7, Color.ORANGE);
        });

        return Optional.of(calendarImage);
    }

    private Optional<BufferedImage> drawNextEventImage(final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final BufferedImage calendarImage = paintToolsService.newImage();
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        paintToolsService.drawTextAlignCenter(calendarImage, cgPixel5Px, formula1Calendar.getNextFormula1Race().name().toUpperCase().replace("GRAND PRIX", "").trim(), 8, Color.RED);
        paintToolsService.drawTextAlignCenter(calendarImage, cgPixel5Px, "GRAND PRIX", 18, Color.ORANGE);
        paintToolsService.drawTextAlignCenter(calendarImage, cgPixel5Px, formula1Calendar.getNextFormula1Race().date().atZone(zoneId).format(formatter), 28, Color.BLUE);

        return Optional.of(calendarImage);
    }

    private Optional<BufferedImage> drawNextSessionImage(final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final BufferedImage calendarImage = paintToolsService.newImage();
        final BufferedImage f1logo = imageService.imageToBufferedImage("assets/formula1/f1logo_ori.png");
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        paintToolsService.drawImage(calendarImage, f1logo, 24, 2);
        paintToolsService.drawTextAlignCenter(calendarImage, cgPixel5Px, formula1Calendar.getNextSession().sessionName(), 18, Color.ORANGE);
        paintToolsService.drawTextAlignCenter(calendarImage, cgPixel5Px, formula1Calendar.getNextSession().dateTime().atZone(zoneId).format(formatter), 27, Color.BLUE);

        return Optional.of(calendarImage);
    }

    public String getCountryCode(final String countryName) {
        for (String iso : Locale.getISOCountries()) {
            final Locale locale = new Locale.Builder().setRegion(iso).build();
            if (locale.getDisplayCountry(Locale.ENGLISH).equalsIgnoreCase(countryName)) {
                return locale.getISO3Country(); // 2-letter ISO country code
            }
        }
        return countryName;
    }

    /* --------------------------------------------------------------------
     * ACMD command path: the four detail pages as cg-pixel FONT page + TEXT
     * with the country flags / F1 logo as BLITs (committed 5x5 / 19x7 pixel
     * assets, all inside BLIT's 16-entry RGB565 palette).
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final Formula1ScreenConfig screenConfig) {
        final FontPageExtractor.FontPage page = cgPixelPage();
        final CommandBatch batch = CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .fontPage(PAGE_CGPIXEL_ID, page.glyphs());

        switch (screenConfig.detailsType()) {
            case CALENDAR -> calendarCommands(batch, page, screenConfig);
            case NEXT_EVENT -> nextEventCommands(batch, page, screenConfig);
            case NEXT_SESSION -> nextSessionCommands(batch, page, screenConfig);
            case STANDINGS -> standingsCommands(batch, page);
        }
        return batch.build();
    }

    private void standingsCommands(final CommandBatch batch, final FontPageExtractor.FontPage page) {
        final Formula1DriverStandings driverStandings = formula1Client.getDriverStandings();
        for (int i = 0; i < 5; i++) {
            final Formula1DriverStandings.F1Driver f1Driver = driverStandings.standings().get(i);
            final Color teamColor = F1TeamUtils.constructorColor(f1Driver.teamName());
            AcmdLayout.left(batch, page, PAGE_CGPIXEL_ID, "" + f1Driver.ranking(), 0, (i + 1) * 6, Color.ORANGE);
            AcmdLayout.left(batch, page, PAGE_CGPIXEL_ID, "-", 6, (i + 1) * 6, Color.WHITE);
            AcmdLayout.left(batch, page, PAGE_CGPIXEL_ID, f1Driver.abbreviation(), 11, (i + 1) * 6, teamColor);
            AcmdLayout.right(batch, page, PAGE_CGPIXEL_ID, "" + f1Driver.points(), (i + 1) * 6, Color.WHITE);
        }
    }

    private void calendarCommands(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                  final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final List<Formula1Calendar.Formula1Race> nextFormula1Races = formula1Calendar.getNextFormula1Races(4);
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        for (int index = 0; index < nextFormula1Races.size(); index++) {
            final String countryCode = getCountryCode(nextFormula1Races.get(index).country());
            final BufferedImage flagImage = imageService.imageToBufferedImage("assets/flags/countries/" + countryCode + ".png");

            batch.blit(4, (index * 7) + 2, flagImage.getWidth(), flagImage.getHeight(), Rgb565.pixels(flagImage));
            AcmdLayout.left(batch, page, PAGE_CGPIXEL_ID, countryCode, 11, (index * 7) + 7, Color.ORANGE);
            AcmdLayout.left(batch, page, PAGE_CGPIXEL_ID,
                    nextFormula1Races.get(index).date().atZone(zoneId).format(formatterDateOnly),
                    36, (index * 7) + 7, Color.ORANGE);
        }
    }

    private void nextEventCommands(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                   final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        AcmdLayout.center(batch, page, PAGE_CGPIXEL_ID,
                formula1Calendar.getNextFormula1Race().name().toUpperCase().replace("GRAND PRIX", "").trim(),
                8, Color.RED);
        AcmdLayout.center(batch, page, PAGE_CGPIXEL_ID, "GRAND PRIX", 18, Color.ORANGE);
        AcmdLayout.center(batch, page, PAGE_CGPIXEL_ID,
                formula1Calendar.getNextFormula1Race().date().atZone(zoneId).format(formatter),
                28, Color.BLUE);
    }

    private void nextSessionCommands(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                     final Formula1ScreenConfig screenConfig) {
        final Formula1Calendar formula1Calendar = formula1Client.getFormula1Calendar();
        final BufferedImage f1logo = imageService.imageToBufferedImage("assets/formula1/f1logo_ori.png");
        final ZoneId zoneId = ZoneId.of(screenConfig.timezone());

        batch.blit(24, 2, f1logo.getWidth(), f1logo.getHeight(), Rgb565.pixels(f1logo));
        AcmdLayout.center(batch, page, PAGE_CGPIXEL_ID,
                formula1Calendar.getNextSession().sessionName(), 18, Color.ORANGE);
        AcmdLayout.center(batch, page, PAGE_CGPIXEL_ID,
                formula1Calendar.getNextSession().dateTime().atZone(zoneId).format(formatter),
                27, Color.BLUE);
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
