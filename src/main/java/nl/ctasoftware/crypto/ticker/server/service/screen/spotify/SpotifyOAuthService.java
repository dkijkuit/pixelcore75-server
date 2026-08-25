package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.Px75ClientException;
import nl.ctasoftware.crypto.ticker.server.model.Px75SpotifyConnection;
import nl.ctasoftware.crypto.ticker.server.repository.SpotifyConnectionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Spotify user authorization, PKCE flavor (no client secret on this server):
 * start() builds the authorize URL from a random verifier/state pair, the browser
 * goes through Spotify consent and lands on the callback endpoint, which calls
 * {@link #complete} to exchange the code for tokens. The refresh token is persisted
 * (one shared connection per server); access tokens are cached in memory and
 * refreshed transparently. A refresh rejected with 400 (token revoked or expired
 * beyond grace) drops the stored connection — the screen then shows its
 * "not connected" card until someone connects again.
 */
@Slf4j
@Service
public class SpotifyOAuthService {

    static final String DEFAULT_CONNECTION_KEY = "default";
    static final String SCOPE = "user-read-playback-state user-read-currently-playing";
    static final Duration PENDING_TTL = Duration.ofMinutes(10);

    /** Skew applied to the access-token expiry so refreshes never race the clock. */
    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 60;

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_PATH = "/api/token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SpotifyConnectionRepository repository;
    private final RestClient spotifyApiRestClient;
    private final RestClient spotifyAccountsRestClient;
    private final String clientId;
    private final String redirectUri;

    /** PKCE pair for the authorization in flight; single-operator flow, one at a time. */
    private record PendingAuth(String state, String codeVerifier, Instant createdAt) {
    }

    private record CachedAccessToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private volatile PendingAuth pending;
    private volatile CachedAccessToken cachedAccessToken;

    public SpotifyOAuthService(final SpotifyConnectionRepository repository,
                               @Qualifier("spotifyApiRestClient") final RestClient spotifyApiRestClient,
                               @Qualifier("spotifyAccountsRestClient") final RestClient spotifyAccountsRestClient,
                               @Value("${pixelcore75.spotify.client-id}") final String clientId,
                               @Value("${pixelcore75.spotify.redirect-uri}") final String redirectUri) {
        this.repository = repository;
        this.spotifyApiRestClient = spotifyApiRestClient;
        this.spotifyAccountsRestClient = spotifyAccountsRestClient;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }

    public record SpotifyStatus(boolean connected, String displayName) {
    }

    public SpotifyStatus status() {
        return repository.findByConnectionKey(DEFAULT_CONNECTION_KEY)
                .map(c -> new SpotifyStatus(true, c.getDisplayName()))
                .orElse(new SpotifyStatus(false, null));
    }

    /** Starts the flow: builds the authorize URL from a fresh PKCE verifier/state pair. */
    public synchronized String start() {
        if (!isConfigured()) {
            throw new Px75ClientException("Spotify is not configured: set the SPOTIFY_CLIENT_ID environment variable");
        }
        if (pending != null && pending.createdAt().isBefore(Instant.now().minus(PENDING_TTL))) {
            pending = null;
        }
        final String verifier = randomUrlSafe(64);
        final String state = randomUrlSafe(16);
        pending = new PendingAuth(state, verifier, Instant.now());

        final URI uri = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("code_challenge_method", "S256")
                .queryParam("code_challenge", codeChallengeFor(verifier))
                .queryParam("state", state)
                .queryParam("scope", SCOPE)
                .encode()
                .build()
                .toUri();
        return uri.toString();
    }

    /**
     * Callback leg: validates the state against the pending pair, exchanges the code
     * (PKCE verifier) for tokens, resolves the profile and stores the connection.
     *
     * @return true when the connection was stored
     */
    public synchronized boolean complete(final String code, final String state) {
        final PendingAuth auth = pending;
        pending = null;
        if (auth == null) {
            throw new Px75ClientException("No Spotify authorization in flight — start the connect flow again");
        }
        if (!auth.state().equals(state)) {
            throw new Px75ClientException("Spotify OAuth state mismatch — start the connect flow again");
        }
        if (auth.createdAt().isBefore(Instant.now().minus(PENDING_TTL))) {
            throw new Px75ClientException("Spotify authorization expired — start the connect flow again");
        }

        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("code_verifier", auth.codeVerifier());
        final SpotifyTokenResponse tokens = requestTokens(form);

        final SpotifyUserProfile profile = fetchProfile(tokens.accessToken());

        final Px75SpotifyConnection connection = repository.findByConnectionKey(DEFAULT_CONNECTION_KEY)
                .orElseGet(() -> new Px75SpotifyConnection(null, DEFAULT_CONNECTION_KEY,
                        "", "", "", null));
        connection.setRefreshToken(tokens.refreshToken() != null ? tokens.refreshToken() : "");
        connection.setSpotifyUserId(profile.id() != null ? profile.id() : "");
        connection.setDisplayName(profile.displayName() != null && !profile.displayName().isBlank()
                ? profile.displayName()
                : connection.getSpotifyUserId());
        connection.setUpdatedAt(Instant.now());
        repository.save(connection);

        cachedAccessToken = new CachedAccessToken(tokens.accessToken(),
                Instant.now().plusSeconds(expirySeconds(tokens)));
        log.info("Spotify connection stored for user {}", connection.getDisplayName());
        return true;
    }

    /** A valid access token, or null when there is no connection / refresh failed. */
    public String getAccessToken() {
        final CachedAccessToken cached = cachedAccessToken;
        if (cached != null && cached.isValid()) {
            return cached.token();
        }
        final Px75SpotifyConnection connection = connection();
        if (connection == null || connection.getRefreshToken().isBlank()) {
            return null;
        }
        return refreshSynchronized(connection);
    }

    /** Whether a stored connection exists (independent of transient refresh failures). */
    public boolean hasConnection() {
        final Px75SpotifyConnection connection = connection();
        return connection != null && !connection.getRefreshToken().isBlank();
    }

    /** Drops the cached access token and fetches a fresh one (used after a 401). */
    public String forceRefresh() {
        cachedAccessToken = null;
        return getAccessToken();
    }

    private synchronized String refreshSynchronized(final Px75SpotifyConnection connection) {
        final CachedAccessToken cached = cachedAccessToken; // another thread may have won the race
        if (cached != null && cached.isValid()) {
            return cached.token();
        }
        if (!isConfigured()) {
            // A process without the env var must never talk to the token endpoint
            // (a blank client_id draws a 400) — and never touch the stored row.
            log.warn("Spotify client id not configured; cannot refresh the stored connection (kept)");
            return null;
        }
        try {
            final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", connection.getRefreshToken());
            form.add("client_id", clientId);
            final SpotifyTokenResponse tokens = requestTokens(form);
            if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
                log.warn("Spotify token endpoint returned no usable token; keeping the stored connection");
                return null;
            }

            // Spotify may rotate the refresh token; keep whichever one is newest.
            if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
                connection.setRefreshToken(tokens.refreshToken());
            }
            connection.setUpdatedAt(Instant.now());
            repository.save(connection);

            cachedAccessToken = new CachedAccessToken(tokens.accessToken(),
                    Instant.now().plusSeconds(expirySeconds(tokens)));
            return tokens.accessToken();
        } catch (final HttpClientErrorException.BadRequest e) {
            handleRefreshRejected(connection, e);
            cachedAccessToken = null;
            return null;
        } catch (final RestClientException e) {
            log.warn("Spotify token refresh failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * A 400 from the token endpoint only drops the stored connection when Spotify
     * definitively rejected THE STORED refresh token ({@code invalid_grant} and the
     * row still carries the token we attempted with). Anything else — a blank or
     * wrong {@code client_id} ({@code invalid_client}), a restart rotation race
     * where another process already swapped the token ({@code invalid_grant} for a
     * token the row no longer holds), or transient API weirdness — keeps the row:
     * a config mistake or an overlap window must never force a re-consent.
     */
    private void handleRefreshRejected(final Px75SpotifyConnection attempted, final HttpClientErrorException.BadRequest e) {
        final String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (!body.contains("invalid_grant")) {
            log.warn("Spotify token refresh rejected ({}); keeping the stored connection", body);
            return;
        }
        final Px75SpotifyConnection stored = connection();
        if (stored == null || !stored.getRefreshToken().equals(attempted.getRefreshToken())) {
            log.warn("Spotify refresh rejected for an already-rotated token; keeping the stored connection");
            return;
        }
        log.warn("Spotify refresh token revoked, dropping the stored connection: {}", body);
        repository.delete(stored);
    }

    private Px75SpotifyConnection connection() {
        return repository.findByConnectionKey(DEFAULT_CONNECTION_KEY).orElse(null);
    }

    private SpotifyTokenResponse requestTokens(final MultiValueMap<String, String> form) {
        return spotifyAccountsRestClient.post()
                .uri(TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(SpotifyTokenResponse.class);
    }

    private SpotifyUserProfile fetchProfile(final String accessToken) {
        final SpotifyUserProfile profile = spotifyApiRestClient.get()
                .uri("/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(SpotifyUserProfile.class);
        if (profile == null) {
            throw new Px75ClientException("Spotify profile lookup returned no body");
        }
        return profile;
    }

    private static long expirySeconds(final SpotifyTokenResponse tokens) {
        return Math.max(TOKEN_EXPIRY_SKEW_SECONDS + 1, tokens.expiresIn() - TOKEN_EXPIRY_SKEW_SECONDS);
    }

    private static String randomUrlSafe(final int byteCount) {
        final byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return base64UrlNoPadding(bytes);
    }

    static String base64UrlNoPadding(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** S256 code challenge: BASE64URL(SHA-256(verifier ASCII)), per RFC 7636 §4.2. */
    static String codeChallengeFor(final String verifier) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return base64UrlNoPadding(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotifyUserProfile(
            @JsonProperty("id") String id,
            @JsonProperty("display_name") String displayName) {
    }
}
