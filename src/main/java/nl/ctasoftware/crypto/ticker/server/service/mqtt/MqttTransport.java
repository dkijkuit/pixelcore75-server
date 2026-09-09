package nl.ctasoftware.crypto.ticker.server.service.mqtt;

import java.util.concurrent.CompletableFuture;

/**
 * The single seam between the panel pipeline and the MQTT broker. Publish semantics match the
 * firmware contract byte-for-byte (topic, QoS, retained); the wire protocol itself is unchanged
 * by the transport implementation. Production wraps the MQTT client; tests substitute fakes,
 * keeping every protocol test docker-free.
 */
public interface MqttTransport {

    /** Blocking publish; returns once the broker acknowledged (QoS 1) or accepted (QoS 0). */
    void publish(String topic, byte[] payload, int qos, boolean retained);

    /**
     * Asynchronous publish: returns immediately, the future completes when the broker
     * acknowledged. Callers apply their own bounded in-flight window where the firmware's
     * flow control expects pacing.
     */
    CompletableFuture<Void> publishAsync(String topic, byte[] payload, int qos, boolean retained);

    /** Registers a listener for a topic filter (re-registered by the transport on reconnects). */
    void subscribe(String topicFilter, MqttMessageListener listener);

    @FunctionalInterface
    interface MqttMessageListener {
        void messageArrived(String topic, byte[] payload);
    }
}
