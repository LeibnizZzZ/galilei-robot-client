package mine.kartzan.galilei.model;

import lombok.Data;

/**
 * 机器狗异步回调消息：POST /api/robot/callBackMess
 *
 * <pre>
 * {
 *   "ip": "192.168.100.106",
 *   "port": 8888,
 *   "messType": 320,   // 消息类型；一键导航 402 的回调可能为 402 或 320
 *   "messVal": "1",    // 1 成功 0 失败；导航场景 1 表示已到位
 *   "readme": ""       // 失败说明
 * }
 * </pre>
 *
 * <p>机器狗方已确认：一键导航（402）先返回请求成功（已受理），
 * 在<b>导航结束（到位）时</b>才回调本消息。
 */
@Data
public class CallbackMessage {

    private String ip;

    private Integer port;

    private Integer messType;

    private String messVal;

    private String readme;
}
