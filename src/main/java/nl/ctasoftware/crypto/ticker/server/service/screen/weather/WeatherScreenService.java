package nl.ctasoftware.crypto.ticker.server.service.screen.weather;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.WeatherScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherForecast;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

@Slf4j
@Service
public class WeatherScreenService implements CommandScreenService<WeatherScreenConfig> {
    static final int PAGE_ID = 0;

    static final int COLUMN_WIDTH = 16;
    static final int DAY_BASELINE = 19;
    static final int TEMP_MAX_BASELINE = 25;
    static final int TEMP_MIN_BASELINE = 31;

    final WeatherClient weatherClient;
    final Font tinyUnicode8Px;

    /** Extracted FONT page of the forecast font; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage tinyUnicodePage;

    public WeatherScreenService(final WeatherClient weatherClient, Font tinyUnicode8Px) {
        this.weatherClient = weatherClient;
        this.tinyUnicode8Px = tinyUnicode8Px;
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.WEATHER_FORECAST;
    }

    /* --------------------------------------------------------------------
     * ACMD command path: the same four forecast cells as BLIT + TEXT — the
     * WMO pixel icons are committed assets (2..6 RGB565 colors each, inside
     * BLIT's 16-entry palette).
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final WeatherScreenConfig screenConfig) {
        log.info("Loading weather: {}", screenConfig.latLon());

        final List<WeatherForecast> sevenDayForecast = weatherClient.getSevenDayForecast(screenConfig.latLon(), "auto");
        log.info("First 7 days weather: {}", sevenDayForecast);

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
