package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.LatinFoldService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyAlbumArtClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Spotify Now Playing (64x32): a 32x32 album-art thumbnail filling the left half
 * with title/artist beside it (scrolling when they overflow the 30px column),
 * stacked elapsed/total time lines and a slim progress bar in the right column,
 * all driven by the Web API's playback state. Without art (fetch failed) the
 * full-width layout takes over: combined centered time line, full-width bar.
 *
 *  * <p>The ACMD command path is the screen's only encoding: a ~1KB batch (fonts + BLIT
 * art + text/scroll + the bar as FILLs) republished on a 1s render tick, with fresh
 * playback fetched only on the (slower, rate-limit-aware) refresh cadence — between
 * fetches the cached snapshot's progress is re-projected locally, so the time line and
 * bar move naturally without extra API load and no multi-second frame uploads (an
 * 80-frame inline ANIM takes ~10s at the panel's drain rate, the exact stall this
 * avoids).
 *
 * <p>Album art comes from {@link SpotifyAlbumArtClient}: downloaded once per cover
 * URL, downgraded to at most 16 dithered RGB565 colors — exactly BLIT's palette
 * capacity. Both idle and not-connected states show a generated pixel-art
 * Spotify-logo mark instead of a wordmark.
 */
@Slf4j
@Service
public class SpotifyScreenService implements CommandScreenService<SpotifyScreenConfig> {

    /** Configured live-refresh cadence (clamped to &ge; 1s: the API is rate-limited). */
    private final long refreshMs;

    static final int CANVAS_WIDTH = 64;
    static final int CANVAS_HEIGHT = 32;

    /** Album-art thumbnail (square), filling the left half; text column after a 2px gutter. */
    static final int ART_SIZE = 32;
    static final int TEXT_X_WITH_ART = ART_SIZE + 2;
    static final int TEXT_W_WITH_ART = CANVAS_WIDTH - TEXT_X_WITH_ART;

    static final int TITLE_BASELINE_Y = 9;
    static final int ARTIST_BASELINE_Y = 16;
    /** No-art layout: combined "m:ss/m:ss" line, centered on the full canvas. */
    static final int TIME_BASELINE_Y = 25;
    /** Art layout: the combined line cannot fit the 30px column, so elapsed and
     * total stack (elapsed white, total grey) above the bar, Spotify-app style. */
    static final int ELAPSED_BASELINE_Y = 22;
    static final int TOTAL_BASELINE_Y = 29;
    static final int PROGRESS_BAR_Y = 30;
    static final int PROGRESS_BAR_HEIGHT = 2;

    /* --------------------------------------------------------------------
     * ACMD command path (the radar's live-refresh pattern): font page ids are
     * convention only (batches are self-contained), shared with the other
     * command screens. 0 = EXEPixelPerfect@16f, 1 = cg-pixel@5f.
     * ------------------------------------------------------------------ */
    static final int PAGE_LEDBOARD_ID = 0;
    static final int PAGE_CGPIXEL_ID = 1;

    /** SCROLL pace for overflowing lines (ms per pixel, shared look with radar). */
    static final int SCROLL_MS_PER_PX = 120;

    /** SCROLL region heights: one text row plus clearance without eating the next row's box. */
    static final int TITLE_SCROLL_REGION_HEIGHT = 9;
    static final int ARTIST_SCROLL_REGION_HEIGHT = 7;

    /**
     * Live-refresh cadence for a playing slot (default): how often the Web API is
     * actually polled for fresh playback state. 5s keeps the API load modest (the
     * playback endpoint is rate-limited). Deployments can override it via
     * {@code pixelcore75.spotify.refresh-ms} / {@code SPOTIFY_REFRESH_MS}.
     */
    public static final long DEFAULT_REFRESH_MS = 5_000;

    /**
     * Render tick while a track plays: batches republish on this grid so the time
     * line advances second by second instead of jumping a whole refresh interval.
     * Fetches still honor {@code refreshMs} (the client's fetch budget) — ticks in
     * between reuse the cached snapshot.
     */
    public static final long RENDER_TICK_MS = 1_000;

    /** Extracted FONT pages (deterministic per TTF+size), computed on first command render. */
    private volatile FontPageExtractor.FontPage ledBoardPage;
    private volatile FontPageExtractor.FontPage cgPixelPage;

    static final Color SPOTIFY_GREEN = new Color(30, 215, 96);
    static final Color PROGRESS_TRACK = new Color(24, 24, 24);
    static final Color PAUSED_GREY = new Color(120, 120, 120);
    static final Color IDLE_GREY = new Color(90, 90, 90);

