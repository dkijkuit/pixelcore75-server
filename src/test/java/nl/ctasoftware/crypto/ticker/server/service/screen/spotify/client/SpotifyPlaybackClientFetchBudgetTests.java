package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Fetch budget for the render-tick decoupling: a budgeted
 * {@code getCurrentlyPlaying(maxAgeMs)} must serve the cached definitive
 * outcome (track OR idle) while it is young and only hit the API once it is
 * older than the budget — the 1s time-line tick must not multiply the polling
 * rate of the rate-limited playback endpoint.
 */
class SpotifyPlaybackClientFetchBudgetTests {

    private static final String PLAYER_URL =
            "https://api.spotify.com/v1/me/player?additional_types=track,episode";
    private static final String PLAYING_BODY =
            "{\"is_playing\":true,\"progress_ms\":60000,\"item\":"
                    + "{\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}],\"duration_ms\":240000}}";

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

    private void expectPlaying() {
        server.expect(requestTo(PLAYER_URL))
                .andRespond(withSuccess(PLAYING_BODY, MediaType.APPLICATION_JSON));
    }

    @Test
    void youngSnapshotIsServedFromTheCache() {
        expectPlaying(); // the only allowed request
        assertEquals("Song", client.getCurrentlyPlaying().title());

        clock.now = clock.now.plusSeconds(1); // inside a 2s budget
        final SpotifyPlaybackClient.SpotifyPlayback cached = client.getCurrentlyPlaying(2_000);
        assertEquals("Song", cached.title(), "cached snapshot, no second fetch");
        server.verify();
    }

    @Test
    void staleSnapshotTriggersARefetch() {
        expectPlaying();
        expectPlaying();
        assertEquals("Song", client.getCurrentlyPlaying().title());

        clock.now = clock.now.plusSeconds(3); // past a 2s budget
        assertEquals("Song", client.getCurrentlyPlaying(2_000).title());
        server.verify(); // exactly two requests
    }

    @Test
    void idleOutcomesAreCachedToo() {
        server.expect(requestTo(PLAYER_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));
        assertFalse(client.getCurrentlyPlaying().hasTrack());

        clock.now = clock.now.plusSeconds(1);
        assertFalse(client.getCurrentlyPlaying(2_000).hasTrack(),
                "cached idle must not resurrect the previous track between fetches");
        server.verify();
    }

    @Test
    void zeroBudgetAlwaysFetches() {
        expectPlaying();
        expectPlaying();
        client.getCurrentlyPlaying();
        client.getCurrentlyPlaying(); // no-arg = always a real fetch
        server.verify();
    }
}
