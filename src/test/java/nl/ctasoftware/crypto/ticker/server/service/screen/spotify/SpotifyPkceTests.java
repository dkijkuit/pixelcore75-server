package nl.ctasoftware.crypto.ticker.server.service.screen.spotify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PKCE S256 correctness: the code challenge must be BASE64URL(SHA-256(verifier))
 * without padding — pinned against the RFC 7636 appendix B test vector.
 */
class SpotifyPkceTests {

    private static final String RFC7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void codeChallengeMatchesRfc7636Vector() {
        assertEquals(RFC7636_CHALLENGE, SpotifyOAuthService.codeChallengeFor(RFC7636_VERIFIER));
    }

    @Test
    void base64UrlHasNoPaddingCharacters() {
        // 31 bytes → 42 base64 chars (no padding); must use the URL-safe alphabet.
        final String encoded = SpotifyOAuthService.base64UrlNoPadding(new byte[31]);
        assertEquals(42, encoded.length());
        assertTrue(encoded.matches("[A-Za-z0-9_-]+"), "URL-safe alphabet only, no '=' padding");
    }

    @Test
    void challengeChangesWithVerifier() {
        assertNotEquals(SpotifyOAuthService.codeChallengeFor("verifier-one"),
                SpotifyOAuthService.codeChallengeFor("verifier-two"));
    }
}
