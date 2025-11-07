package nl.ctasoftware.crypto.ticker.server.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.AuthResponse;
import nl.ctasoftware.crypto.ticker.server.model.LoginRequest;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.repository.UserRepository;
import nl.ctasoftware.crypto.ticker.server.security.CookieSupportService;
import nl.ctasoftware.crypto.ticker.server.security.JwtService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final Px75UserDetailsService px75UserDetailsService;
    private final CookieSupportService cookieSupportService;

    @PostMapping("/login")
    public AuthResponse login(@RequestBody final LoginRequest request, final HttpServletResponse response) {
        final Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        final Px75User px75User = px75UserDetailsService.getPx75User(auth.getName());
        final String accessToken  = jwtService.generateAccessToken(px75User);
        final String refreshToken = jwtService.generateRefreshToken(px75User);

        // Set ONLY the refresh token as an HttpOnly, Secure cookie (access token stays in Authorization header)
        response.addHeader(HttpHeaders.SET_COOKIE, cookieSupportService.refreshCookie(refreshToken, /*secureInProd*/ true).toString());

        // Return the access token in the response body for the SPA to store/use in the Authorization header
        return new AuthResponse(accessToken, /*refresh*/ null, px75User);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@CookieValue("refreshToken") final String refresh, final HttpServletResponse response) {
        // parse() validates signature & expiration; will throw if invalid/expired
        final String username = jwtService.extractUsername(refresh, true);
        final Px75User px75User = px75UserDetailsService.getPx75User(username);

        final String newAccessToken = jwtService.generateAccessToken(px75User);
        // (Optional, more secure) rotate the refresh token:
        // final String newRefresh = jwtService.generateRefreshToken(px75User);
        // response.addHeader(HttpHeaders.SET_COOKIE, cookieSupportService.refreshCookie(newRefresh, true).toString());
        // return new AuthResponse(newAccessToken, null, px75User);

        return new AuthResponse(newAccessToken, null, px75User);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        // Clear ONLY the refresh cookie (no access-token cookie to clear anymore)
        response.addHeader(HttpHeaders.SET_COOKIE, cookieSupportService.clear("refreshToken", "/v1/auth", true).toString());
        return ResponseEntity.ok().build();
    }

    // Consider restricting this in production:
    @PostMapping("/register")
    public String register(@RequestBody Px75User px75User) {
        Px75User newPx75User = new Px75User(
                px75User.getUsername(),
                passwordEncoder.encode(px75User.getPassword()),
                px75User.getEmail(),
                Set.of(Px75Role.USER)
        );
        userRepository.save(newPx75User);
        return "User registered!";
    }
}
