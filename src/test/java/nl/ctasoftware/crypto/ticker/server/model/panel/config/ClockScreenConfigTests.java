package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code disabled} arrived after the first release: configs persisted without it
 * (DB rows through the plain converter mapper) must keep deserializing with the
 * field defaulting to off, while the strict REST mapper round-trips an explicit
 * value — appending the component last is what makes the plain mapper lenient.
 */
class ClockScreenConfigTests {

    private static final String LEGACY_JSON =
            "{\"screenType\":\"CLOCK\",\"durationSeconds\":10,"
                    + "\"timezone\":\"Europe/Amsterdam\",\"format24hr\":true,\"color\":\"#FFFFFF\"}";
    private static final String DISABLED_JSON =
            "{\"screenType\":\"CLOCK\",\"durationSeconds\":10,"
                    + "\"timezone\":\"Europe/Amsterdam\",\"format24hr\":true,\"color\":\"#FFFFFF\","
                    + "\"disabled\":true}";

    private final ObjectMapper strict = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true);

    private final ObjectMapper plain = new ObjectMapper(); // the JPA converter's mapper

    @Test
    void legacyJsonWithoutDisabledDefaultsToFalse() throws Exception {
        final ClockScreenConfig config = plain.readValue(LEGACY_JSON, ClockScreenConfig.class);
        assertFalse(config.disabled());
        assertTrue(config.format24hr());
    }

    @Test
    void disabledTrueRoundTrips() throws Exception {
        for (final ObjectMapper mapper : List.of(strict, plain)) {
            final ClockScreenConfig config = mapper.readValue(DISABLED_JSON, ClockScreenConfig.class);
            assertTrue(config.disabled());
            final ClockScreenConfig again = mapper.readValue(
                    mapper.writeValueAsString(config), ClockScreenConfig.class);
            assertTrue(again.disabled());
        }
    }

    @Test
    void compatConstructorDefaultsToFalse() {
        final ClockScreenConfig config = new ClockScreenConfig(ScreenType.CLOCK, 10,
                "Europe/Amsterdam", true, "#FFFFFF");
        assertFalse(config.disabled());
    }
}