    /* --------------------------------------------------------------------
     * Idle/not-connected mark: a hand-drawn pixel-art rendering of the Spotify
     * logo (filled circle + three rising sound-wave arcs), built once per accent
     * color as a 16x16 RGB565 block the frame path paints and the command path
     * BLITs — identical pixels on both paths by construction. Deliberately NOT
     * scaled with the album art: a mark reads at 16px, cover art needs the room.
     * ------------------------------------------------------------------ */
    static final int LOGO_SIZE = 16;
    static final int LOGO_X = (CANVAS_WIDTH - LOGO_SIZE) / 2;
    static final int LOGO_Y = 1;

    /**
     * The mark, one string per row: {@code G} = circle fill, {@code .} = wave cut,
     * space = outside the circle. Three arcs, each rising to the right, peaks
     * stepping down from top-center to bottom-center — the "sound wave" glyph.
     */
    private static final String[] LOGO_ROWS = {
            "                ",
            "    GGGGGGGG    ",
            "   GGGG.....G   ",
            "  GGG...GGGG..  ",
            " GG...GGGGGGGGG ",
            " G..GGG......GG ",
            " GGGG...GGGG... ",
            " GG...GGGGGGGGG ",
            " G..GGGG....GGG ",
            " GGGGG...GG...G ",
            " GGG...GGGGGGGG ",
            " GG..GGGGGGGGGG ",
            "  GGGGGGGGGGGG  ",
            "   GGGGGGGGGG   ",
            "    GGGGGGGG    ",
            "                ",
    };

    private static final SpotifyAlbumArtClient.AlbumArt LOGO_GREEN =
            new SpotifyAlbumArtClient.AlbumArt(LOGO_SIZE,
                    spotifyLogo(Rgb565.of(SPOTIFY_GREEN)));
    private static final SpotifyAlbumArtClient.AlbumArt LOGO_GREY =
            new SpotifyAlbumArtClient.AlbumArt(LOGO_SIZE,
                    spotifyLogo(Rgb565.of(IDLE_GREY)));

    private final Font ledBoardFont8Px;
    private final Font cgPixel5Px;
    private final SpotifyPlaybackClient spotifyPlaybackClient;
    private final SpotifyAlbumArtClient spotifyAlbumArtClient;

    public SpotifyScreenService(final Font ledBoardFont8Px,
                                final Font cgPixel5Px,
                                final SpotifyPlaybackClient spotifyPlaybackClient,
                                final SpotifyAlbumArtClient spotifyAlbumArtClient,
                                @Value("${pixelcore75.spotify.refresh-ms:" + DEFAULT_REFRESH_MS + "}")
                                final long refreshMs) {
        this.ledBoardFont8Px = ledBoardFont8Px;
        this.cgPixel5Px = cgPixel5Px;
        this.spotifyPlaybackClient = spotifyPlaybackClient;
        this.spotifyAlbumArtClient = spotifyAlbumArtClient;
        this.refreshMs = Math.max(1_000, refreshMs);
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.SPOTIFY_NOW_PLAYING;
    }

