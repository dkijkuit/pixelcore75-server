package nl.ctasoftware.crypto.ticker.server.config;

import nl.ctasoftware.crypto.ticker.server.service.mqtt.HiveMqMqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes the HiveMQ client's connected state on {@code /actuator/health}: DOWN while the
 * broker is unreachable (boot continues by design, auto-reconnect active), so a dead
 * broker is visible without reading logs.
 */
@Component
public class MqttHealthIndicator implements HealthIndicator {

    private final MqttTransport transport;

    public MqttHealthIndicator(final MqttTransport transport) {
        this.transport = transport;
    }

    @Override
    public Health health() {
        if (transport instanceof HiveMqMqttTransport hiveMq) {
            return hiveMq.isConnected()
                    ? Health.up().withDetail("broker", "connected").build()
                    : Health.down().withDetail("broker", "disconnected").build();
        }
        // A non-HiveMQ transport (test fake) has no observable connection state.
        return Health.up().withDetail("broker", "unknown-transport").build();
    }
}
