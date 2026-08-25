package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code showAlbumArt} arrived after the first release: configs persisted without
 * it (in the DB and in older clients' REST payloads) must keep deserializing under
 * the strict mapper (fail-on-missing/null-creator-properties) with the field
 * defaulting to on — the {@code @JsonProperty(defaultValue)} mechanism this pins.
 */
class SpotifyScreenConfigTests {

    private static final String LEGACY_JSON =
            "{\"screenType\":\"SPOTIFY_NOW_PLAYING\",\"durationSeconds\":10,"
                    + "\"frameDelayMs\":250,\"showIdleScreen\":true}";
    private static final String HIDDEN_JSON =
            "{\"screenType\":\"SPOTIFY_NOW_PLAYING\",\"durationSeconds\":10,"
                    + "\"frameDelayMs\":250,\"showIdleScreen\":true,\"showAlbumArt\":false}";

    private final ObjectMapper strict = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true);

    private final ObjectMapper plain = new ObjectMapper(); // the JPA converter's mapper

    @Test
    void legacyJsonWithoutShowAlbumArtDefaultsToOn() throws Exception {
        for (final ObjectMapper mapper : List.of(strict, plain)) {
            final SpotifyScreenConfig config = mapper.readValue(LEGACY_JSON, SpotifyScreenConfig.class);
            assertTrue(config.showAlbumArt(), "pre-art config must render with art");
            assertTrue(config.showIdleScreen());
        }
    }

    @Test
    void explicitFalseRoundTrips() throws Exception {
        for (final ObjectMapper mapper : List.of(strict, plain)) {
            final SpotifyScreenConfig config = mapper.readValue(HIDDEN_JSON, SpotifyScreenConfig.class);
            assertFalse(config.showAlbumArt());
            // serialize → deserialize keeps the value (strict mapper carries every component)
            final SpotifyScreenConfig again = mapper.readValue(
                    mapper.writeValueAsString(config), SpotifyScreenConfig.class);
            assertFalse(again.showAlbumArt());
        }
    }
}
