package nl.ctasoftware.crypto.ticker.server.config;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;

import java.util.UUID;

@Configuration
public class MqttConfig {

    @Bean
    IMqttClient mqttPahoClientFactory() throws MqttException {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        final String clientId = "crypto-ticker-" + UUID.randomUUID().toString().substring(0, 8);
        IMqttClient mqttClient = factory.getClientInstance("tcp://localhost:1883", clientId);

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(false);

        mqttClient.connect(mqttConnectOptions);

        return mqttClient;
    }
}
