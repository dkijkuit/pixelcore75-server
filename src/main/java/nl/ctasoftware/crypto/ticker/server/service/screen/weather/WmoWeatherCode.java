package nl.ctasoftware.crypto.ticker.server.service.screen.weather;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Getter
public enum WmoWeatherCode {
    CLEAR_SKY(0, "Clear sky"),

    MAINLY_CLEAR_1(1, "Mainly clear"),
    MAINLY_CLEAR_2_PARTLY_CLOUDY(2, "Partly cloudy"),
    MAINLY_CLEAR_3_OVERCAST(3, "Overcast"),

    FOG(45, "Fog"),
    RIME_FOG(48, "Rime fog"),

    DRIZZLE_LIGHT(51, "Drizzle: Light intensity"),
    DRIZZLE_MODERATE(53, "Drizzle: Moderate intensity"),
    DRIZZLE_DENSE(55, "Drizzle: Dense intensity"),

    FREEZING_DRIZZLE_LIGHT(56, "Freezing Drizzle: Light intensity"),
    FREEZING_DRIZZLE_DENSE(57, "Freezing Drizzle: Dense intensity"),

    RAIN_SLIGHT(61, "Rain: Slight intensity"),
    RAIN_MODERATE(63, "Rain: Moderate intensity"),
    RAIN_HEAVY(65, "Rain: Heavy intensity"),

    FREEZING_RAIN_LIGHT(66, "Freezing Rain: Light intensity"),
    FREEZING_RAIN_HEAVY(67, "Freezing Rain: Heavy intensity"),

    SNOW_SLIGHT(71, "Snow fall: Slight intensity"),
    SNOW_MODERATE(73, "Snow fall: Moderate intensity"),
    SNOW_HEAVY(75, "Snow fall: Heavy intensity"),

    SNOW_GRAINS(77, "Snow grains"),

    RAIN_SHOWERS_SLIGHT(80, "Rain showers: Slight"),
    RAIN_SHOWERS_MODERATE(81, "Rain showers: Moderate"),
    RAIN_SHOWERS_VIOLENT(82, "Rain showers: Violent"),

    SNOW_SHOWERS_SLIGHT(85, "Snow showers: Slight"),
    SNOW_SHOWERS_HEAVY(86, "Snow showers: Heavy"),

    THUNDERSTORM(95, "Thunderstorm: Slight or moderate"),
    THUNDERSTORM_HAIL_SLIGHT(96, "Thunderstorm with slight hail"),
    THUNDERSTORM_HAIL_HEAVY(99, "Thunderstorm with heavy hail");

    private final int code;
    private final String description;

    private static final Map<Integer, WmoWeatherCode> codeMap = new HashMap<>();

    static {
        for (WmoWeatherCode weatherCode : WmoWeatherCode.values()) {
            codeMap.put(weatherCode.code, weatherCode);
        }
    }

    WmoWeatherCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public BufferedImage getIcon() {
        try {
            return switch (this) {
                case CLEAR_SKY, MAINLY_CLEAR_1 -> ImageIO.read(new File("assets/weather/weather_sunny.png"));
                case MAINLY_CLEAR_2_PARTLY_CLOUDY -> ImageIO.read(new File("assets/weather/weather_half_sunny.png"));
                case MAINLY_CLEAR_3_OVERCAST -> ImageIO.read(new File("assets/weather/weather_overcast.png"));
                case RIME_FOG, FOG -> ImageIO.read(new File("assets/weather/fog.png"));
                case SNOW_SHOWERS_SLIGHT, SNOW_SHOWERS_HEAVY, FREEZING_DRIZZLE_LIGHT, FREEZING_DRIZZLE_DENSE, FREEZING_RAIN_LIGHT, FREEZING_RAIN_HEAVY -> ImageIO.read(new File("assets/weather/freezing_rain.png"));
                case DRIZZLE_LIGHT -> ImageIO.read(new File("assets/weather/weather_light_drizzle.png"));
                case DRIZZLE_MODERATE, DRIZZLE_DENSE -> ImageIO.read(new File("assets/weather/weather_drizzle.png"));
                case RAIN_SLIGHT, RAIN_SHOWERS_SLIGHT -> ImageIO.read(new File("assets/weather/weather_slight_rain.png"));
                case RAIN_SHOWERS_MODERATE, RAIN_SHOWERS_VIOLENT, RAIN_MODERATE, RAIN_HEAVY -> ImageIO.read(new File("assets/weather/weather_heavy_rain.png"));
                case SNOW_SLIGHT, SNOW_MODERATE, SNOW_HEAVY, SNOW_GRAINS -> ImageIO.read(new File("assets/weather/weather_snow.png"));
                case THUNDERSTORM, THUNDERSTORM_HAIL_SLIGHT, THUNDERSTORM_HAIL_HEAVY -> ImageIO.read(new File("assets/weather/weather_thunderstorm.png"));
            };
        } catch (Exception e) {
            log.error("Failed to fetch weather icon: {}", e.getMessage());
            return getErrorImage();
        }
    }

    private BufferedImage getErrorImage() {
        BufferedImage errorImage = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = (Graphics2D)errorImage.getGraphics();
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(1, 1, 11, 11);
        g.drawLine(1, 11, 11, 1);

        return errorImage;
    }

    public static WmoWeatherCode fromCode(int code) {
        return codeMap.get(code);
    }
}