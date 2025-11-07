package nl.ctasoftware.crypto.ticker.server.service.screen.weather.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.WmoWeatherCode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenMeteoWeatherClient implements WeatherClient {
    final RestClient openMeteoRestClient;

    @Override
    @Cacheable("weatherForecast")
    public List<WeatherForecast> getSevenDayForecast(final LatLon latLon, final String timezone) {
        log.info("OpenMeteoWeatherClient getSevenDayForecast: {}", latLon);
        final ResponseEntity<WeatherData> weatherDataResponseEntity = openMeteoRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("forecast")
                        .queryParam("latitude", latLon.lat())
                        .queryParam("longitude", latLon.lon())
                        .queryParam("daily", "temperature_2m_min,temperature_2m_max,weather_code")
                        .queryParam("timezone", timezone)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(WeatherData.class);

        if (weatherDataResponseEntity.getStatusCode().is2xxSuccessful()) {
            final List<WeatherForecast> weatherForecasts = new ArrayList<>();
            final WeatherData weatherData = weatherDataResponseEntity.getBody();
            for (int i = 0; i < weatherData.getDaily().getWeatherCode().size(); i++) {
                final int weatherCode = weatherData.getDaily().getWeatherCode().get(i);
                final double tempMin = weatherData.getDaily().getTemperature2mMin().get(i);
                final double tempMax = weatherData.getDaily().getTemperature2mMax().get(i);
                final String date = weatherData.getDaily().getTime().get(i);

                final LocalDate localDate = LocalDate.parse(date);
                weatherForecasts.add(new WeatherForecast(localDate, localDate.getDayOfWeek().name(), WmoWeatherCode.fromCode(weatherCode), tempMin, tempMax));
            }
            return weatherForecasts;
        } else {
            log.error("Failed to fetch weather data: {}", weatherDataResponseEntity.getStatusCode(), weatherDataResponseEntity.getBody().toString());
            throw new RuntimeException("Failed to fetch weather data: " + weatherDataResponseEntity.getStatusCode());
        }
    }
}
