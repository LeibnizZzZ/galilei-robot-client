package mine.kartzan.galilei.controller;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.config.RobotProperties;
import mine.kartzan.galilei.enums.RobotCommandEnum;
import mine.kartzan.galilei.service.PendingAsyncRegistry;
import mine.kartzan.galilei.mqtt.MqttManager;
import mine.kartzan.galilei.model.CallbackMessage;
import mine.kartzan.galilei.model.RobotMqttMessage;
import mine.kartzan.galilei.util.TopicUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 接收机器狗异步指令的最终结果回调，并转发为 MQTT 消息。
 *
 * <p>机器狗方已确认：下发 {@code 402 一键导航} 后，HTTP 先返回请求成功（已受理），
 * 随后在<b>导航结束（到位）时</b>回调本接口；回调 {@code messVal=1} 表示导航到达成功。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RobotCallbackController {

    private final MqttManager mqttManager;
    private final RobotProperties properties;
    private final PendingAsyncRegistry pendingAsyncRegistry;

    @PostMapping("/api/robot/callBackMess")
    public Map<String, Object> callBackMess(@RequestBody CallbackMessage body) {
        log.info("收到机器狗回调: {}", body);

        Integer messType = body.getMessType();
        boolean success = "1".equals(body.getMessVal());

        // 关联原始异步指令：回调 messType 可能为 320，而下发的是 402，
        // 用登记表还原原始 code / msgId，保证上行结果与首次「已受理」消息一致。
        PendingAsyncRegistry.Pending pending = pendingAsyncRegistry.removeByCallbackCode(messType);
        Integer replyCode = pending != null ? pending.getCode() : messType;
        RobotCommandEnum command = RobotCommandEnum.fromCode(replyCode);

        RobotMqttMessage reply = new RobotMqttMessage();
        reply.setReplyMsgId(pending != null ? pending.getMsgId() : null);
        reply.setSn(properties.getSn());
        reply.setCommand(command != null ? command.getCommand() : null);
        reply.setCode(replyCode);
        reply.setSuccess(success);
        reply.setPending(false);
        reply.setMsg(success ? "ok" : body.getReadme());

        JSONObject data = new JSONObject();
        data.put("ip", body.getIp());
        data.put("port", body.getPort());
        data.put("messType", messType);
        data.put("messVal", body.getMessVal());
        data.put("readme", body.getReadme());
        reply.setData(data);

        String topic = TopicUtil.format(properties.getMqtt().getReplyTopic(), properties.getSn());
        mqttManager.publish(topic, reply.toJSON());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("msg", "ok");
        return response;
    }
}
