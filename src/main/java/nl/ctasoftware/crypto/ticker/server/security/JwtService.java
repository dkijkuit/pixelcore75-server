package nl.ctasoftware.crypto.ticker.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private static final String ISSUER = "pixelcore75";
    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtService(@Value("${pixelcore75.jwt.secret}") String accessSecret,
                      @Value("${pixelcore75.jwt.refresh-secret}") String refreshSecret,
                      @Value("${pixelcore75.jwt.access-expiration}") long accessExpiration,
                      @Value("${pixelcore75.jwt.refresh-expiration}") long refreshExpiration) {
        this.accessKey = Keys.hmacShaKeyFor(accessSecret.getBytes());
        this.refreshKey = Keys.hmacShaKeyFor(refreshSecret.getBytes());
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    public String generateAccessToken(final Px75User px75User) {
        return buildToken(px75User, accessKey, accessExpiration);
    }


    public String generateRefreshToken(final Px75User px75User) {
        return buildToken(px75User, refreshKey, refreshExpiration);
    }

    private String buildToken(final Px75User px75User, final SecretKey key, final long expiration) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(px75User.getUsername())
                .claim("roles", px75User.getRoles())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private Jws<Claims> parse(String token, boolean isRefresh) {
        return Jwts.parser()
                .requireIssuer(ISSUER)
                .clockSkewSeconds(60) // 1 min skew
                .verifyWith(isRefresh ? refreshKey : accessKey)
                .build()
                .parseSignedClaims(token);
    }

    public String extractUsername(String token, boolean isRefresh) {
        return parse(token, isRefresh).getPayload().getSubject();
    }

    public boolean isValid(String token) {
        try {
            parse(token, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
