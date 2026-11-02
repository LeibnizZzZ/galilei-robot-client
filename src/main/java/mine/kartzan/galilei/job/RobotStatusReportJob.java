package mine.kartzan.galilei.job;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.api.RobotApiClient;
import mine.kartzan.galilei.config.RobotProperties;
import mine.kartzan.galilei.enums.RobotCommandEnum;
import mine.kartzan.galilei.mqtt.MqttManager;
import mine.kartzan.galilei.model.RobotMqttMessage;
import mine.kartzan.galilei.util.TopicUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 定时轮询机器狗运行状态（电量、状态等），并通过 MQTT 上报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RobotStatusReportJob {

    private final MqttManager mqttManager;
    private final RobotApiClient robotApi;
    private final RobotProperties properties;

    @Scheduled(
            initialDelayString = "${robot.status.poll-interval-ms:1000}",
            fixedDelayString = "${robot.status.poll-interval-ms:1000}")
    public void report() {
        if (!properties.getStatus().isPollEnabled()) {
            return;
        }

        Optional<JSONObject> result;
        try {
            result = robotApi.getStatus();
        } catch (Exception e) {
            log.error("获取机器狗状态异常", e);
            return;
        }
        if (!result.isPresent()) {
            log.warn("获取机器狗状态失败");
            return;
        }

        RobotMqttMessage message = new RobotMqttMessage();
        message.setSn(properties.getSn());
        message.setCommand(RobotCommandEnum.STATUS.getCommand());
        message.setCode(RobotCommandEnum.STATUS.getCode());
        message.setSuccess(true);
        message.setMsg("ok");
        message.setData(result.get().getJSONObject("data"));

        String topic = TopicUtil.format(properties.getMqtt().getStatusTopic(), properties.getSn());
        mqttManager.publish(topic, message.toJSON());
    }
}
