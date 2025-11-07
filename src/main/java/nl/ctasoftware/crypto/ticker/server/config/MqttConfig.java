package nl.ctasoftware.crypto.ticker.server.config;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;

@Configuration
public class MqttConfig {

    @Bean
    IMqttClient mqttPahoClientFactory() throws MqttException {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        IMqttClient mqttClient = factory.getClientInstance("tcp://localhost:1883", "crypto-ticker");

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);

        mqttClient.connect(mqttConnectOptions);

        return mqttClient;
    }
}
