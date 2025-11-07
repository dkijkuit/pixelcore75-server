package nl.ctasoftware.crypto.ticker.server.service.screen.weather;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.WeatherScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherForecast;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class WeatherScreenService implements ScreenService<WeatherScreenConfig> {
    final WeatherClient weatherClient;
    final PaintToolsService paintToolsService;
    final Font tinyUnicode8Px;

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
            graphics.drawImage(weatherForecast.weatherCode().getIcon(), i * 16, 0, null);
            paintToolsService.drawText(image, tinyUnicode8Px, weatherForecast.day().substring(0, 3), (i * 16), 19, Color.GREEN);
            paintToolsService.drawText(image, tinyUnicode8Px, Math.round(weatherForecast.tempMax()) + "C", (i * 16), 25, Color.RED);
            paintToolsService.drawText(image, tinyUnicode8Px, Math.round(weatherForecast.tempMin()) + "C", (i * 16), 31, Color.CYAN);
        }

        return Optional.of(image);
    }
}
