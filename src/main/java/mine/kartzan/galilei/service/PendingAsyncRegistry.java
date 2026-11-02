package mine.kartzan.galilei.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步指令待回执登记表：记录「下发 code -> 最近一次下发的 msgId + 原始指令」，
 * 供机器狗通过 callBackMess 回传结果时关联原始指令。
 *
 * <p>机器狗方已确认：下发 {@code 402 一键导航} 会先返回请求成功（已受理），
 * 随后在<b>导航结束（到位）时</b>回调 {@code /api/robot/callBackMess}。
 * 回调的 {@code messType} 可能是 {@code 402}，也可能是 {@code 320}（启动导航回传），
 * 因此这里做 402 / 320 互相兜底。
 */
@Component
public class PendingAsyncRegistry {

    /** 待回执的异步指令：记录原始 msgId 与下发 code，回调时据此还原指令。 */
    public static class Pending {

        private final String msgId;

        private final int code;

        public Pending(String msgId, int code) {
            this.msgId = msgId;
            this.code = code;
        }

        public String getMsgId() {
            return msgId;
        }

        public int getCode() {
            return code;
        }
    }

    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();

    public void register(int code, String msgId) {
        if (msgId != null) {
            pending.put(code, new Pending(msgId, code));
        }
    }

    public Pending get(int code) {
        return pending.get(code);
    }

    /**
     * 按回调的 messType 关联原始异步指令。
     *
     * <p>优先按回调 code 命中；命中不到时在 402 / 320 之间互相兜底。
     */
    public Pending removeByCallbackCode(Integer callbackCode) {
        if (callbackCode == null) {
            return null;
        }
        Pending hit = pending.remove(callbackCode);
        if (hit == null) {
            Integer alias = aliasOf(callbackCode);
            if (alias != null) {
                hit = pending.remove(alias);
            }
        }
        return hit;
    }

    private Integer aliasOf(int code) {
        if (code == 320) {
            return 402;
        }
        if (code == 402) {
            return 320;
        }
        return null;
    }
}