    /* --------------------------------------------------------------------
     * ACMD command path: CLS + FONT pages + TEXT/SCROLL lines + the bar as
     * FILLs, republished live while a track plays. The time text and bar are
     * fixed commands re-issued per refresh; the SCROLL parametrics carry the
     * epoch across refreshes while the track (and so the scroll text) is
     * unchanged, so marquees run continuously.
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final SpotifyScreenConfig screenConfig) {
        final SpotifyPlayback playback = spotifyPlaybackClient.getCurrentlyPlaying();
        final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
        appendStateCommands(batch, playback, 0, screenConfig.showAlbumArt());
        return batch.build();
    }

    @Override
    public RefreshStream renderCommandRefresh(final SpotifyScreenConfig screenConfig) {
        final SpotifyPlayback playback = spotifyPlaybackClient.getCurrentlyPlaying();
        if (!playback.connected() || !playback.hasTrack()) {
            // Idle/not-connected/paused content has no live value — single batch.
            return null;
        }
        final CommandBatch first = CommandBatch.builder().cls(AcmdMirror.BLACK);
        appendStateCommands(first, playback, 0, screenConfig.showAlbumArt());
        return new RefreshStream(first.build(), () -> {
            // Rendered one tick before its grid point: project the progress to
            // the publish time (the radar's pipelined-page lead). The fetch
            // budget keeps the API on the configured refresh cadence — ticks in
            // between reuse the cached snapshot.
            final SpotifyPlayback next = spotifyPlaybackClient.getCurrentlyPlaying(refreshMs);
            final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
            appendStateCommands(batch, next, RENDER_TICK_MS, screenConfig.showAlbumArt());
            return batch.build();
        }, RENDER_TICK_MS);
    }

    private void appendStateCommands(final CommandBatch batch, final SpotifyPlayback playback,
                                      final long leadMs, final boolean showAlbumArt) {
        if (!playback.connected()) {
            notConnectedCommands(batch);
        } else if (!playback.hasTrack()) {
            idleCommands(batch);
        } else {
            playingCommands(batch, playback, leadMs, showAlbumArt);
        }
    }

    private void playingCommands(final CommandBatch batch, final SpotifyPlayback playback,
                                  final long leadMs, final boolean showAlbumArt) {
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());

        final SpotifyAlbumArtClient.AlbumArt art = albumArt(playback, showAlbumArt);
        if (art != null) {
            batch.blit(0, 0, art.size(), art.size(), art.rgb565());
        }
        final int textX = art != null ? TEXT_X_WITH_ART : 0;
        final int textW = art != null ? TEXT_W_WITH_ART : AcmdMirror.WIDTH;

        final Color accent = playback.playing() ? SPOTIFY_GREEN : PAUSED_GREY;
        textOrScroll(batch, ledPage, PAGE_LEDBOARD_ID,
                ascii(LatinFoldService.fold(playback.title())), textX, textW, TITLE_BASELINE_Y,
                TITLE_SCROLL_REGION_HEIGHT, Color.WHITE);
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID,
                ascii(LatinFoldService.fold(playback.artist())), textX, textW, ARTIST_BASELINE_Y,
                ARTIST_SCROLL_REGION_HEIGHT, accent);

        final long progressMs = projectedProgress(playback, leadMs);
        final FontPageExtractor.FontPage page = cgPixelPage();
        if (art != null) {
            textLine(batch, page, PAGE_CGPIXEL_ID, formatTime(progressMs), TEXT_X_WITH_ART,
                    ELAPSED_BASELINE_Y, playback.playing() ? Color.WHITE : PAUSED_GREY);
            textLine(batch, page, PAGE_CGPIXEL_ID, formatTime(playback.durationMs()), TEXT_X_WITH_ART,
                    TOTAL_BASELINE_Y, IDLE_GREY);
            batch.fill(TEXT_X_WITH_ART, PROGRESS_BAR_Y, TEXT_W_WITH_ART, PROGRESS_BAR_HEIGHT,
                    Rgb565.of(PROGRESS_TRACK));
            final int barWidth = barWidthPx(progressMs, playback.durationMs(), TEXT_W_WITH_ART);
            if (barWidth > 0) { // FILL dims are u8 1..255 — a zero-width bar is simply not drawn
                batch.fill(TEXT_X_WITH_ART, PROGRESS_BAR_Y, barWidth, PROGRESS_BAR_HEIGHT,
                        Rgb565.of(accent));
            }
        } else {
            final String timeLine = formatTime(progressMs) + "/" + formatTime(playback.durationMs());
            textLine(batch, page, PAGE_CGPIXEL_ID, timeLine,
                    AcmdMirror.WIDTH / 2 - page.width(timeLine) / 2, TIME_BASELINE_Y,
                    playback.playing() ? Color.WHITE : PAUSED_GREY);
            batch.fill(0, PROGRESS_BAR_Y, CANVAS_WIDTH, PROGRESS_BAR_HEIGHT, Rgb565.of(PROGRESS_TRACK));
            final int barWidth = barWidthPx(progressMs, playback.durationMs(), CANVAS_WIDTH);
            if (barWidth > 0) {
                batch.fill(0, PROGRESS_BAR_Y, barWidth, PROGRESS_BAR_HEIGHT, Rgb565.of(accent));
            }
        }
    }

    private void idleCommands(final CommandBatch batch) {
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());
        batch.blit(LOGO_X, LOGO_Y, LOGO_SIZE, LOGO_SIZE, LOGO_GREEN.rgb565());
        centerText(batch, cgPage, PAGE_CGPIXEL_ID, "NOT PLAYING", TIME_BASELINE_Y, IDLE_GREY);
    }

    private void notConnectedCommands(final CommandBatch batch) {
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());
        batch.blit(LOGO_X, LOGO_Y, LOGO_SIZE, LOGO_SIZE, LOGO_GREY.rgb565());
        centerText(batch, cgPage, PAGE_CGPIXEL_ID, "NOT CONNECTED", TIME_BASELINE_Y, Color.RED);
    }

    /** The playback's cover thumbnail, or null when hidden/absent/unfetchable (text-only layout). */
    private SpotifyAlbumArtClient.AlbumArt albumArt(final SpotifyPlayback playback, final boolean showAlbumArt) {
        return showAlbumArt ? spotifyAlbumArtClient.artFor(playback.albumArtUrl()) : null;
    }

