package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Podcast (episode) playback must render like a track: the request carries
 * {@code additional_types=track,episode} (without {@code episode} Spotify omits
 * the item entirely — the screen showed "not playing"), and an episode's show
 * name fills the artist line because episodes carry no artists array.
 */
class SpotifyPlaybackClientEpisodeTests {

    private static final String PLAYER_URL =
            "https://api.spotify.com/v1/me/player?additional_types=track,episode";

    private SpotifyOAuthService oauth;
    private MockRestServiceServer server;
    private SpotifyPlaybackClient client;

    @BeforeEach
    void setUp() {
        oauth = mock(SpotifyOAuthService.class);
        when(oauth.getAccessToken()).thenReturn("token");
        when(oauth.hasConnection()).thenReturn(true);
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.spotify.com/v1/");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SpotifyPlaybackClient(builder.build(), oauth, Clock.systemUTC());
    }

    @Test
    void episodeBodyUsesTheShowNameAsArtistLine() {
        server.expect(requestTo(PLAYER_URL)).andRespond(withSuccess(
                "{\"is_playing\":true,\"progress_ms\":12000,\"currently_playing_type\":\"episode\","
                        + "\"item\":{\"name\":\"Episode 42\",\"duration_ms\":3000000,"
                        + "\"show\":{\"name\":\"Some Podcast\",\"publisher\":\"Someone\"}}}",
                MediaType.APPLICATION_JSON));

        final SpotifyPlaybackClient.SpotifyPlayback playback = client.getCurrentlyPlaying();
        assertTrue(playback.hasTrack(), "episode playback is a playable item, not idle");
        assertEquals("Episode 42", playback.title());
        assertEquals("Some Podcast", playback.artist());
        assertEquals(3_000_000, playback.durationMs());
        assertTrue(Math.abs(playback.progressMs() - 12_000) < 100,
                "served progress is the anchored projection (12s + serve-time epsilon)");
        server.verify();
    }

    @Test
    void trackBodyWithoutShowStillJoinsArtists() {
        server.expect(requestTo(PLAYER_URL)).andRespond(withSuccess(
                "{\"is_playing\":true,\"progress_ms\":60000,\"item\":"
                        + "{\"name\":\"Song\",\"artists\":[{\"name\":\"A\"},{\"name\":\"B\"}],"
                        + "\"duration_ms\":240000}}",
                MediaType.APPLICATION_JSON));

        final SpotifyPlaybackClient.SpotifyPlayback playback = client.getCurrentlyPlaying();
        assertTrue(playback.hasTrack());
        assertEquals("Song", playback.title());
        assertEquals("A, B", playback.artist(), "artists take precedence over a (absent) show");
        server.verify();
    }
}
