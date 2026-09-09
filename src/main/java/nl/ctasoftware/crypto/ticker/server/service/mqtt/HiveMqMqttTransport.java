package nl.ctasoftware.crypto.ticker.server.service.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HiveMQ-backed {@link MqttTransport} (plan §5, replaces Paho v3; the wire protocol to the
 * firmware is unchanged). Design points that matter for this fleet:
 *
 * <ul>
 *   <li><strong>Async connect, boot never crashes on a down broker</strong>: the client
 *       starts connecting in the constructor and {@code automaticReconnect} takes over from
 *       the first attempt on; {@link #awaitConnected} bounds the startup wait (logged, not
 *       thrown — the old Paho wiring failed the boot instead).</li>
 *   <li><strong>Resubscribe on every (re)connect</strong>: HiveMQ restores subscriptions
 *       only if the broker session does; NanoMQ's {@code cleanStart(false)} session behavior
 *       is not trusted here — the anim/loaded ack subscription is re-registered on every
 *       connected callback (belt and braces, per plan §5).</li>
 *   <li><strong>{@link #publishAsync} completes on broker ack</strong> (QoS 1 PUBACK), which
 *       is what {@code AnimationTransport}'s bounded in-flight window gates on.</li>
 * </ul>
 */
@Slf4j
public class HiveMqMqttTransport implements MqttTransport {

    private final Mqtt3AsyncClient client;
    private final Map<String, MqttMessageListener> subscriptions = new ConcurrentHashMap<>();

    public HiveMqMqttTransport(final String brokerUrl, final String clientId) {
        final URI uri = URI.create(brokerUrl);
        final boolean tls = "ssl".equals(uri.getScheme()) || "tls".equals(uri.getScheme());
        final var builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(uri.getHost())
                .serverPort(uri.getPort() > 0 ? uri.getPort() : (tls ? 8883 : 1883))
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(context -> {
                    log.info("MQTT connected to {}", brokerUrl);
                    resubscribeAll();
                })
                .addDisconnectedListener(context ->
                        log.warn("MQTT disconnected from {} ({}); automatic reconnect active",
                                brokerUrl, context.getCause() == null ? "?" : context.getCause().toString()));
        if (tls) {
            builder.useSslWithDefaultConfig();
        }
        this.client = builder.buildAsync();

        // Never connect synchronously in the bean: a down broker must not fail the boot
        // (plan §5 — boot-failure semantics change is deliberate, auto-reconnect recovers).
        client.connectWith()
                .cleanSession(false)
                .keepAlive(60)
                .send()
                .whenComplete((connack, throwable) -> {
                    if (throwable != null) {
                        log.warn("Initial MQTT connect to {} failed ({}); automatic reconnect keeps trying",
                                brokerUrl, throwable.toString());
                    }
                });
    }

    /** Bounded startup wait for the initial connect; logs (does not throw) when the broker is down. */
    public void awaitConnected(final Duration timeout) {
        try {
            client.connectWith().cleanSession(false).keepAlive(60).send()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("MQTT broker connection confirmed ({} ms budget)", timeout.toMillis());
        } catch (final Exception e) {
            log.warn("MQTT broker not reachable within {} ({}); continuing — automatic reconnect keeps trying",
                    timeout, e.toString());
        }
    }

    public void shutdown() {
        client.disconnect();
    }

    /** Current connection state (for the Actuator health indicator). */
    public boolean isConnected() {
        return client.getState().isConnected();
    }

    @Override
    public void publish(final String topic, final byte[] payload, final int qos, final boolean retained) {
        client.toBlocking().publishWith()
                .topic(topic)
                .payload(payload)
                .qos(qos(qos))
                .retain(retained)
                .send();
    }

    @Override
    public CompletableFuture<Void> publishAsync(final String topic, final byte[] payload, final int qos, final boolean retained) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        client.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(qos(qos))
                .retain(retained)
                .send()
                .whenComplete((publish, throwable) -> {
                    if (throwable != null) {
                        result.completeExceptionally(throwable);
                    } else {
                        result.complete(null);
                    }
                });
        return result;
    }

    @Override
    public void subscribe(final String topicFilter, final MqttMessageListener listener) {
        subscriptions.put(topicFilter, listener);
        // Subscribing before the initial connect completes would fail; the connected
        // callback re-subscribes everything, but try immediately when already up.
        if (client.getState().isConnected()) {
            doSubscribe(topicFilter, listener);
        }
    }

    private void resubscribeAll() {
        subscriptions.forEach((filter, listener) -> {
            try {
                doSubscribe(filter, listener);
            } catch (RuntimeException e) {
                log.error("Failed to re-subscribe to {} after reconnect", filter, e);
            }
        });
    }

    private void doSubscribe(final String topicFilter, final MqttMessageListener listener) {
        client.subscribeWith()
                .topicFilter(topicFilter)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(publish -> listener.messageArrived(
                        publish.getTopic().toString(),
                        publish.getPayload().isPresent() ? publish.getPayloadAsBytes() : new byte[0]))
                .send()
                .whenComplete((ack, throwable) -> {
                    if (throwable != null) {
                        log.error("Subscribe to {} failed: {}", topicFilter, throwable.toString());
                    }
                });
    }

    private static MqttQos qos(final int qos) {
        return qos >= 1 ? MqttQos.AT_LEAST_ONCE : MqttQos.AT_MOST_ONCE;
    }
}
