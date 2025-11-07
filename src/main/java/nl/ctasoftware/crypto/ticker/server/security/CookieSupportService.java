package nl.ctasoftware.crypto.ticker.server.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CookieSupportService {
    public ResponseCookie refreshCookie(String value, boolean crossSite) {
        var builder = ResponseCookie.from("refreshToken", value)
            .httpOnly(true)
            .path("/v1/auth") // refresh endpoint path
            .maxAge(Duration.ofDays(7))
            .sameSite(crossSite ? "None" : "Lax");
        if (crossSite) builder.secure(true);
        return builder.build();
    }

    public ResponseCookie clear(String name, String path, boolean crossSite) {
        var builder = ResponseCookie.from(name, "")
            .httpOnly(true)
            .path(path)
            .maxAge(0)
            .sameSite(crossSite ? "None" : "Lax");
        if (crossSite) builder.secure(true);
        return builder.build();
    }
}