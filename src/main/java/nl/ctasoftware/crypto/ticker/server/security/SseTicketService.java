package nl.ctasoftware.crypto.ticker.server.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SseTicketService {

    record Ticket(String token, long userId, long panelId, Instant expiresAt) {}

    private final Map<String, Ticket> store = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    public String issue(long userId, long panelId, long ttlSeconds) {
        byte[] buf = new byte[32];
        rng.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        Instant exp = Instant.now().plusSeconds(ttlSeconds);
        store.put(token, new Ticket(token, userId, panelId, exp));
        return token;
    }

    /**
     * Tickets are NOT consumed on use: EventSource auto-reconnects replay the original ticket
     * URL, and a consumed ticket would turn every transient connection drop into a permanently
     * stuck preview (401 on each reconnect until page reload). Expiry bounds replay instead.
     */
    public Ticket validate(String token, long expectedPanelId) {
        Ticket t = store.get(token);
        if (t == null) return null;
        if (t.expiresAt().isBefore(Instant.now())) { store.remove(token); return null; }
        if (t.panelId() != expectedPanelId) return null;
        return t;
    }

    @Scheduled(fixedDelay = 60_000)
    public void reap() {
        Instant now = Instant.now();
        store.values().removeIf(t -> t.expiresAt().isBefore(now));
    }
}