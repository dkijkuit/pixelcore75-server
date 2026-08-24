package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two JSON shapes of a CUSTOM screen entry — the library reference
 * ({@code customScreenId}) and the legacy inline {@code design} — must both deserialize
 * under the strict REST mapper (fail-on-missing/null-creator-properties) and under the
 * plain converter mapper, and serialize back to the same shape (nulls omitted).
 */
class CustomScreenConfigTests {

    private static final String REF_JSON =
            "{\"screenType\":\"CUSTOM\",\"durationSeconds\":10,\"customScreenId\":7}";
    private static final String INLINE_JSON =
            "{\"screenType\":\"CUSTOM\",\"durationSeconds\":10,\"design\":\"{\\\"schemaVersion\\\":1\\\"}\"";

    private final ObjectMapper strict = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true);

    private final ObjectMapper plain = new ObjectMapper(); // the JPA converter's mapper

    @Test
    void parsesReferenceFormUnderBothMappers() throws Exception {
        for (final ObjectMapper mapper : List.of(strict, plain)) {
            final CustomScreenConfig config = mapper.readValue(REF_JSON, CustomScreenConfig.class);
            assertEquals(7L, config.customScreenId());
            assertNull(config.design());
            assertEquals(10, config.durationSeconds());
        }
    }

    @Test
    void parsesLegacyInlineFormUnderBothMappers() throws Exception {
        final String json = INLINE_JSON + "}";
        for (final ObjectMapper mapper : List.of(strict, plain)) {
            final CustomScreenConfig config = mapper.readValue(json, CustomScreenConfig.class);
            assertNull(config.customScreenId());
            assertNotNull(config.design());
        }
    }

    @Test
    void serializationOmitsTheUnsetShape() throws Exception {
        final String refOut = plain.writeValueAsString(
                plain.readValue(REF_JSON, CustomScreenConfig.class));
        assertTrue(refOut.contains("\"customScreenId\":7"), refOut);
        assertFalse(refOut.contains("design"), refOut);

        final String inlineOut = plain.writeValueAsString(
                plain.readValue(INLINE_JSON + "}", CustomScreenConfig.class));
        assertTrue(inlineOut.contains("design"), inlineOut);
        assertFalse(inlineOut.contains("customScreenId"), inlineOut);
    }

    @Test
    void roundTripsThroughThePolymorphicScreenConfigList() throws Exception {
        final String rotation = "[" + REF_JSON + "," + INLINE_JSON + "}]";
        final List<ScreenConfig> configs = plain.readValue(rotation,
                plain.getTypeFactory().constructCollectionType(List.class, ScreenConfig.class));

        assertEquals(2, configs.size());
        final CustomScreenConfig reference = (CustomScreenConfig) configs.get(0);
        final CustomScreenConfig inline = (CustomScreenConfig) configs.get(1);
        assertEquals(7L, reference.customScreenId());
        assertNull(inline.customScreenId());

        // and back: the reference entry keeps its reference shape (it is never serialized hydrated)
        final String out = plain.writeValueAsString(configs);
        assertTrue(out.contains("\"customScreenId\":7"), out);
        assertFalse(out.substring(out.indexOf("design")).contains("customScreenId"), out);
    }

    @Test
    void rejectsBothFieldsSet() {
        assertThrows(ValueInstantiationException.class, () ->
                plain.readValue("{\"screenType\":\"CUSTOM\",\"durationSeconds\":10,"
                        + "\"customScreenId\":7,\"design\":\"x\"}", CustomScreenConfig.class));
    }

    @Test
    void rejectsNeitherFieldSet() {
        assertThrows(ValueInstantiationException.class, () ->
                plain.readValue("{\"screenType\":\"CUSTOM\",\"durationSeconds\":10}", CustomScreenConfig.class));
    }

    @Test
    void constructorEnforcesExactlyOneShape() {
        assertThrows(IllegalArgumentException.class, () ->
                new CustomScreenConfig(ScreenType.CUSTOM, 10, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new CustomScreenConfig(ScreenType.CUSTOM, 10, 7L, "{}"));
    }

    @Test
    void unhydratedReferenceCannotRender() {
        final CustomScreenConfig reference = new CustomScreenConfig(ScreenType.CUSTOM, 10, 7L, null);
        assertThrows(IllegalStateException.class, reference::producesFrames);
        assertThrows(IllegalStateException.class, reference::frameDelayMs);
    }

    @Test
    void legacyInlineConstructorKeepsWorking() {
        final CustomScreenConfig inline = new CustomScreenConfig(ScreenType.CUSTOM, 10,
                "{\"schemaVersion\":1,\"name\":\"X\",\"frames\":[{\"layers\":[]}]}");
        assertNull(inline.customScreenId());
        assertNotNull(inline.design());
    }
}
