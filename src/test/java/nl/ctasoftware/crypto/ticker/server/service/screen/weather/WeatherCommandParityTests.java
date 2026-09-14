package nl.ctasoftware.crypto.ticker.server.service.screen.weather;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.WeatherScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.client.WeatherForecast;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the WEATHER command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, pinned per mocked forecast. Icons exercise several committed WMO assets (each
 * &le;6 RGB565 colors, inside BLIT's 16-entry palette).
 */
class WeatherCommandParityTests {

    private WeatherScreenService service;
    private WeatherClient client;
    private WeatherScreenConfig config;

    @BeforeEach
    void setUp() {
        final Font tinyUnicode = FontPageExtractor.loadFont(new File("assets/fonts/TinyUnicode.ttf"), 16f);
        client = mock(WeatherClient.class);
        service = new WeatherScreenService(client, tinyUnicode);
        config = new WeatherScreenConfig(ScreenType.WEATHER_FORECAST, 5, new LatLon(52.37, 4.9));
    }

    private void assertGoldenFor(final String name, final List<WeatherForecast> forecast) {
        when(client.getSevenDayForecast(config.latLon(), "auto")).thenReturn(forecast);
        CommandGolden.assertGolden(name, CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    @Test
    void fourDayForecastMatchesGolden() {
        final List<WeatherForecast> forecast = List.of(
                new WeatherForecast(java.time.LocalDate.of(2026, 8, 25), "Tuesday", WmoWeatherCode.CLEAR_SKY, 12.4, 21.6),
                new WeatherForecast(java.time.LocalDate.of(2026, 8, 26), "Wednesday", WmoWeatherCode.MAINLY_CLEAR_2_PARTLY_CLOUDY, 11.2, 19.8),
                new WeatherForecast(java.time.LocalDate.of(2026, 8, 27), "Thursday", WmoWeatherCode.RAIN_SHOWERS_MODERATE, 13.9, 20.1),
                new WeatherForecast(java.time.LocalDate.of(2026, 8, 28), "Friday", WmoWeatherCode.THUNDERSTORM, 14.0, 24.5));
        assertGoldenFor("weather-forecast", forecast);
    }

    @Test
    void snowAndFogIconsMatchGolden() {
        final List<WeatherForecast> forecast = List.of(
                new WeatherForecast(java.time.LocalDate.of(2026, 12, 21), "Monday", WmoWeatherCode.SNOW_HEAVY, -2.5, 1.0),
                new WeatherForecast(java.time.LocalDate.of(2026, 12, 22), "Tuesday", WmoWeatherCode.FOG, -1.4, 2.2),
                new WeatherForecast(java.time.LocalDate.of(2026, 12, 23), "Wednesday", WmoWeatherCode.SNOW_GRAINS, -3.1, 0.4),
                new WeatherForecast(java.time.LocalDate.of(2026, 12, 24), "Thursday", WmoWeatherCode.RIME_FOG, -4.0, -0.5));
        assertGoldenFor("weather-winter", forecast);
    }
}
