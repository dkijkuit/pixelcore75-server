package nl.ctasoftware.crypto.ticker.server.service.screen.weather;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.WeatherScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherForecast;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class WeatherScreenService implements CommandScreenService<WeatherScreenConfig> {
    static final int PAGE_ID = 0;

    static final int COLUMN_WIDTH = 16;
    static final int DAY_BASELINE = 19;
    static final int TEMP_MAX_BASELINE = 25;
    static final int TEMP_MIN_BASELINE = 31;

    final WeatherClient weatherClient;
    final PaintToolsService paintToolsService;
    final Font tinyUnicode8Px;

    /** Extracted FONT page of the forecast font; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage tinyUnicodePage;

    public WeatherScreenService(final WeatherClient weatherClient, PaintToolsService paintToolsService, Font tinyUnicode8Px) {
        this.weatherClient = weatherClient;
        this.paintToolsService = paintToolsService;
        this.tinyUnicode8Px = tinyUnicode8Px;
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.WEATHER_FORECAST;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final WeatherScreenConfig screenConfig) {
        log.info("Loading weather: {}", screenConfig.latLon());

        final List<WeatherForecast> sevenDayForecast = weatherClient.getSevenDayForecast(screenConfig.latLon(), "auto");
        final BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        final Graphics graphics = image.getGraphics();

        log.info("First 7 days weather: {}", sevenDayForecast);

        for (int i = 0; i < 4; i++) {
            final WeatherForecast weatherForecast = sevenDayForecast.get(i);
            graphics.drawImage(weatherForecast.weatherCode().getIcon(), i * COLUMN_WIDTH, 0, null);
            paintToolsService.drawText(image, tinyUnicode8Px, weatherForecast.day().substring(0, 3), (i * COLUMN_WIDTH), DAY_BASELINE, Color.GREEN);
            paintToolsService.drawText(image, tinyUnicode8Px, Math.round(weatherForecast.tempMax()) + "C", (i * COLUMN_WIDTH), TEMP_MAX_BASELINE, Color.RED);
            paintToolsService.drawText(image, tinyUnicode8Px, Math.round(weatherForecast.tempMin()) + "C", (i * COLUMN_WIDTH), TEMP_MIN_BASELINE, Color.CYAN);
        }

        return Optional.of(image);
    }

    /* --------------------------------------------------------------------
     * ACMD command path: the same four forecast cells as BLIT + TEXT — the
     * WMO pixel icons are committed assets (2..6 RGB565 colors each, inside
     * BLIT's 16-entry palette; a >16-color icon would fail the build and the
     * job falls back to this frame path).
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final WeatherScreenConfig screenConfig) {
        final List<WeatherForecast> sevenDayForecast = weatherClient.getSevenDayForecast(screenConfig.latLon(), "auto");
        final FontPageExtractor.FontPage page = tinyUnicodePage();

        final CommandBatch batch = CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .fontPage(PAGE_ID, page.glyphs());
        for (int i = 0; i < 4; i++) {
            final WeatherForecast weatherForecast = sevenDayForecast.get(i);
            final BufferedImage icon = weatherForecast.weatherCode().getIcon();
            batch.blit(i * COLUMN_WIDTH, 0, icon.getWidth(), icon.getHeight(), Rgb565.pixels(icon));
            AcmdLayout.left(batch, page, PAGE_ID, weatherForecast.day().substring(0, 3),
                    i * COLUMN_WIDTH, DAY_BASELINE, Color.GREEN);
            AcmdLayout.left(batch, page, PAGE_ID, Math.round(weatherForecast.tempMax()) + "C",
                    i * COLUMN_WIDTH, TEMP_MAX_BASELINE, Color.RED);
            AcmdLayout.left(batch, page, PAGE_ID, Math.round(weatherForecast.tempMin()) + "C",
                    i * COLUMN_WIDTH, TEMP_MIN_BASELINE, Color.CYAN);
        }
        return batch.build();
    }

    private FontPageExtractor.FontPage tinyUnicodePage() {
        FontPageExtractor.FontPage page = tinyUnicodePage;
        if (page == null) {
            page = FontPageExtractor.extract(tinyUnicode8Px);
            tinyUnicodePage = page;
        }
        return page;
    }
}
