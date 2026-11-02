package mine.kartzan.galilei.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.api.RobotApiClient;
import mine.kartzan.galilei.api.dto.PointInfo;
import mine.kartzan.galilei.api.dto.PointReport;
import mine.kartzan.galilei.config.RobotProperties;
import mine.kartzan.galilei.enums.RobotCommandEnum;
import mine.kartzan.galilei.mqtt.MqttManager;
import mine.kartzan.galilei.model.CommandMessage;
import mine.kartzan.galilei.model.RobotMqttMessage;
import mine.kartzan.galilei.util.TopicUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 指令处理：解析 MQTT 指令 -> 调用机器狗接口 -> MQTT 回发结果。
 *
 * <p>其中点位相关的三条指令不走机器狗指令下发：
 * {@code savePoint} 取机器狗当前坐标（401 状态接口）写入本地点位 JSON，
 * {@code deletePoint} 按 id / 点位名 / outsideId 从该 JSON 删除一条点位，
 * {@code batchReportPoints} 把该 JSON 里的点位原样回传（见 {@link PointStorageService}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RobotCommandService {

    private final MqttManager mqttManager;
    private final RobotApiClient robotApi;
    private final RobotProperties properties;
    private final PendingAsyncRegistry pendingAsyncRegistry;
    private final PointStorageService pointStorageService;

    /**
     * 处理一条 MQTT 指令消息。
     */
    public void handle(String topic, String payload) {
        CommandMessage commandMessage;
        try {
            commandMessage = JSON.parseObject(payload, CommandMessage.class);
        } catch (Exception e) {
            log.error("解析MQTT指令失败, topic: {}, payload: {}", topic, payload, e);
            return;
        }
        if (commandMessage == null || StringUtils.isBlank(commandMessage.getMsgId())) {
            log.warn("MQTT指令缺少 msgId, 已忽略, topic: {}, payload: {}", topic, payload);
            return;
        }

        RobotCommandEnum command = resolve(commandMessage);
        if (command == null) {
            log.warn("不支持的指令, msgId: {}, command: {}, code: {}",
                    commandMessage.getMsgId(), commandMessage.getCommand(), commandMessage.getCode());
            publishResult(commandMessage, null, false, false, "不支持的指令", null);
            return;
        }

        log.info("处理指令, msgId: {}, command: {}, code: {}, data: {}",
                commandMessage.getMsgId(), command.getCommand(), command.getCode(), commandMessage.getData());

        // 纯本地点位指令：删除点位 / 批量点位上报都只操作本地 JSON，不下发机器狗
        if (command == RobotCommandEnum.DELETE_POINT) {
            deletePoint(commandMessage, command);
            return;
        }
        if (command == RobotCommandEnum.BATCH_REPORT_POINTS) {
            reportPoints(commandMessage, command);
            return;
        }

        Optional<JSONObject> result;
        try {
            result = command == RobotCommandEnum.SAVE_POINT
                    ? robotApi.getPoint()
                    : robotApi.call(command, commandMessage.getData());
        } catch (Exception e) {
            log.error("调用机器狗接口异常, msgId: {}, command: {}", commandMessage.getMsgId(), command, e);
            publishResult(commandMessage, command, false, false, "调用机器狗接口异常: " + e.getMessage(), null);
            return;
        }

        if (result.isPresent()) {
            if (command == RobotCommandEnum.SAVE_POINT) {
                // 保存点位：取 401 状态里的当前坐标，按平台点位格式写入本地 JSON（创建点位）
                savePoint(commandMessage, command, result.get());
            } else if (command.isAsync()) {
                // 异步指令：HTTP 200 仅代表已受理；一键导航会在到位时回调 callBackMess
                pendingAsyncRegistry.register(command.getCode(), commandMessage.getMsgId());
                log.info("异步指令已受理, msgId: {}, command: {}, 等待到位回调",
                        commandMessage.getMsgId(), command.getCommand());
                publishResult(commandMessage, command, true, true, "指令已受理，等待执行结果", extractData(result.get()));
            } else {
                publishResult(commandMessage, command, true, false, "ok", extractData(result.get()));
            }
        } else {
            log.error("机器狗接口调用失败, msgId: {}, command: {}", commandMessage.getMsgId(), command.getCommand());
            publishResult(commandMessage, command, false, false, "机器狗接口调用失败", null);
        }
    }

    /**
     * 保存点位（创建点位）：把机器狗当前坐标按平台点位格式写入本地点位 JSON，回执里带落盘后的点位。
     */
    private void savePoint(CommandMessage commandMessage, RobotCommandEnum command, JSONObject response) {
        PointInfo point;
        try {
            PointInfo current = PointInfo.from(PointReport.from(extractData(response)), commandMessage.getData());
            point = pointStorageService.save(current);
        } catch (Exception e) {
            log.error("保存点位失败, msgId: {}", commandMessage.getMsgId(), e);
            publishResult(commandMessage, command, false, false, "保存点位失败: " + e.getMessage(), null);
            return;
        }
        publishResult(commandMessage, command, true, false, "ok", point.toJson());
    }

    /**
     * 删除点位：按 {@code id} / {@code pointName} / {@code outsideId} 从本地点位 JSON 里删掉一条点位，
     * 回执里带被删掉的点位。
     */
    private void deletePoint(CommandMessage commandMessage, RobotCommandEnum command) {
        JSONObject data = commandMessage.getData();
        PointInfo deleted;
        try {
            deleted = pointStorageService.delete(
                    data == null ? null : data.getInteger("id"),
                    data == null ? null : data.getString("pointName"),
                    data == null ? null : data.getInteger("outsideId"));
        } catch (IllegalArgumentException e) {
            log.warn("删除点位失败, msgId: {}, data: {}, 原因: {}", commandMessage.getMsgId(), data, e.getMessage());
            publishResult(commandMessage, command, false, false, e.getMessage(), null);
            return;
        } catch (Exception e) {
            log.error("删除点位异常, msgId: {}", commandMessage.getMsgId(), e);
            publishResult(commandMessage, command, false, false, "删除点位失败: " + e.getMessage(), null);
            return;
        }
        publishResult(commandMessage, command, true, false, "ok", deleted.toJson());
    }

    /**
     * 批量点位上报：读取本地点位 JSON，把里面的点位原样回传。
     */
    private void reportPoints(CommandMessage commandMessage, RobotCommandEnum command) {
        JSONObject snapshot;
        try {
            snapshot = pointStorageService.snapshot();
        } catch (Exception e) {
            log.error("读取本地点位失败, msgId: {}", commandMessage.getMsgId(), e);
            publishResult(commandMessage, command, false, false, "读取本地点位失败: " + e.getMessage(), null);
            return;
        }
        log.info("批量点位上报, msgId: {}, 共{}个点位", commandMessage.getMsgId(), snapshot.getIntValue("total"));
        publishResult(commandMessage, command, true, false, "ok", snapshot);
    }

    /**
     * 优先取机器人返回 data 字段，否则返回完整响应体。
     */
    private JSONObject extractData(JSONObject response) {
        JSONObject data = response.getJSONObject("data");
        return data != null ? data : response;
    }

    /**
     * 优先按 command 名解析，其次按 code 解析。
     */
    private RobotCommandEnum resolve(CommandMessage commandMessage) {
        if (StringUtils.isNotBlank(commandMessage.getCommand())) {
            return RobotCommandEnum.fromCommand(commandMessage.getCommand());
        }
        return RobotCommandEnum.fromCode(commandMessage.getCode());
    }

    /**
     * 组装并发布指令执行结果。
     */
    private void publishResult(CommandMessage commandMessage, RobotCommandEnum command, boolean success, boolean pending, String msg, JSONObject data) {
        RobotMqttMessage reply = new RobotMqttMessage();
        reply.setReplyMsgId(commandMessage.getMsgId());
        reply.setSn(properties.getSn());
        reply.setCommand(command != null ? command.getCommand() : commandMessage.getCommand());
        // 注意用 Integer.valueOf 包一层：否则与 commandMessage.getCode()（Integer）组成三元表达式时
        // 会按数值提升拆箱，指令名下发且没带 code 的“不支持的指令”回执会直接 NPE
        reply.setCode(command != null ? Integer.valueOf(command.getCode()) : commandMessage.getCode());
        reply.setSuccess(success);
        reply.setPending(pending);
        reply.setMsg(msg);
        reply.setData(data);

        String topic = TopicUtil.format(properties.getMqtt().getReplyTopic(), properties.getSn());
        mqttManager.publish(topic, reply.toJSON());
    }
}
