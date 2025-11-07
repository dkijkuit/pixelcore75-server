package nl.ctasoftware.crypto.ticker.server.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TicketAuthFilter extends OncePerRequestFilter {
    private static final String SSE_AUTH_ATTR = "px75.sse.auth";

    private final SseTicketService tickets;
    private final Px75UserDetailsService userDetailsService;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false; // run for async dispatch too
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null
                && req.getRequestURI().startsWith("/v1/panel/image/")) {

            // Reuse authentication if we cached it earlier in this request
            var cached = (Authentication) req.getAttribute(SSE_AUTH_ATTR);
            if (cached != null) {
                SecurityContextHolder.getContext().setAuthentication(cached);
            } else if (req.getDispatcherType() == DispatcherType.REQUEST) {
                // Only consume the ticket on the initial request dispatch
                String token = req.getParameter("ticket");
                if (token != null && !token.isBlank()) {
                    Long panelId = tryParsePanelId(req.getRequestURI());
                    if (panelId != null) {
                        var ticket = tickets.consumeIfValid(token, panelId);
                        if (ticket != null) {
                            var user = userDetailsService.getPx75UserById(ticket.userId());
                            var auth = new UsernamePasswordAuthenticationToken(
                                    user, null, user.getAuthorities());
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            // Cache auth for later ASYNC dispatches
                            req.setAttribute(SSE_AUTH_ATTR, auth);
                        }
                    }
                }
            }
        }

        chain.doFilter(req, res);
    }

    private Long tryParsePanelId(String uri) {
        String[] parts = uri.split("/");
        try {
            return Long.parseLong(parts[parts.length - 1]);
        } catch (Exception e) {
            return null;
        }
    }
}
