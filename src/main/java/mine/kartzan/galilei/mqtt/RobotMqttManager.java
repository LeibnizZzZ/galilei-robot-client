package mine.kartzan.galilei.mqtt;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.model.RobotMqttMessage;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 基于 Eclipse Paho 的 MQTT 管理器实现，参考 sz-paxini-client 的 RobotManagerImpl。
 * 支持自动重连、重连后自动重订阅、请求/响应关联。
 */
@Slf4j
public class RobotMqttManager implements MqttManager {

    private MqttClient client;
    private MqttConnectOptions connOpts;
    private final ConcurrentHashMap<String, MqttRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BiConsumer<String, String>> topicCallbacks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> subscribedTopics = new CopyOnWriteArrayList<>();

    private Integer qos = 1;

    public RobotMqttManager() {
    }

    @Override
    public void connect(String brokerUrl, String clientId, String username, String password, Integer qos) throws MqttException {
        if (client != null && client.isConnected()) {
            disconnect();
        }

        connOpts = new MqttConnectOptions();
        connOpts.setCleanSession(true);
        connOpts.setConnectionTimeout(30);
        connOpts.setKeepAliveInterval(60);
        connOpts.setAutomaticReconnect(true);
        if (StringUtils.isNotEmpty(username)) {
            connOpts.setUserName(username);
        }
        if (StringUtils.isNotEmpty(password)) {
            connOpts.setPassword(password.toCharArray());
        }
        this.qos = qos == null ? 1 : qos;

        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("MQTT连接成功: reconnect={}, serverURI={}", reconnect, serverURI);
                if (reconnect) {
                    resubscribeAllTopics();
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT连接断开，将自动重连", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                handleMessage(topic, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                log.debug("MQTT消息投递完成");
            }
        });

        client.connect(connOpts);
        log.info("MQTT客户端已连接到: {}, clientId={}", brokerUrl, clientId);
    }

    @Override
    public void subscribe(String topic, BiConsumer<String, String> callback) {
        try {
            client.subscribe(topic, qos);
            topicCallbacks.put(topic, callback);
            subscribedTopics.add(topic);
            log.info("已订阅topic并注册回调: {}", topic);
        } catch (MqttException e) {
            log.error("订阅topic失败: {}", topic, e);
        }
    }

    @Override
    public void subscribe(String topic) {
        try {
            client.subscribe(topic, qos);
            subscribedTopics.add(topic);
            log.info("已订阅topic: {}", topic);
        } catch (MqttException e) {
            log.error("订阅topic失败: {}", topic, e);
        }
    }

    private void handleMessage(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            log.info("收到MQTT消息, topic: {}, qos:{}, payload: {}", topic, message.getQos(), payload);
            handleReplyMessage(topic, payload);
            BiConsumer<String, String> callback = topicCallbacks.get(topic);
            if (callback != null) {
                callback.accept(topic, payload);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败, topic: {}", topic, e);
        }
    }

    /**
     * 处理请求/响应关联：若收到的消息 replyMsgId 命中等待中的请求，则完成它。
     */
    private void handleReplyMessage(String topic, String payload) {
        try {
            JSONObject json = JSON.parseObject(payload);
            if (json == null) {
                return;
            }
            String msgId = json.getString("replyMsgId");
            if (StringUtils.isEmpty(msgId)) {
                msgId = json.getString("msgId");
            }
            if (StringUtils.isEmpty(msgId)) {
                return;
            }

            MqttRequest request = pendingRequests.get(msgId);
            if (request != null && topic.equals(request.getResponseTopic())) {
                pendingRequests.remove(msgId);
                RobotMqttMessage responseMessage = JSON.parseObject(payload, RobotMqttMessage.class);
                if (Boolean.FALSE.equals(responseMessage.getSuccess())) {
                    request.completeExceptionally(new RuntimeException(responseMessage.getMsg()));
                }
                request.complete(responseMessage);
                log.info("处理响应消息, msgId: {}, topic: {}", msgId, topic);
            }
        } catch (Exception e) {
            log.error("处理响应消息失败, topic: {}", topic, e);
        }
    }

    private MqttRequest createRequest(String publishTopic, String responseTopic, RobotMqttMessage message) {
        MqttRequest request = new MqttRequest(message.getMsgId(), publishTopic, responseTopic, message, this);
        pendingRequests.put(message.getMsgId(), request);
        return request;
    }

    @Override
    public MqttRequest message(String publishTopic, RobotMqttMessage message) {
        return message(publishTopic, null, message);
    }

    @Override
    public MqttRequest message(String publishTopic, String responseTopic, RobotMqttMessage message) {
        return createRequest(publishTopic, responseTopic, message);
    }

    @Override
    public void publish(String topic, String payload) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage mqttMessage = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                mqttMessage.setQos(qos);
                client.publish(topic, mqttMessage);
                log.info("发布MQTT消息, topic: {}, payload: {}", topic, payload);
            } else {
                log.warn("MQTT客户端未连接，无法发布消息, topic: {}", topic);
            }
        } catch (MqttException e) {
            log.error("发布MQTT消息失败, topic: {}", topic, e);
        }
    }

    @Override
    public void publishBytes(String topic, byte[] payload) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage mqttMessage = new MqttMessage(payload);
                mqttMessage.setQos(qos);
                client.publish(topic, mqttMessage);
                log.info("发布MQTT二进制消息, topic: {}, 长度: {}字节", topic, payload.length);
            } else {
                log.warn("MQTT客户端未连接，无法发布消息, topic: {}", topic);
            }
        } catch (MqttException e) {
            log.error("发布MQTT二进制消息失败, topic: {}", topic, e);
        }
    }

    @Override
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                log.info("MQTT客户端已断开连接");
            }
        } catch (MqttException e) {
            log.error("断开MQTT连接失败", e);
        }
    }

    @Override
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override
    public void removePendingRequest(String msgId) {
        pendingRequests.remove(msgId);
    }

    private void resubscribeAllTopics() {
        log.info("开始重新订阅所有已注册的topic，共{}个", subscribedTopics.size());
        for (String topic : subscribedTopics) {
            try {
                client.subscribe(topic, qos);
                log.info("重新订阅topic成功: {}", topic);
            } catch (MqttException e) {
                log.error("重新订阅topic失败: {}", topic, e);
            }
        }
    }
}
