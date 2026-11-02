package mine.kartzan.galilei.model;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * MQTT 消息体，参考 sz-paxini-client 的 RobotMqttMessage。
 *
 * <p>下行指令：{@code command/code + data}
 * <p>上行结果：{@code replyMsgId + command/code + success + msg + data}
 */
@Data
public class RobotMqttMessage {

    private Long timestamp = System.currentTimeMillis();

    /** 本条消息自身的 ID */
    private String msgId = String.valueOf(IdUtil.getSnowflakeNextId());

    /** 回复的目标消息 ID（对应下发指令的 msgId） */
    private String replyMsgId;

    private String sn;

    /** 语义化指令名，如 navigate / standUp */
    private String command;

    /** 机器狗接口 code，如 402 / 108 */
    private Integer code;

    private Boolean success;

    /** 异步指令是否仍在等待最终结果：true=已受理待回调，false=最终结果 */
    private Boolean pending;

    private String msg;

    private JSONObject data;

    /** 二进制报文（如 protobuf）。不为空时按二进制发送，忽略 data。 */
    private byte[] rawPayload;

    public static RobotMqttMessage of(String command, Object data) {
        RobotMqttMessage message = new RobotMqttMessage();
        message.setCommand(command);
        message.setData(toJsonObject(data));
        return message;
    }

    public static RobotMqttMessage binary(byte[] rawPayload) {
        RobotMqttMessage message = new RobotMqttMessage();
        message.setRawPayload(rawPayload);
        return message;
    }

    private static JSONObject toJsonObject(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        return JSON.parseObject(JSON.toJSONString(data));
    }

    public String toJSON() {
        return JSON.toJSONString(this);
    }
}
