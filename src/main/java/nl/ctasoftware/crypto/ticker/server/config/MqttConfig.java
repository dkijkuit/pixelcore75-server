package nl.ctasoftware.crypto.ticker.server.config;

import nl.ctasoftware.crypto.ticker.server.service.mqtt.HiveMqMqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private HiveMqMqttTransport transport;

    @Bean(destroyMethod = "shutdown")
    MqttTransport mqttTransport(final MqttProperties properties) {
        // HiveMQ async client (plan §5): connects in the background — a down broker logs a
        // warning instead of failing the boot, automaticReconnect keeps trying. The client
        // id is stable (MqttProperties) so cleanSession(false) sessions survive restarts
        // instead of piling up an orphaned session per boot.
        this.transport = new HiveMqMqttTransport(properties.brokerUrl(), properties.clientId());
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
