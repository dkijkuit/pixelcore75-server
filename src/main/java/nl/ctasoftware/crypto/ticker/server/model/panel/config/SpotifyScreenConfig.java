package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Spotify Now Playing: title (scrolling marquee when long), artist, elapsed/total
 * time and a progress bar driven by the Web API's playback state, with an optional
 * 32x32 album-art thumbnail on the left half. One shared connection per server
 * (OAuth PKCE, see the Spotify service package).
 *
 * <p>{@code showAlbumArt} arrived after the first release: the delegating creator
 * accepts the older 4-field JSON shape (DB rows and older client payloads) and
 * defaults the field to on, where the strict mapper's
 * fail-on-missing-creator-properties would reject the canonical record creator
 * (same reason CustomScreenConfig parses itself).
 */
public record SpotifyScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        int frameDelayMs,
        boolean showIdleScreen,
        boolean showAlbumArt,
        boolean disabled
) implements FrameScreenConfig {

    /** Pre-art legacy shape ({@code showAlbumArt} on). */
    public SpotifyScreenConfig(final ScreenType screenType, final int durationSeconds,
                               final int frameDelayMs, final boolean showIdleScreen) {
        this(screenType, durationSeconds, frameDelayMs, showIdleScreen, true, false);
    }

    public SpotifyScreenConfig(final ScreenType screenType, final int durationSeconds,
                               final int frameDelayMs, final boolean showIdleScreen,
                               final boolean showAlbumArt) {
        this(screenType, durationSeconds, frameDelayMs, showIdleScreen, showAlbumArt, false);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static SpotifyScreenConfig fromJson(final JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("SPOTIFY_NOW_PLAYING screen config must be a JSON object");
        }
        final JsonNode type = node.get("screenType");
        if (type == null || !type.isTextual() || !"SPOTIFY_NOW_PLAYING".equals(type.asText())) {
            throw new IllegalArgumentException("screenType must be SPOTIFY_NOW_PLAYING");
        }
        final JsonNode duration = node.get("durationSeconds");
        if (duration == null || !duration.canConvertToInt() || duration.asInt() < 1) {
            throw new IllegalArgumentException("durationSeconds must be an integer >= 1");
        }
        final JsonNode frameDelay = node.get("frameDelayMs");
        if (frameDelay == null || !frameDelay.canConvertToInt()) {
            throw new IllegalArgumentException("frameDelayMs must be an integer");
        }
        final JsonNode showIdle = node.get("showIdleScreen");
        if (showIdle == null || !showIdle.isBoolean()) {
            throw new IllegalArgumentException("showIdleScreen must be a boolean");
        }
        boolean showAlbumArt = true; // field is newer than the first release: absent = on
        final JsonNode art = node.get("showAlbumArt");
        if (art != null && !art.isNull()) {
            if (!art.isBoolean()) {
                throw new IllegalArgumentException("showAlbumArt must be a boolean");
            }
            showAlbumArt = art.asBoolean();
        }
        boolean disabled = false;
        final JsonNode disabledNode = node.get("disabled");
        if (disabledNode != null && !disabledNode.isNull()) {
            if (!disabledNode.isBoolean()) {
                throw new IllegalArgumentException("disabled must be a boolean");
            }
            disabled = disabledNode.asBoolean();
        }
        return new SpotifyScreenConfig(ScreenType.SPOTIFY_NOW_PLAYING, duration.asInt(),
                frameDelay.asInt(), showIdle.asBoolean(), showAlbumArt, disabled);
    }

    @Override
    public boolean producesFrames() {
        // The marquee and progress bar animate.
        return true;
    }

    @Override
    public boolean stageAhead() {
        // The ~330KB frame loop (80 frames × 4096B) takes ~10s inline — the panel
        // drains 256B/loop — so multi-screen rotations must upload during the
        // previous slot (same reasoning as the radar). The command path avoids
        // frame uploads entirely; this covers its frame fallback. A single-screen
        // Spotify rotation still uploads inline (a slot cannot stage itself).
        return true;
    }
}
