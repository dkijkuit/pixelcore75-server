package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.SpotifyScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.LatinFoldService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client.SpotifyPlaybackClient.SpotifyPlayback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spotify Now Playing (64x32): scrolling title, artist line, elapsed/total time
 * and a progress bar driven by the Web API's playback state.
 *
 * <p>The ACMD command path is the primary encoding while a track plays: a ~1KB
 * batch (fonts + text/scroll + the bar as FILLs) republished on a live-refresh
 * grid with a fresh playback fetch, so the bar/time tick and track changes land
 * within one refresh — no multi-second frame uploads (an 80-frame inline ANIM
 * takes ~10s at the panel's drain rate, the exact stall this avoids). The frame
 * path stays as the non-ACMD fallback: a per-slot frame loop with the progress
 * interpolated from the slot-start fetch.
 */
@Slf4j
@Service
public class SpotifyScreenService implements FrameScreenService<SpotifyScreenConfig>,
        CommandScreenService<SpotifyScreenConfig> {

    public static final int DEFAULT_FRAME_DELAY_MS = 250;

    static final int MAX_FRAMES = 200;
    static final int CANVAS_WIDTH = 64;
    static final int CANVAS_HEIGHT = 32;

    static final int TITLE_BASELINE_Y = 9;
    static final int ARTIST_BASELINE_Y = 17;
    static final int TIME_BASELINE_Y = 26;
    static final int PROGRESS_BAR_Y = 28;
    static final int PROGRESS_BAR_HEIGHT = 4;

    static final int SCROLL_PX_PER_SEC = 12;
    static final int SCROLL_GAP_PX = 12;

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
     * Live-refresh cadence for a playing slot (default): the batch (bar, time
     * line, track) re-renders from a fresh fetch and republishes on this grid.
     * 5s keeps the Web API load modest (the playback endpoint is rate-limited)
     * while the bar moves under a pixel and the time text ticks in 5s steps —
     * effectively live. Deployments can override it via
     * {@code pixelcore75.spotify.refresh-ms} / {@code SPOTIFY_REFRESH_MS}.
     */
    public static final long DEFAULT_REFRESH_MS = 5_000;

    /** Configured live-refresh cadence (clamped to &ge; 1s: the API is rate-limited). */
    private final long refreshMs;

    /** Extracted FONT pages (deterministic per TTF+size), computed on first command render. */
    private volatile FontPageExtractor.FontPage ledBoardPage;
    private volatile FontPageExtractor.FontPage cgPixelPage;

    static final Color SPOTIFY_GREEN = new Color(30, 215, 96);
    static final Color PROGRESS_TRACK = new Color(24, 24, 24);
    static final Color PAUSED_GREY = new Color(120, 120, 120);
    static final Color IDLE_GREY = new Color(90, 90, 90);

    private final PaintToolsService paintToolsService;
    private final Font ledBoardFont8Px;
    private final Font cgPixel5Px;
    private final SpotifyPlaybackClient spotifyPlaybackClient;

    public SpotifyScreenService(final PaintToolsService paintToolsService,
                                final Font ledBoardFont8Px,
                                final Font cgPixel5Px,
                                final SpotifyPlaybackClient spotifyPlaybackClient,
                                @Value("${pixelcore75.spotify.refresh-ms:" + DEFAULT_REFRESH_MS + "}")
                                final long refreshMs) {
        this.paintToolsService = paintToolsService;
        this.ledBoardFont8Px = ledBoardFont8Px;
        this.cgPixel5Px = cgPixel5Px;
        this.spotifyPlaybackClient = spotifyPlaybackClient;
        this.refreshMs = Math.max(1_000, refreshMs);
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.SPOTIFY_NOW_PLAYING;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final SpotifyScreenConfig screenConfig) {
        final List<BufferedImage> frames = renderFrames(screenConfig);
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.getFirst());
    }

    @Override
    public List<BufferedImage> renderFrames(final SpotifyScreenConfig screenConfig) {
        final SpotifyPlayback playback = spotifyPlaybackClient.getCurrentlyPlaying();

        if (!playback.connected()) {
            return screenConfig.showIdleScreen() ? staticPage(notConnectedPage()) : staticPage(blankPage());
        }
        if (!playback.hasTrack()) {
            return screenConfig.showIdleScreen() ? staticPage(idlePage()) : staticPage(blankPage());
        }
        return playingFrames(playback, screenConfig);
    }

    /** Static content only needs the protocol's 2-frame minimum (the panel loops it). */
    private static List<BufferedImage> staticPage(final BufferedImage page) {
        return List.of(page, page);
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
        appendStateCommands(batch, playback, 0);
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
        appendStateCommands(first, playback, 0);
        return new RefreshStream(first.build(), () -> {
            // Rendered one refresh interval before its grid point: project the
            // progress to the publish time (the radar's pipelined-page lead).
            final SpotifyPlayback next = spotifyPlaybackClient.getCurrentlyPlaying();
            final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
            appendStateCommands(batch, next, refreshMs);
            return batch.build();
        }, refreshMs);
    }

    private void appendStateCommands(final CommandBatch batch, final SpotifyPlayback playback,
                                     final long leadMs) {
        if (!playback.connected()) {
            notConnectedCommands(batch);
        } else if (!playback.hasTrack()) {
            idleCommands(batch);
        } else {
            playingCommands(batch, playback, leadMs);
        }
    }

    private void playingCommands(final CommandBatch batch, final SpotifyPlayback playback,
                                 final long leadMs) {
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());

        final Color accent = playback.playing() ? SPOTIFY_GREEN : PAUSED_GREY;
        textOrScroll(batch, ledPage, PAGE_LEDBOARD_ID,
                ascii(LatinFoldService.fold(playback.title())), TITLE_BASELINE_Y,
                TITLE_SCROLL_REGION_HEIGHT, Color.WHITE);
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID,
                ascii(LatinFoldService.fold(playback.artist())), ARTIST_BASELINE_Y,
                ARTIST_SCROLL_REGION_HEIGHT, accent);

        final long progressMs = projectedProgress(playback, leadMs);
        final String timeLine = formatTime(progressMs) + "/" + formatTime(playback.durationMs());
        final FontPageExtractor.FontPage page = cgPixelPage();
        textLine(batch, page, PAGE_CGPIXEL_ID, timeLine,
                AcmdMirror.WIDTH / 2 - page.width(timeLine) / 2, TIME_BASELINE_Y,
                playback.playing() ? Color.CYAN : PAUSED_GREY);

        batch.fill(0, PROGRESS_BAR_Y, CANVAS_WIDTH, PROGRESS_BAR_HEIGHT, Rgb565.of(PROGRESS_TRACK));
        final int barWidth = barWidthPx(progressMs, playback.durationMs(), CANVAS_WIDTH);
        if (barWidth > 0) { // FILL dims are u8 1..255 — a zero-width bar is simply not drawn
            batch.fill(0, PROGRESS_BAR_Y, barWidth, PROGRESS_BAR_HEIGHT, Rgb565.of(accent));
        }
    }

    private void idleCommands(final CommandBatch batch) {
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());
        centerText(batch, ledPage, PAGE_LEDBOARD_ID, "SPOTIFY", 13, SPOTIFY_GREEN);
        centerText(batch, cgPage, PAGE_CGPIXEL_ID, "NOT PLAYING", 24, IDLE_GREY);
    }

    private void notConnectedCommands(final CommandBatch batch) {
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());
        centerText(batch, ledPage, PAGE_LEDBOARD_ID, "SPOTIFY", 13, IDLE_GREY);
        centerText(batch, cgPage, PAGE_CGPIXEL_ID, "NOT CONNECTED", 24, Color.RED);
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
     * TEXT when the full string fits the canvas; an overflowing string becomes one
     * SCROLL of the whole, untruncated text (the panel ticks the marquee locally —
     * identical text across refreshes carries the epoch, so it runs continuously).
     */
    private static void textOrScroll(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                     final int pageId, final String text, final int baselineY,
                                     final int regionHeight, final Color color) {
        if (page.width(text) <= AcmdMirror.WIDTH) {
            textLine(batch, page, pageId, text, 0, baselineY, color);
            return;
        }
        batch.scroll(0, baselineY + page.lineTop(), AcmdMirror.WIDTH, regionHeight,
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


    private List<BufferedImage> playingFrames(final SpotifyPlayback playback,
                                              final SpotifyScreenConfig screenConfig) {
        final int frameDelayMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS, screenConfig.frameDelayMs());
        final long slotMillis = screenConfig.durationSeconds() * 1000L;
        final int frameCount = frameCount(slotMillis, frameDelayMs);

        // Fold before measuring: the draw path folds too, and accents can change widths.
        final String title = LatinFoldService.fold(playback.title());
        final String artist = LatinFoldService.fold(playback.artist());
        final BufferedImage probe = paintToolsService.newImage();
        final Graphics probeGraphics = probe.getGraphics();
        final int titleWidth = textWidth(probeGraphics, ledBoardFont8Px, title);
        final int artistWidth = textWidth(probeGraphics, cgPixel5Px, artist);

        // Progress base: the API reports progress as of fetchedAt; advance by the
        // fetch-to-render lag so frame 0 is current, then per frame by the delay.
        final long fetchLagMs = Math.max(0,
                Duration.between(playback.fetchedAt(), Instant.now()).toMillis());
        final String totalTime = formatTime(playback.durationMs());

        final var frames = new ArrayList<BufferedImage>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            final long frameElapsedMs = (long) i * frameDelayMs;
            final long progressMs = progressAtFrame(playback.progressMs(), playback.playing(),
                    fetchLagMs + frameElapsedMs, playback.durationMs());

            // The elapsed time ticks with the interpolated progress — baking the
            // slot-start string would freeze "1:23" on screen while the bar moves.
            final String timeLine = formatTime(progressMs) + "/" + totalTime;

            final BufferedImage image = paintToolsService.newImage();
            paintToolsService.drawText(image, ledBoardFont8Px, title,
                    marqueeX(frameElapsedMs, titleWidth, CANVAS_WIDTH), TITLE_BASELINE_Y, Color.WHITE);
            paintToolsService.drawText(image, cgPixel5Px, artist,
                    marqueeX(frameElapsedMs, artistWidth, CANVAS_WIDTH), ARTIST_BASELINE_Y,
                    playback.playing() ? SPOTIFY_GREEN : PAUSED_GREY);
            paintToolsService.drawTextAlignCenter(image, cgPixel5Px, timeLine, TIME_BASELINE_Y,
                    playback.playing() ? Color.CYAN : PAUSED_GREY);
            drawProgressBar(image, progressMs, playback.durationMs(), playback.playing());
            frames.add(image);
        }
        return frames;
    }

    private void drawProgressBar(final BufferedImage image, final long progressMs, final long durationMs,
                                 final boolean playing) {
        final Graphics g = image.getGraphics();
        g.setColor(PROGRESS_TRACK);
        g.fillRect(0, PROGRESS_BAR_Y, CANVAS_WIDTH, PROGRESS_BAR_HEIGHT);
        g.setColor(playing ? SPOTIFY_GREEN : PAUSED_GREY);
        g.fillRect(0, PROGRESS_BAR_Y, barWidthPx(progressMs, durationMs, CANVAS_WIDTH), PROGRESS_BAR_HEIGHT);
    }

    private BufferedImage idlePage() {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawTextAlignCenter(image, ledBoardFont8Px, "SPOTIFY", 13, SPOTIFY_GREEN);
        paintToolsService.drawTextAlignCenter(image, cgPixel5Px, "NOT PLAYING", 24, IDLE_GREY);
        return image;
    }

    private BufferedImage notConnectedPage() {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawTextAlignCenter(image, ledBoardFont8Px, "SPOTIFY", 13, IDLE_GREY);
        paintToolsService.drawTextAlignCenter(image, cgPixel5Px, "NOT CONNECTED", 24, Color.RED);
        return image;
    }

    private BufferedImage blankPage() {
        return paintToolsService.newImage();
    }

    /* --------------------------------------------------------------------
     * Layout math (static for tests)
     * ------------------------------------------------------------------ */

    /** Frames for a slot: the slot length at the playback delay, clamped to the wire range. */
    static int frameCount(final long slotMillis, final int frameDelayMs) {
        final int delay = Math.max(1, frameDelayMs);
        return (int) Math.max(2, Math.min(MAX_FRAMES, slotMillis / delay));
    }

    /**
     * Marquee x for a text of {@code textWidth} px at {@code elapsedMs} into the loop:
     * 0 (static, left-aligned) when it fits the canvas; otherwise it slides left until
     * fully out, then re-enters from the right after a gap — both wrap extremes are
     * off-canvas, so the modulo seam is invisible.
     */
    static int marqueeX(final long elapsedMs, final int textWidth, final int canvasWidth) {
        if (textWidth <= canvasWidth) {
            return 0;
        }
        final int cycle = textWidth + canvasWidth + SCROLL_GAP_PX;
        final int offset = (int) ((elapsedMs * SCROLL_PX_PER_SEC / 1000) % cycle);
        return offset <= textWidth ? -offset : cycle - offset;
    }

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

    private static int textWidth(final Graphics g, final Font font, final String text) {
        return (int) g.getFontMetrics(font).getStringBounds(text, g).getWidth();
    }
}
