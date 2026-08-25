package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The monotone playback anchor: Spotify's {@code progress_ms} is quantized and
 * lags the request by the round-trip, so serving each raw fetch makes the
 * rendered time jump back and forth. A fetch agreeing with the locally
 * projected position (same track, within tolerance) must keep the local clock;
 * only a real discontinuity (seek, pause) re-anchors. Between fetches the
 * anchor advances, so the 1s render tick serves smoothly increasing positions.
 *
 * <p>All expectations are declared up front (the ordered expectation manager
 * refuses additions once requests have run); the mutable clock advances between
 * the client calls to walk the timeline.
 */
class SpotifyPlaybackClientSmoothAnchorTests {

    private static final String PLAYER_URL =
            "https://api.spotify.com/v1/me/player?additional_types=track,episode";

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

    private void respond(final boolean playing, final long progressMs) {
        server.expect(requestTo(PLAYER_URL)).andRespond(withSuccess(
                "{\"is_playing\":" + playing + ",\"progress_ms\":" + progressMs
                        + ",\"item\":{\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}],"
                        + "\"duration_ms\":240000}}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    void agreeingFetchKeepsTheLocalClock() {
        respond(true, 60_000); // anchor: 60s at t0
        respond(true, 62_300); // fresh fetch at t0+2.4s: API lags the true 62.4s position
        assertEquals(60_000, client.getCurrentlyPlaying().progressMs());

        clock.now = clock.now.plusMillis(2_400);
        assertEquals(62_400, client.getCurrentlyPlaying(2_000).progressMs(),
                "agreement within tolerance keeps the monotone local projection");
        server.verify();
    }

    @Test
    void seekBeyondToleranceReAnchors() {
        respond(true, 60_000);
        respond(true, 10_000); // user seeked back at t0+2s — a real discontinuity
        client.getCurrentlyPlaying();

        clock.now = clock.now.plusSeconds(2);
        assertEquals(10_000, client.getCurrentlyPlaying(2_000).progressMs());
        server.verify();
    }

    @Test
    void pausedFetchFreezesTheServedProgress() {
        respond(true, 60_000);
        respond(false, 61_500); // paused at t0+2s
        client.getCurrentlyPlaying();

        clock.now = clock.now.plusSeconds(2);
        assertEquals(61_500, client.getCurrentlyPlaying(2_000).progressMs());

        clock.now = clock.now.plusSeconds(1); // budgeted serve, no request allowed
        assertEquals(61_500, client.getCurrentlyPlaying(5_000).progressMs(),
                "paused anchor stays frozen across serves");
        server.verify();
    }

    @Test
    void budgetedServesAdvanceBetweenFetches() {
        respond(true, 60_000);
        assertEquals(60_000, client.getCurrentlyPlaying().progressMs());

        clock.now = clock.now.plusSeconds(1); // inside the budget: no request
        assertEquals(61_000, client.getCurrentlyPlaying(2_000).progressMs(),
                "the anchor advances locally — the natural 1s tick without API load");
        server.verify();
    }

    @Test
    void driftBeyondToleranceSlewsInsteadOfSnapping() {
        respond(true, 60_000);                 // anchor: 60s at t0
        respond(true, 55_000);                 // t0+2s: 7s behind — a buffering stall
        client.getCurrentlyPlaying();

        clock.now = clock.now.plusSeconds(2);
        assertEquals(61_500, client.getCurrentlyPlaying(2_000).progressMs(),
                "drift inside the slew band moves the anchor one capped step (62s - 0.5s), not a 7s snap");
        server.verify();
    }

    @Test
    void repeatedFetchesConvergeTheDriftGradually() {
        respond(true, 60_000); // anchor: 60s at t0
        respond(true, 55_000); // t0+2s: slew step 1 → 61.5 @ t0+2s
        respond(true, 55_000); // t0+4s: projection 63.5, diff -8.5 → step 2 → 63.0
        client.getCurrentlyPlaying();

        clock.now = clock.now.plusSeconds(2);
        client.getCurrentlyPlaying(2_000);

        clock.now = clock.now.plusSeconds(2);
        assertEquals(63_000, client.getCurrentlyPlaying(2_000).progressMs(),
                "each fetch closes the gap by at most one slew step");
        server.verify();
    }

    @Test
    void seekBeyondTheSlewBandSnapsImmediately() {
        respond(true, 60_000);  // anchor: 60s at t0
        respond(true, 20_000);  // t0+2s: -42s — a deliberate seek must not crawl
        client.getCurrentlyPlaying();

        clock.now = clock.now.plusSeconds(2);
        assertEquals(20_000, client.getCurrentlyPlaying(2_000).progressMs());
        server.verify();
    }
}
