package nl.ctasoftware.crypto.ticker.server.config;

import nl.ctasoftware.crypto.ticker.server.service.mqtt.HiveMqMqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class MqttConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private HiveMqMqttTransport transport;

    @Bean(destroyMethod = "shutdown")
    MqttTransport mqttTransport(@Value("${pixelcore75.mqtt.broker-url:tcp://localhost:1883}") final String brokerUrl) {
        // HiveMQ async client (plan §5): connects in the background — a down broker logs a
        // warning instead of failing the boot, automaticReconnect keeps trying.
        final String clientId = "pixelcore75-server-" + UUID.randomUUID().toString().substring(0, 8);
        this.transport = new HiveMqMqttTransport(brokerUrl, clientId);
        return transport;
    }

    /** Confirm the (async) connection once the context is up; bounded, never fatal. */
    @EventListener(ApplicationReadyEvent.class)
    public void awaitBrokerConnection() {
        if (transport != null) {
            transport.awaitConnected(CONNECT_TIMEOUT);
        }
    }
}
