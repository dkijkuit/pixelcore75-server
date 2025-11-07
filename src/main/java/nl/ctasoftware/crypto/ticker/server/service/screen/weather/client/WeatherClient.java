package nl.ctasoftware.crypto.ticker.server.service.screen.weather.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;

import java.util.List;

public interface WeatherClient {
    List<WeatherForecast> getSevenDayForecast(LatLon latLon, String timezone);
}
