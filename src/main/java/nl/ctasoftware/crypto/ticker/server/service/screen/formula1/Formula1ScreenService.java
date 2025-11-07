package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Formula1ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
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
public class Formula1ScreenService implements ScreenService<Formula1ScreenConfig> {
    final Formula1Client formula1Client;
    final PaintToolsService paintToolsService;
    final ImageService imageService;
    final Font cgPixel5Px;
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM HH:mm");
    final DateTimeFormatter formatterDateOnly = DateTimeFormatter.ofPattern("dd-MM");

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
}
