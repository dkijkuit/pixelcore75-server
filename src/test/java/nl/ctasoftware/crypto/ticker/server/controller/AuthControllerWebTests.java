package nl.ctasoftware.crypto.ticker.server.controller;

import io.jsonwebtoken.JwtException;
import nl.ctasoftware.crypto.ticker.server.exception.ApiErrorHandler;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.repository.UserRepository;
import nl.ctasoftware.crypto.ticker.server.security.CookieSupportService;
import nl.ctasoftware.crypto.ticker.server.security.JwtService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the review 2026-08-27 fix: bad-password logins and refresh with an
 * expired/invalid refresh JWT answered 500 before the {@link ApiErrorHandler}
 * gained its {@code AuthenticationException}/{@code JwtException} handlers.
 */
class AuthControllerWebTests {

    private AuthenticationManager authManager;
    private JwtService jwtService;
    private Px75UserDetailsService userDetailsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authManager = mock(AuthenticationManager.class);
        jwtService = mock(JwtService.class);
        userDetailsService = mock(Px75UserDetailsService.class);
        final AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), authManager,
                jwtService, userDetailsService, new CookieSupportService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void wrongPasswordLoginIs401Not500() throws Exception {
        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"david\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void refreshWithExpiredTokenIs401Not500() throws Exception {
        when(jwtService.extractUsername(any(), eq(true)))
                .thenThrow(new JwtException("JWT expired"));

        mockMvc.perform(post("/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("refreshToken", "stale")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void refreshWithValidTokenStillReturnsAccessToken() throws Exception {
        final Px75User user = new Px75User("david", "pw", "david@example.com", Set.of(Px75Role.USER));
        when(jwtService.extractUsername("good", true)).thenReturn("david");
        when(userDetailsService.getPx75User("david")).thenReturn(user);
        when(jwtService.generateAccessToken(user)).thenReturn("access");

        mockMvc.perform(post("/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("refreshToken", "good")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    void successfulLoginSetsRefreshCookieAndReturnsAccessToken() throws Exception {
        final Px75User user = new Px75User("david", "pw", "david@example.com", Set.of(Px75Role.USER));
        final org.springframework.security.core.Authentication auth =
                org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class);
        when(auth.getName()).thenReturn("david");
        when(authManager.authenticate(any())).thenReturn(auth);
        when(userDetailsService.getPx75User("david")).thenReturn(user);
        when(jwtService.generateAccessToken(user)).thenReturn("access");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh");

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"david\",\"password\":\"right\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }
}