    /**
     * Playback position to render {@code leadMs} into the future from the fetch:
     * the API reports progress as of {@code fetchedAt}, each refresh batch is
     * rendered one interval before its publish grid point, so without the lead
     * every refresh would trail the grid by {@code REFRESH_MS}. Frozen when
     * paused, clamped to the track length.
     */
    static long projectedProgress(final SpotifyPlayback playback, final long leadMs) {
        final long elapsedMs = Math.max(0,
                Duration.between(playback.fetchedAt(), Instant.now()).toMillis()) + leadMs;
        return progressAtFrame(playback.progressMs(), playback.playing(), elapsedMs,
                playback.durationMs());
    }

    /** TEXT at the frame path's baseline (ACMD y = glyph line-box top = baseline + lineTop). */
    private static void textLine(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                 final int pageId, final String text, final int x,
                                 final int baselineY, final Color color) {
        batch.text(pageId, x, baselineY + page.lineTop(), Rgb565.of(color), text);
    }

    private static void centerText(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                   final int pageId, final String text, final int baselineY,
                                   final Color color) {
        textLine(batch, page, pageId, text, AcmdMirror.WIDTH / 2 - page.width(text) / 2,
                baselineY, color);
    }

    /**
     * TEXT when the full string fits the region; an overflowing string becomes one
     * SCROLL of the whole, untruncated text (the panel ticks the marquee locally —
     * identical text across refreshes carries the epoch, so it runs continuously).
     */
    private static void textOrScroll(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                      final int pageId, final String text, final int x, final int regionWidth,
                                      final int baselineY, final int regionHeight, final Color color) {
        if (page.width(text) <= regionWidth) {
            textLine(batch, page, pageId, text, x, baselineY, color);
            return;
        }
        batch.scroll(x, baselineY + page.lineTop(), regionWidth, regionHeight,
                pageId, Rgb565.of(color), SCROLL_MS_PER_PX, text);
    }

    /**
     * ACMD TEXT/SCROLL payloads are ASCII 32..126 by spec; Spotify metadata is
     * free-text, so transliterate — NFD strips diacritics, anything else surviving
     * becomes {@code ?}. Sanitize before width math so fit/scroll decisions match
     * what is drawn.
     */
    private static String ascii(final String s) {
        if (s.chars().allMatch(c -> c >= 32 && c <= 126)) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length());
        Normalizer.normalize(s, Normalizer.Form.NFD).codePoints().forEach(cp -> {
            if (cp >= 32 && cp <= 126) {
                sb.append((char) cp);
            } else if (Character.getType(cp) != Character.NON_SPACING_MARK) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private FontPageExtractor.FontPage ledBoardPage() {
        FontPageExtractor.FontPage page = ledBoardPage;
        if (page == null) {
            page = FontPageExtractor.extract(ledBoardFont8Px);
            ledBoardPage = page;
        }
        return page;
    }

    private FontPageExtractor.FontPage cgPixelPage() {
        FontPageExtractor.FontPage page = cgPixelPage;
        if (page == null) {
            page = FontPageExtractor.extract(cgPixel5Px);
            cgPixelPage = page;
        }
        return page;
    }

    /* --------------------------------------------------------------------
     * Pixel-art Spotify logo (BLIT payload for idle/not-connected batches)
     * ------------------------------------------------------------------ */

    /**
     * 16&times;16 RGB565 rendering of the Spotify mark from {@link #LOGO_ROWS}:
     * at most 2 distinct colors (BLIT-safe), deterministic by construction.
     */
    static int[] spotifyLogo(final int circleRgb565) {
        final int[] px = new int[LOGO_SIZE * LOGO_SIZE];
        for (int y = 0; y < LOGO_SIZE; y++) {
            final String row = LOGO_ROWS[y];
            for (int x = 0; x < LOGO_SIZE; x++) {
                px[y * LOGO_SIZE + x] = row.charAt(x) == 'G' ? circleRgb565 : 0;
            }
        }
        return px;
    }

    /* --------------------------------------------------------------------
     * Layout math (static for tests)
     * ------------------------------------------------------------------ */

    /** Playback position to render: frozen when paused, advancing otherwise, clamped to the track length. */
    static long progressAtFrame(final long progressMs, final boolean playing, final long elapsedMs,
                                final long durationMs) {
        if (!playing) {
            return Math.max(0, Math.min(durationMs, progressMs));
        }
        return Math.max(0, Math.min(durationMs, progressMs + elapsedMs));
    }

    /** Progress bar fill width in px, 0 when the duration is unknown. */
    static int barWidthPx(final long progressMs, final long durationMs, final int canvasWidth) {
        if (durationMs <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(canvasWidth,
                Math.round(canvasWidth * progressMs / (double) durationMs)));
    }

    /** m:ss clock (minutes unpadded, seconds zero-padded). */
    static String formatTime(final long ms) {
        final long totalSeconds = Math.max(0, ms) / 1000;
        return totalSeconds / 60 + ":" + String.format(Locale.ROOT, "%02d", totalSeconds % 60);
    }
}
