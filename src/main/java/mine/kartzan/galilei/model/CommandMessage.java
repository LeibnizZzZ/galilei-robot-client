package mine.kartzan.galilei.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * 平台通过 MQTT 下发的指令。
 *
 * <pre>
 * {
 *   "msgId": "1234567890",
 *   "command": "navigate",          // 语义化指令名，与 code 二选一
 *   "code": 402,                    // 也可直接下发机器狗接口 code
 *   "sn": "galilei-001",
 *   "data": { "presetCode": "10", "xyz": "1.03,-0.30,1.03" }
 * }
 * </pre>
 */
@Data
public class CommandMessage {

    private String msgId;

    private String command;

    private Integer code;

    private String sn;

    private JSONObject data;
}
