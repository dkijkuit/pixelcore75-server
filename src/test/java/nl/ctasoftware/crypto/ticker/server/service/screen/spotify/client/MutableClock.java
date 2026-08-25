package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** Settable clock for playback-client tests: every {@code instant()} read returns {@code now}. */
class MutableClock extends Clock {
    Instant now;

    MutableClock(final Instant start) {
        this.now = start;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
