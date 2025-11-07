package nl.ctasoftware.crypto.ticker.server.service.job;

import java.time.Duration;
import java.util.Optional;

/**
 * Implementeer run() en return:
 *  - Optional.of(delay) om opnieuw te schedulen over 'delay'
 *  - Optional.empty() om te stoppen (geen reschedule)
 */
public interface ReschedulableJob {
    Optional<Duration> run() throws Exception;

    default String name() {
        return getClass().getSimpleName();
    }

    String getId();
}