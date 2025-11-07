package nl.ctasoftware.crypto.ticker.server.service.screen.weather.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.WmoWeatherCode;

import java.time.LocalDate;

public record WeatherForecast(LocalDate date, String day, WmoWeatherCode weatherCode, double tempMin, double tempMax) {
}
