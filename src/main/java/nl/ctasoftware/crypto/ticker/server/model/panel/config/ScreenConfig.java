package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY, // use a real property in the JSON
        property = "screenType",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CryptoScreenConfig.class, name = "CRYPTO_TICKER"),
        @JsonSubTypes.Type(value = ImageScreenConfig.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = WeatherScreenConfig.class, name = "WEATHER_FORECAST"),
        @JsonSubTypes.Type(value = SoccerMatchScreenConfig.class, name = "SOCCER_MATCH"),
        @JsonSubTypes.Type(value = ClockScreenConfig.class, name = "CLOCK"),
        @JsonSubTypes.Type(value = DateScreenConfig.class, name = "DATE"),
        @JsonSubTypes.Type(value = Formula1ScreenConfig.class, name = "FORMULA1")
})
public sealed interface ScreenConfig permits ClockScreenConfig, CryptoScreenConfig, DateScreenConfig, Formula1ScreenConfig, ImageScreenConfig, SoccerMatchScreenConfig, WeatherScreenConfig {
    ScreenType screenType();

    int durationSeconds();
}
