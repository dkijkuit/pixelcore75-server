package nl.ctasoftware.crypto.ticker.server.service.screen.soccer;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Resolving league names needs one ESPN request per league (~5s in total).
 * Prime the 24h cache at startup so the first user request is instant.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pixelcore75.soccer.client", havingValue = "espn")
public class SoccerMetadataWarmup {
    private final SoccerMatchClient soccerMatchClient;

    public SoccerMetadataWarmup(final SoccerMatchClient soccerMatchClient) {
        this.soccerMatchClient = soccerMatchClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmLeagues() {
        Thread.ofVirtual().name("soccer-metadata-warmup").start(() -> {
            try {
                soccerMatchClient.getLeagues();
                log.info("Warmed soccer league metadata");
            } catch (final RuntimeException e) {
                log.warn("Failed to warm soccer league metadata: {}", e.getMessage());
            }
        });
    }
}
