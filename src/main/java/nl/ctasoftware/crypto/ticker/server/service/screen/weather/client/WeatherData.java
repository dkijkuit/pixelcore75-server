package nl.ctasoftware.crypto.ticker.server.service.screen.weather.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class WeatherData {
    private double latitude;
    private double longitude;
    private @JsonProperty("generationtime_ms") double generationtimeMs;
    private @JsonProperty("utc_offset_seconds") int utcOffsetSeconds;
    private String timezone;
    private @JsonProperty("timezone_abbreviation") String timezoneAbbreviation;
    private double elevation;
    private @JsonProperty("daily_units") DailyUnits dailyUnits;
    private Daily daily;

    @Data
    public static class DailyUnits {
        private String time;
        private @JsonProperty("temperature_2m_min") String temperature2mMin;
        private @JsonProperty("temperature_2m_max") String temperature2mMax;
        private @JsonProperty("weather_code") String weatherCode;
    }

    @Data
    public static class Daily {
        private List<String> time;
        private @JsonProperty("temperature_2m_min") List<Double> temperature2mMin;
        private @JsonProperty("temperature_2m_max")List<Double> temperature2mMax;
        private @JsonProperty("weather_code") List<Integer> weatherCode;
    }
}
