package mine.kartzan.galilei.mqtt;

import mine.kartzan.galilei.model.RobotMqttMessage;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.function.BiConsumer;

/**
 * MQTT 管理器接口，参考 sz-paxini-client。
 */
public interface MqttManager {

    void connect(String brokerUrl, String clientId, String username, String password, Integer qos) throws MqttException;

    void subscribe(String topic, BiConsumer<String, String> callback);

    void subscribe(String topic);

    MqttRequest message(String publishTopic, RobotMqttMessage message);

    MqttRequest message(String publishTopic, String responseTopic, RobotMqttMessage message);

    /**
     * 发送请求。默认按 JSON 报文发送；二进制协议可覆写此方法，
     * 或读取 {@link RobotMqttMessage#getRawPayload()} 后按二进制发送。
     */
    default void publishRequest(MqttRequest request) {
        RobotMqttMessage message = request.getMessage();
        if (message.getRawPayload() != null) {
            publishBytes(request.getPublishTopic(), message.getRawPayload());
        } else {
            publish(request.getPublishTopic(), message.toJSON());
        }
    }

    /**
     * 发送二进制报文，默认实现不支持。
     */
    default void publishBytes(String topic, byte[] payload) {
        throw new UnsupportedOperationException("当前MQTT管理器不支持二进制消息");
    }

    void publish(String topic, String payload);

    void disconnect();

    boolean isConnected();

    default void removePendingRequest(String msgId) {
        throw new IllegalArgumentException("不支持此方法");
    }
}
