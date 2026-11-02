package mine.kartzan.galilei.mqtt;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.model.RobotMqttMessage;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MQTT 请求 / 响应封装，参考 sz-paxini-client。
 */
@Slf4j
@Data
public class MqttRequest {

    private final String msgId;
    private final String publishTopic;
    private final String responseTopic;
    private final RobotMqttMessage message;
    private final MqttManager mqttManager;
    private String receiveMsgId;

    private Consumer<RobotMqttMessage> onSuccess;
    private Consumer<Throwable> onError;

    private volatile RobotMqttMessage result;
    private volatile Throwable error;
    private volatile boolean completed = false;
    private volatile boolean cancelled = false;

    private Long waitingTimeMills = 0L;

    public MqttRequest(String msgId, String publishTopic, String responseTopic, RobotMqttMessage message, MqttManager mqttManager) {
        this.msgId = msgId;
        this.publishTopic = publishTopic;
        this.responseTopic = responseTopic;
        this.message = message;
        this.mqttManager = mqttManager;
    }

    /**
     * 设置成功回调。
     */
    public MqttRequest finish(Consumer<RobotMqttMessage> onSuccess) {
        this.onSuccess = onSuccess;
        return this;
    }

    /**
     * 设置失败回调。
     */
    public MqttRequest fail(Consumer<Throwable> onError) {
        this.onError = onError;
        return this;
    }

    /**
     * 发送消息。
     */
    public void send() {
        if (isDone()) {
            log.info("MqttRequest 已完成或已取消，跳过发送, msgId: {}", msgId);
            return;
        }
        try {
            mqttManager.publishRequest(this);
            log.info("发送MQTT消息, msgId: {}, topic: {}, payload: {}", msgId, publishTopic, message.toJSON());
        } catch (Exception e) {
            log.error("发送MQTT消息失败, msgId: {}, topic: {}", msgId, publishTopic, e);
            completeExceptionally(e);
        }
    }

    /**
     * 取消等待。
     */
    public boolean cancel() {
        if (completed || cancelled) {
            return false;
        }
        cancelled = true;
        mqttManager.removePendingRequest(msgId);
        log.info("MqttRequest 已取消, msgId: {}", msgId);
        return true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isDone() {
        return completed || cancelled;
    }

    /**
     * 阻塞等待响应。
     */
    public RobotMqttMessage waiting() {
        try {
            return waitForResult(0, null);
        } finally {
            mqttManager.removePendingRequest(msgId);
            executeCallbacks();
        }
    }

    public RobotMqttMessage waiting(Long timeoutSeconds) {
        try {
            return waitForResult(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            mqttManager.removePendingRequest(msgId);
            executeCallbacks();
        }
    }

    public void complete(RobotMqttMessage response) {
        if (completed || cancelled) {
            return;
        }
        this.result = response;
        this.completed = true;
        this.receiveMsgId = response.getMsgId();
        executeCallbacks();
    }

    public void completeExceptionally(Throwable error) {
        if (completed || cancelled) {
            return;
        }
        this.error = error;
        this.completed = true;
        executeCallbacks();
    }

    private void executeCallbacks() {
        if (error != null) {
            if (onError != null) {
                try {
                    onError.accept(error);
                } catch (Exception e) {
                    log.error("MqttRequest 失败回调执行异常, msgId: {}", msgId, e);
                }
            } else {
                log.error("MqttRequest 执行失败, msgId: {}", msgId, error);
            }
        } else if (onSuccess != null) {
            try {
                onSuccess.accept(result);
            } catch (Exception e) {
                log.error("MqttRequest 成功回调执行异常, msgId: {}", msgId, e);
            }
        }
    }

    private RobotMqttMessage waitForResult(long timeout, TimeUnit unit) {
        long timeoutMillis = (timeout > 0 && unit != null) ? unit.toMillis(timeout) : 0;

        while (!completed && !cancelled) {
            if (timeoutMillis > 0 && waitingTimeMills >= timeoutMillis) {
                break;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            waitingTimeMills += 100L;
        }

        if (error != null) {
            throw new RuntimeException(error);
        }
        return result;
    }
}
