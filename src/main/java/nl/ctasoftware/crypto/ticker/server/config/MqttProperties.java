package nl.ctasoftware.crypto.ticker.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MQTT connection settings ({@code pixelcore75.mqtt.*}), env-overridable for
 * off-localhost deployments.
 *
 * @param brokerUrl e.g. {@code tcp://localhost:1883} or {@code ssl://broker:8883}
 * @param clientId  stable across restarts — the transport connects with
 *                  {@code cleanSession(false)}, so a random per-boot id would orphan a
 *                  persistent broker session (and its queued QoS 1 messages) every restart
 */
@ConfigurationProperties(prefix = "pixelcore75.mqtt")
public record MqttProperties(
        @DefaultValue("tcp://localhost:1883") String brokerUrl,
        @DefaultValue("pixelcore75-server") String clientId) {
}
