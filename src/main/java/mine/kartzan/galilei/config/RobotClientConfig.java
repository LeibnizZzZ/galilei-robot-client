package mine.kartzan.galilei.config;

import mine.kartzan.galilei.api.RobotApiClient;
import mine.kartzan.galilei.mqtt.RobotMqttManager;
import mine.kartzan.galilei.mqtt.MqttManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手动装配机器狗 API 客户端与 MQTT 管理器。
 */
@Configuration
public class RobotClientConfig {

    @Bean
    public MqttManager mqttManager() {
        return new RobotMqttManager();
    }

    @Bean
    public RobotApiClient galileiRobotApi(RobotProperties properties) {
        RobotProperties.Api api = properties.getApi();
        return new RobotApiClient(
                api.getBaseUrl(),
                api.getConnectTimeoutSeconds(),
                api.getReadTimeoutSeconds(),
                api.getMaxRetries());
    }
}
