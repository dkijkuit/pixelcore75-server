package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reads the shared connection's current playback state from the Spotify Web API.
 * Called on the live-refresh grid while a slot displays (see
 * {@code pixelcore75.spotify.refresh-ms}). Served positions run on a monotone
 * local clock anchored to the fetches: an agreeing fetch (same track, within
 * {@value #SMOOTH_TOLERANCE_MS} ms of the projection) never moves the position;
 * slow drift slews back gradually; only a seek beyond {@value #SLEW_BAND_MS} ms,
 * a track change or a pause re-anchors outright. Degradation: a
 * transport failure keeps
 * the last good snapshot (progress frozen), a 401 forces one token refresh +
 * retry, a 429 backs off for the {@code Retry-After} window (skipping ALL API
 * traffic — the refresh grid must not hammer an endpoint Spotify already
 * throttled), and a dropped connection (refresh rejected) degrades to the
 * not-connected card.
 */
@Slf4j
@Service
public class SpotifyPlaybackClient {

    /** Fallback backoff (s) when a 429 carries no/garbled {@code Retry-After}. */
    static final long DEFAULT_RETRY_AFTER_SECONDS = 5;

    /** Ceiling (s) for the backoff: a garbled header must not wedge the screen for minutes. */
    static final long MAX_RETRY_AFTER_SECONDS = 60;

    /**
     * Max drift between a fresh fetch and the locally projected position before
     * the anchor reacts at all: Spotify's {@code progress_ms} is quantized and
     * lags the request by the round-trip, so small disagreements are noise the local
     * clock should absorb. Compared at request-start time, so round-trip jitter
     * never counts as drift.
     */
    static final long SMOOTH_TOLERANCE_MS = 2_000;

    /**
     * Drift beyond the tolerance but inside this band slews the anchor gradually
     * (buffering stalls and clock skew accumulate slowly; a snap here is exactly
     * the glitch this anchor exists to prevent). Beyond the band the fetch is
     * treated as a deliberate seek — the position steps immediately (podcast
     * ±15s skips land out here and must be instant).
     */
    static final long SLEW_BAND_MS = 10_000;

    /** Max anchor correction per fetch while slewing (ms) — imperceptible in m:ss. */
    static final long SLEW_STEP_MS = 500;

    private final RestClient spotifyApiRestClient;
    private final SpotifyOAuthService spotifyOAuthService;
    private final Clock clock;

    private volatile SpotifyPlayback lastGood;
    private volatile Instant throttledUntil;

    /** Last definitive outcome (track OR idle) and when it was fetched — the fetch-budget cache. */
    private volatile SpotifyPlayback lastOutcome;
    private volatile Instant lastOutcomeAt;

    /**
     * Monotone playback clock anchor: fetches that merely agree with the local
     * projection keep it, so served positions never move backwards.
     */
    private record PlaybackAnchor(String title, long durationMs, boolean playing, long progressMs, Instant at) {
    }

    private volatile PlaybackAnchor anchor;

    public SpotifyPlaybackClient(@Qualifier("spotifyApiRestClient") final RestClient spotifyApiRestClient,
                                 final SpotifyOAuthService spotifyOAuthService,
                                 final Clock clock) {
        this.spotifyApiRestClient = spotifyApiRestClient;
        this.spotifyOAuthService = spotifyOAuthService;
        this.clock = clock;
    }

    /** Resolved playback state; progressMs is "as of fetchedAt". */
    public record SpotifyPlayback(
            boolean connected,
            boolean playing,
            String title,
            String artist,
            String albumArtUrl,
            long progressMs,
            long durationMs,
            Instant fetchedAt) {

        public boolean hasTrack() {
            return title != null && !title.isBlank();
        }

        public static SpotifyPlayback notConnected() {
            return new SpotifyPlayback(false, false, null, null, null, 0, 0, Instant.now());
        }

        public static SpotifyPlayback idle() {
            return new SpotifyPlayback(true, false, null, null, null, 0, 0, Instant.now());
        }
    }

    public SpotifyPlayback getCurrentlyPlaying() {
        return getCurrentlyPlaying(0);
    }

    /**
     * Playback with a fetch budget: a definitive outcome (track or idle) younger
     * than {@code maxAgeMs} is returned as-is — progress projection covers the
     * time elapsed since {@code fetchedAt} — and only an older snapshot triggers
     * a real API call. Lets a render grid tick faster (a 1 s time line) than the
     * rate-limited playback endpoint may be polled.
     */
    public SpotifyPlayback getCurrentlyPlaying(final long maxAgeMs) {
        // 429 backoff: while Spotify asked us to wait, no API traffic at all —
        // display holds the last good snapshot (or idle) until the window closes.
        final Instant until = throttledUntil;
        if (until != null && clock.instant().isBefore(until)) {
            return lastGoodSnapshot();
        }

        final SpotifyPlayback cached = lastOutcome;
        if (maxAgeMs > 0 && cached != null
                && clock.instant().isBefore(lastOutcomeAt.plusMillis(maxAgeMs))) {
            return cached.hasTrack() ? anchoredSnapshot(cached) : cached;
        }

        String token = spotifyOAuthService.getAccessToken();
        if (token == null) {
            if (!spotifyOAuthService.hasConnection()) {
                lastGood = null;
                return SpotifyPlayback.notConnected();
            }
            // Stored connection but no token right now (unconfigured process,
            // refresh outage): the last good snapshot beats a false "not connected".
            final SpotifyPlayback fallback = lastGood;
            return fallback != null ? fallback : SpotifyPlayback.idle();
        }

        final Instant requestedAt = clock.instant();
        FetchOutcome outcome = fetchWithToken(token);
        if (outcome == null) {
            return lastGoodSnapshot();
        }
        if (outcome.tokenRejected()) {
            token = spotifyOAuthService.forceRefresh();
            outcome = token == null ? null : fetchWithToken(token);
        }
        if (outcome == null) {
            return lastGoodSnapshot();
        }
        final SpotifyPlayback definitive = outcome.playback();
        if (definitive.hasTrack()) {
            lastGood = definitive;
            updateAnchor(definitive, requestedAt);
        } else {
            anchor = null;
        }
        lastOutcome = definitive;
        lastOutcomeAt = clock.instant();
        return definitive.hasTrack() ? anchoredSnapshot(definitive) : definitive;
    }    private record FetchOutcome(SpotifyPlayback playback, boolean tokenRejected) {
    }

    private SpotifyPlayback lastGoodSnapshot() {
        final SpotifyPlayback fallback = lastGood;
        return fallback != null ? fallback : SpotifyPlayback.idle();
    }

    /**
     * Anchor update on a definitive fetch, diffed at request-start time (the
     * fetched position describes that moment, not the response's arrival):
     * a fetch agreeing with the locally projected position (same track, same
     * play state, within {@value #SMOOTH_TOLERANCE_MS} ms) keeps the anchor —
     * the local clock is the better estimate of "now" than the latency-lagged
     * API value. Moderate disagreement (up to {@value #SLEW_BAND_MS} ms — slow
     * drift from buffering stalls or clock skew) slews the anchor toward the
     * fetched position by at most {@value #SLEW_STEP_MS} ms per fetch, so it
     * converges without a visible snap. Anything else (a seek beyond the band,
     * track change, pause/resume, first fetch) re-anchors outright.
     */
    private void updateAnchor(final SpotifyPlayback fetched, final Instant requestedAt) {
        final PlaybackAnchor a = anchor;
        if (a != null && a.playing() && fetched.playing()
                && a.title().equals(fetched.title())
                && a.durationMs() == fetched.durationMs()) {
            final long projected = anchorProjection(a, requestedAt);
            final long diff = fetched.progressMs() - projected;
            if (Math.abs(diff) <= SMOOTH_TOLERANCE_MS) {
                return;
            }
            if (Math.abs(diff) <= SLEW_BAND_MS) {
                final long correction = Math.max(-SLEW_STEP_MS, Math.min(SLEW_STEP_MS, diff));
                anchor = new PlaybackAnchor(a.title(), a.durationMs(), true,
                        projected + correction, requestedAt);
                return;
            }
        }
        anchor = new PlaybackAnchor(fetched.title(), fetched.durationMs(), fetched.playing(),
                fetched.progressMs(), requestedAt);
    }

    /** The anchor projected to now: the smooth, monotone position to display. */
    private SpotifyPlayback anchoredSnapshot(final SpotifyPlayback fetched) {
        final PlaybackAnchor a = anchor;
        if (a == null || !a.title().equals(fetched.title()) || a.durationMs() != fetched.durationMs()) {
            return fetched; // nothing smooth to serve for this snapshot
        }
        final long projected = Math.max(0,
                Math.min(fetched.durationMs(), anchorProjection(a, clock.instant())));
        return new SpotifyPlayback(fetched.connected(), a.playing(), fetched.title(), fetched.artist(),
                fetched.albumArtUrl(), projected, fetched.durationMs(), clock.instant());
    }

    private static long anchorProjection(final PlaybackAnchor a, final Instant now) {
        return a.playing() ? a.progressMs() + Math.max(0, Duration.between(a.at(), now).toMillis())
                : a.progressMs();
    }

    private FetchOutcome fetchWithToken(final String accessToken) {
        try {
            final ResponseEntity<SpotifyPlayerResponse> response = spotifyApiRestClient.get()
                    // Episodes must be requested too, or Spotify omits the item for
                    // podcast playback entirely (looks like "not playing").
                    .uri(b -> b.path("/me/player").queryParam("additional_types", "track,episode").build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(SpotifyPlayerResponse.class);
            // 204 No Content = nothing active on any device.
            if (response.getBody() == null) {
                return new FetchOutcome(SpotifyPlayback.idle(), false);
            }
            return new FetchOutcome(toPlayback(response.getBody()), false);
        } catch (final HttpClientErrorException.Unauthorized e) {
            return new FetchOutcome(null, true);
        } catch (final HttpClientErrorException.TooManyRequests e) {
            final long retryAfter = retryAfterSeconds(e.getResponseHeaders());
            throttledUntil = clock.instant().plusSeconds(retryAfter);
            log.warn("Spotify playback rate-limited (429); backing off for {} s", retryAfter);
            return null;
        } catch (final RestClientException e) {
            log.warn("Spotify playback fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * {@code Retry-After} as delta-seconds (Spotify's format), clamped to
     * 1..{@value #MAX_RETRY_AFTER_SECONDS}; the {@value #DEFAULT_RETRY_AFTER_SECONDS} s
     * default covers a missing or non-numeric header.
     */
    static long retryAfterSeconds(final HttpHeaders headers) {
        if (headers != null) {
            final String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (value != null) {
                try {
                    return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, Long.parseLong(value.trim())));
                } catch (final NumberFormatException ignored) {
                    // HTTP-date form or garbage — fall through to the default.
                }
            }
        }
        return DEFAULT_RETRY_AFTER_SECONDS;
    }

    private static SpotifyPlayback toPlayback(final SpotifyPlayerResponse body) {
        final SpotifyTrack item = body.item();
        if (item == null || item.name() == null || item.name().isBlank()) {
            // No track context (e.g. an ad on a free account) — treat as idle.
            return SpotifyPlayback.idle();
        }
        final String artist = artistLine(item);
        return new SpotifyPlayback(
                true,
                body.isPlaying(),
                item.name(),
                artist,
                albumArtUrl(item),
                body.progressMs() != null ? body.progressMs() : 0,
                item.durationMs(),
                Instant.now());
    }

    /** Tracks carry album covers; episodes carry their show's artwork instead. */
    private static String albumArtUrl(final SpotifyTrack item) {
        final List<SpotifyImage> images = item.album() != null && item.album().images() != null
                ? item.album().images()
                : item.show() != null && item.show().images() != null ? item.show().images() : List.of();
        // The smallest image is plenty for a 16x16 thumbnail (usually Spotify's 64px).
        return images.stream()
                .filter(i -> i.url() != null)
                .min(java.util.Comparator.comparingInt(i -> i.width() == null ? Integer.MAX_VALUE : i.width()))
                .map(SpotifyImage::url)
                .orElse(null);
    }

    /** Tracks list their artists; an episode (podcast) carries its show instead. */
    private static String artistLine(final SpotifyTrack item) {
        if (item.artists() != null) {
            final String joined = item.artists().stream()
                    .map(SpotifyArtist::name)
                    .filter(n -> n != null && !n.isBlank())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            if (!joined.isBlank()) {
                return joined;
            }
        }
        return item.show() != null && item.show().name() != null ? item.show().name() : "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyPlayerResponse(
            @JsonProperty("is_playing") boolean isPlaying,
            @JsonProperty("progress_ms") Long progressMs,
            @JsonProperty("item") SpotifyTrack item) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyTrack(
            @JsonProperty("name") String name,
            @JsonProperty("artists") List<SpotifyArtist> artists,
            @JsonProperty("duration_ms") long durationMs,
            @JsonProperty("album") SpotifyAlbum album,
            @JsonProperty("show") SpotifyShow show) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyAlbum(
            @JsonProperty("images") List<SpotifyImage> images) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyImage(
            @JsonProperty("url") String url,
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyArtist(@JsonProperty("name") String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyShow(
            @JsonProperty("name") String name,
            @JsonProperty("images") List<SpotifyImage> images) {
    }
}
