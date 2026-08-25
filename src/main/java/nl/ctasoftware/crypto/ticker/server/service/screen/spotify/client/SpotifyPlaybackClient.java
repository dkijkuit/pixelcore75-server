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
import java.time.Instant;
import java.util.List;

/**
 * Reads the shared connection's current playback state from the Spotify Web API.
 * Called on the live-refresh grid while a slot displays (see
 * {@code pixelcore75.spotify.refresh-ms}). Degradation: a transport failure keeps
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

    private final RestClient spotifyApiRestClient;
    private final SpotifyOAuthService spotifyOAuthService;
    private final Clock clock;

    private volatile SpotifyPlayback lastGood;
    private volatile Instant throttledUntil;

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
            long progressMs,
            long durationMs,
            Instant fetchedAt) {

        public boolean hasTrack() {
            return title != null && !title.isBlank();
        }

        public static SpotifyPlayback notConnected() {
            return new SpotifyPlayback(false, false, null, null, 0, 0, Instant.now());
        }

        public static SpotifyPlayback idle() {
            return new SpotifyPlayback(true, false, null, null, 0, 0, Instant.now());
        }
    }

    public SpotifyPlayback getCurrentlyPlaying() {
        // 429 backoff: while Spotify asked us to wait, no API traffic at all —
        // display holds the last good snapshot (or idle) until the window closes.
        final Instant until = throttledUntil;
        if (until != null && clock.instant().isBefore(until)) {
            return lastGoodSnapshot();
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
        if (outcome.playback().hasTrack()) {
            lastGood = outcome.playback();
        }
        return outcome.playback();
    }

    private record FetchOutcome(SpotifyPlayback playback, boolean tokenRejected) {
    }

    private SpotifyPlayback lastGoodSnapshot() {
        final SpotifyPlayback fallback = lastGood;
        return fallback != null ? fallback : SpotifyPlayback.idle();
    }

    private FetchOutcome fetchWithToken(final String accessToken) {
        try {
            final ResponseEntity<SpotifyPlayerResponse> response = spotifyApiRestClient.get()
                    .uri(b -> b.path("/me/player").queryParam("additional_types", "track").build())
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
        final String artist = item.artists() == null ? "" : item.artists().stream()
                .map(SpotifyArtist::name)
                .filter(n -> n != null && !n.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return new SpotifyPlayback(
                true,
                body.isPlaying(),
                item.name(),
                artist,
                body.progressMs() != null ? body.progressMs() : 0,
                item.durationMs(),
                Instant.now());
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
            @JsonProperty("duration_ms") long durationMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyArtist(@JsonProperty("name") String name) {
    }
}
