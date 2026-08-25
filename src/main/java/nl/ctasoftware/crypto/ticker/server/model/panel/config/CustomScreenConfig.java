package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.PxdDesign;

/**
 * Custom screens come in two shapes: a reference to a user library entry
 * ({@code customScreenId}, resolved to the stored design at render time so library edits
 * propagate live to every panel using it) or an inline {@code design} string (legacy
 * shape from before the library existed — still editable in place). Exactly one of the
 * two is set; Jackson accepts either through the delegating creator because the strict
 * mapper would otherwise require both to be present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        Long customScreenId,
        String design,
        boolean disabled
) implements FrameScreenConfig {

    public CustomScreenConfig {
        if ((customScreenId == null) == (design == null)) {
            throw new IllegalArgumentException(
                    "CUSTOM screen needs either customScreenId (library reference) or design (inline), exactly one");
        }
    }

    /** Legacy inline form: {@code {screenType, durationSeconds, design}}. */
    public CustomScreenConfig(final ScreenType screenType, final int durationSeconds, final String design) {
        this(screenType, durationSeconds, null, design, false);
    }

    public CustomScreenConfig(final ScreenType screenType, final int durationSeconds,
                              final Long customScreenId, final String design) {
        this(screenType, durationSeconds, customScreenId, design, false);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static CustomScreenConfig fromJson(final JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("CUSTOM screen config must be a JSON object");
        }
        final JsonNode type = node.get("screenType");
        if (type == null || !type.isTextual() || !"CUSTOM".equals(type.asText())) {
            throw new IllegalArgumentException("screenType must be CUSTOM");
        }
        final JsonNode duration = node.get("durationSeconds");
        if (duration == null || !duration.canConvertToInt() || duration.asInt() < 1) {
            throw new IllegalArgumentException("durationSeconds must be an integer >= 1");
        }
        boolean disabled = false;
        final JsonNode disabledNode = node.get("disabled");
        if (disabledNode != null && !disabledNode.isNull()) {
            if (!disabledNode.isBoolean()) {
                throw new IllegalArgumentException("disabled must be a boolean");
            }
            disabled = disabledNode.asBoolean();
        }
        final JsonNode ref = node.get("customScreenId");
        final JsonNode design = node.get("design");
        if (ref != null && !ref.isNull() && design != null && !design.isNull()) {
            throw new IllegalArgumentException("CUSTOM screen cannot carry both customScreenId and design");
        }
        if (ref != null && !ref.isNull()) {
            if (!ref.canConvertToLong() || ref.asLong() < 1) {
                throw new IllegalArgumentException("customScreenId must be a positive integer");
            }
            return new CustomScreenConfig(ScreenType.CUSTOM, duration.asInt(), ref.asLong(), null, disabled);
        }
        if (design != null && !design.isNull()) {
            if (!design.isTextual()) {
                throw new IllegalArgumentException("design must be a string");
            }
            return new CustomScreenConfig(ScreenType.CUSTOM, duration.asInt(), null, design.asText(), disabled);
        }
        throw new IllegalArgumentException("CUSTOM screen needs either customScreenId or design");
    }

    @Override
    public int frameDelayMs() {
        return parsedDesign().frameDelayMs();
    }

    @Override
    public boolean producesFrames() {
        return parsedDesign().frames().size() > 1;
    }

    @Override
    public boolean stageAhead() {
        return true;
    }

    private PxdDesign parsedDesign() {
        if (design == null) {
            throw new IllegalStateException("Custom screen " + customScreenId
                    + " is a library reference and must be hydrated before rendering");
        }
        return PxdDesign.parse(design);
    }
}
