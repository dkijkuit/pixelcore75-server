package nl.ctasoftware.crypto.ticker.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.dto.SpotifyStartResponse;
import nl.ctasoftware.crypto.ticker.server.model.dto.SpotifyStatusResponse;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyOAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Spotify connection management: status + OAuth start are authenticated SPA calls;
 * the callback is the browser's redirect target from Spotify consent (no JWT on
 * that request — it is permitAll'd in SecurityConfig and protected by the PKCE
 * state check instead) and always bounces the browser back to the frontend with
 * a ?spotify= outcome marker.
 */
@Slf4j
@RestController
@RequestMapping("/v1/spotify")
@RequiredArgsConstructor
public class SpotifyController {

    private final SpotifyOAuthService spotifyOAuthService;

    @GetMapping("/status")
    public SpotifyStatusResponse status() {
        final SpotifyOAuthService.SpotifyStatus status = spotifyOAuthService.status();
        return new SpotifyStatusResponse(status.connected(), status.displayName());
    }

    @GetMapping("/oauth/start")
    public SpotifyStartResponse start() {
        return new SpotifyStartResponse(spotifyOAuthService.start());
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) final String code,
            @RequestParam(required = false) final String state,
            @RequestParam(required = false) final String error,
            @Value("${pixelcore75.spotify.post-connect-redirect}") final String postConnectRedirect) {
        String outcome = "connected";
        try {
            if (error != null && !error.isBlank()) {
                log.info("Spotify authorization denied: {}", error);
                outcome = "denied";
            } else if (code == null || code.isBlank()) {
                outcome = "failed";
            } else {
                spotifyOAuthService.complete(code, state);
            }
        } catch (final Exception e) {
            log.warn("Spotify OAuth callback failed: {}", e.getMessage());
            outcome = "failed";
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(postConnectRedirect + "?spotify=" + outcome))
                .build();
    }
}
