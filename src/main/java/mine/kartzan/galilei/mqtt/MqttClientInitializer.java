package mine.kartzan.galilei.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.config.RobotProperties;
import mine.kartzan.galilei.service.RobotCommandService;
import mine.kartzan.galilei.util.TopicUtil;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

/**
 * 应用启动后连接 MQTT 并订阅指令 topic。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class MqttClientInitializer implements ApplicationRunner {

    private final MqttManager mqttManager;
    private final RobotCommandService commandHandler;
    private final RobotProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        RobotProperties.Mqtt mqtt = properties.getMqtt();
        String clientId = "galilei-robot-client-" + properties.getSn() + System.currentTimeMillis();

        mqttManager.connect(mqtt.getBroker(), clientId, mqtt.getUsername(), mqtt.getPassword(), mqtt.getQos());

        String commandTopic = TopicUtil.format(mqtt.getCommandTopic(), properties.getSn());
        mqttManager.subscribe(commandTopic, commandHandler::handle);

        log.info("MQTT 初始化完成, broker: {}, commandTopic: {}, replyTopic: {}, statusTopic: {}",
                mqtt.getBroker(), commandTopic,
                TopicUtil.format(mqtt.getReplyTopic(), properties.getSn()),
                TopicUtil.format(mqtt.getStatusTopic(), properties.getSn()));
    }

    @PreDestroy
    public void shutdown() {
        log.info("应用关闭，断开MQTT连接");
        mqttManager.disconnect();
    }
}
