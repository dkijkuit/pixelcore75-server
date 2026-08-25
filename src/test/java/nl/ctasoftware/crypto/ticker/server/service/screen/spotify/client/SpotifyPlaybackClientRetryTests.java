package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 429 backoff for the playback client: a rate-limited response must parse
 * {@code Retry-After} and skip ALL API traffic until it elapses — the refresh
 * grid (2s in aggressive deployments) must not hammer an endpoint Spotify has
 * already throttled. Display degrades to the last good snapshot meanwhile.
 *
 * <p>All expectations are declared up front (the ordered expectation manager
 * refuses additions once requests have run); the mocked clock advances between
 * calls to walk in/out of the backoff window.
 */
class SpotifyPlaybackClientRetryTests {

    private static final String PLAYER_URL = "https://api.spotify.com/v1/me/player?additional_types=track";
    private static final String PLAYING_BODY =
            "{\"is_playing\":true,\"progress_ms\":60000,\"item\":"
                    + "{\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}],\"duration_ms\":240000}}";

    /** Settable clock: every instant() read returns {@code now} — no call-index accounting. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(final Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private SpotifyOAuthService oauth;
    private MockRestServiceServer server;
    private MutableClock clock;
    private SpotifyPlaybackClient client;

    @BeforeEach
    void setUp() {
        oauth = mock(SpotifyOAuthService.class);
        when(oauth.getAccessToken()).thenReturn("token");
        when(oauth.hasConnection()).thenReturn(true);
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.spotify.com/v1/");
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        client = new SpotifyPlaybackClient(builder.build(), oauth, clock);
    }

    private void respond(final int status, final String retryAfter, final String body) {
        final var response = withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        server.expect(requestTo(PLAYER_URL)).andRespond(body != null ? response.body(body) : response);
    }

    @Test
    void rateLimitSkipsAllFetchesUntilRetryAfterElapses() {
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        clock.now = t0;
        respond(429, "10", null); // request 1
        respond(204, null, null); // request 2 (after the window)

        assertFalse(client.getCurrentlyPlaying().hasTrack(), "throttled fetch degrades to idle");

        // t0+5: still inside the Retry-After window — this call must NOT reach the
        // API (the mock server fails the test on any unexpected request).
        clock.now = t0.plusSeconds(5);
        assertFalse(client.getCurrentlyPlaying().hasTrack());

        // t0+11: window elapsed — a fresh fetch happens (204 = nothing playing).
        clock.now = t0.plusSeconds(11);
        assertFalse(client.getCurrentlyPlaying().hasTrack());
        server.verify();
    }

    @Test
    void throttleShowsTheLastGoodSnapshotInsteadOfBlanking() {
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        clock.now = t0;
        respond(200, null, PLAYING_BODY); // request 1: a real track
        respond(429, "30", null);         // request 2: throttled

        assertEquals("Song", client.getCurrentlyPlaying().title());
        clock.now = t0.plusSeconds(1);

        final SpotifyPlaybackClient.SpotifyPlayback throttled = client.getCurrentlyPlaying();
        assertTrue(throttled.hasTrack(), "throttled call keeps the last good track");
        assertEquals("Song", throttled.title());

        server.verify();
    }

    @Test
    void missingOrGarbledRetryAfterFallsBackToFiveSeconds() {
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        clock.now = t0;
        respond(429, "tomorrow", null); // request 1: non-numeric header
        respond(204, null, null);       // request 2 (after the 5s default)

        assertFalse(client.getCurrentlyPlaying().hasTrack());

        // t0+4: still inside the 5s default — no request may happen.
        clock.now = t0.plusSeconds(4);
        assertFalse(client.getCurrentlyPlaying().hasTrack());

        clock.now = t0.plusSeconds(6);
        assertFalse(client.getCurrentlyPlaying().hasTrack());
        server.verify();
    }
}
