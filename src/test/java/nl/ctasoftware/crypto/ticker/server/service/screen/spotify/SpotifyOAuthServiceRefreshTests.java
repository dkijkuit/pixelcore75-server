package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import nl.ctasoftware.crypto.ticker.server.model.Px75SpotifyConnection;
import nl.ctasoftware.crypto.ticker.server.repository.SpotifyConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The stored refresh token must only be dropped when Spotify definitively rejects
 * it (invalid_grant) AND nobody rotated it meanwhile: a blank/misconfigured client
 * id (invalid_client) or a restart rotation race (two processes refreshing the same
 * token) must degrade to a null access token while KEEPING the connection — a
 * config mistake or an overlap window must never force a re-consent.
 */
class SpotifyOAuthServiceRefreshTests {

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    private SpotifyConnectionRepository repository;
    private RestClient accounts;
    private MockRestServiceServer accountsServer;

    @BeforeEach
    void setUp() {
        repository = mock(SpotifyConnectionRepository.class);
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://accounts.spotify.com/");
        accountsServer = MockRestServiceServer.bindTo(builder).build();
        accounts = builder.build();
    }

    private SpotifyOAuthService service(final String clientId) {
        return new SpotifyOAuthService(repository, mock(RestClient.class), accounts, clientId,
                "http://localhost:8080/v1/spotify/oauth/callback");
    }

    private Px75SpotifyConnection row(final String refreshToken) {
        return new Px75SpotifyConnection(1L, SpotifyOAuthService.DEFAULT_CONNECTION_KEY,
                refreshToken, "spotify-user", "David", Instant.now());
    }

    private void tokenEndpointResponds(final String errorJson) {
        accountsServer.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson));
    }

    @Test
    void blankClientIdNeverRefreshesAndNeverDropsTheConnection() {
        // No expectation registered: any request to the token endpoint fails the test.
        when(repository.findByConnectionKey(SpotifyOAuthService.DEFAULT_CONNECTION_KEY))
                .thenReturn(Optional.of(row("token-T")));

        assertNull(service("").getAccessToken());
        accountsServer.verify();
        verify(repository, never()).delete(any());
    }

    @Test
    void invalidClientRejectionKeepsTheStoredConnection() {
        when(repository.findByConnectionKey(SpotifyOAuthService.DEFAULT_CONNECTION_KEY))
                .thenReturn(Optional.of(row("token-T")));
        tokenEndpointResponds("{\"error\":\"invalid_client\"}");

        assertNull(service("configured-client-id").getAccessToken());
        verify(repository, never()).delete(any());
    }

    @Test
    void genuineInvalidGrantForTheStoredTokenDropsTheConnection() {
        when(repository.findByConnectionKey(SpotifyOAuthService.DEFAULT_CONNECTION_KEY))
                .thenReturn(Optional.of(row("token-T")));
        tokenEndpointResponds("{\"error\":\"invalid_grant\"}");

        assertNull(service("configured-client-id").getAccessToken());
        verify(repository).delete(any());
    }

    @Test
    void invalidGrantForAnAlreadyRotatedTokenKeepsTheStoredConnection() {
        // Restart race: we attempted with token-T, but the DB already holds token-T'
        // (another process refreshed and rotated it) — the rejection is stale news.
        when(repository.findByConnectionKey(SpotifyOAuthService.DEFAULT_CONNECTION_KEY))
                .thenReturn(Optional.of(row("token-T")))
                .thenReturn(Optional.of(row("token-T'")));
        tokenEndpointResponds("{\"error\":\"invalid_grant\"}");

        assertNull(service("configured-client-id").getAccessToken());
        verify(repository, never()).delete(any());
    }
}
