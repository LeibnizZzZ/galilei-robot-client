package mine.kartzan.galilei.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.config.RobotProperties;
import mine.kartzan.galilei.model.FileUploadMessage;
import mine.kartzan.galilei.model.RobotMqttMessage;
import mine.kartzan.galilei.mqtt.MqttManager;
import mine.kartzan.galilei.util.TopicUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 接收机器狗「业务文件已上传」通知，并转发为 MQTT 消息。
 *
 * <p>机器狗通过 FTP 将业务文件（PCD 图、灰度图、YAML、日志等）上传到服务器指定目录后，
 * 调用本接口通知服务器；本接口不做文件落盘，仅记录日志并将通知转发到
 * {@code robot/{sn}/reply}，由平台侧决定是否读取 / 处理该文件。
 *
 * <p>与 {@link RobotCallbackController} 一样，本接口属于「机器狗 -> 平台」的上行事件通道。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RobotFileUploadController {

    /** MQTT 指令名：机器狗通知业务文件已上传 */
    private static final String COMMAND_UPLOAD_FILE = "uploadFile";

    private final MqttManager mqttManager;
    private final RobotProperties properties;

    @PostMapping("/api/robot/uploadFile")
    public Map<String, Object> uploadFile(@RequestBody FileUploadMessage body) {
        log.info("收到机器狗文件上传通知: {}", body);

        RobotMqttMessage message = new RobotMqttMessage();
        message.setSn(properties.getSn());
        message.setCommand(COMMAND_UPLOAD_FILE);
        message.setMsg("文件已上传");
        message.setData(toData(body));

        String topic = TopicUtil.format(properties.getMqtt().getReplyTopic(), properties.getSn());
        mqttManager.publish(topic, message.toJSON());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("msg", "ok");
        return response;
    }

    /**
     * 按接口文档原样转发上传信息，不做字段裁剪。
     */
    private JSONObject toData(FileUploadMessage body) {
        JSONObject data = new JSONObject();
        data.put("fileType", body.getFileType());
        data.put("filePath", body.getFilePath());
        data.put("ip", body.getIp());
        data.put("port", body.getPort());
        return data;
    }
}
