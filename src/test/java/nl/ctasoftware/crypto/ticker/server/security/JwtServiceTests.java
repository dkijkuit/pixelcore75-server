package nl.ctasoftware.crypto.ticker.server.security;

import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the review 2026-08-27 fix: JWT secrets are env-provided, local dev gets the
 * old hardcoded fallbacks, and a blank secret with the prod profile fails the boot.
 */
class JwtServiceTests {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef"; // >= 256 bits for HS256

    private static Px75User user() {
        return new Px75User("david", "pw", "david@example.com", Set.of(Px75Role.USER));
    }

    @Test
    void blankSecretsFallBackToDevDefaultsOutsideProd() {
        final JwtService service = new JwtService("", "", 60_000, 60_000, new MockEnvironment());

        assertEquals("david", service.extractUsername(service.generateAccessToken(user()), false));
        assertEquals("david", service.extractUsername(service.generateRefreshToken(user()), true));
    }

    @Test
    void providedSecretsWinOverDevDefaults() {
        final JwtService service = new JwtService(
                SECRET_32 + "-access", SECRET_32 + "-refresh", 60_000, 60_000, new MockEnvironment());

        assertEquals("david", service.extractUsername(service.generateAccessToken(user()), false));
    }

    @Test
    void prodProfileWithBlankSecretsFailsTheBoot() {
        final MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("", "", 60_000, 60_000, env));
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void prodProfileWithEnvSecretsBoots() {
        final MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        final JwtService service = new JwtService(
                SECRET_32 + "-access", SECRET_32 + "-refresh", 60_000, 60_000, env);
        assertEquals("david", service.extractUsername(service.generateRefreshToken(user()), true));
    }
}
