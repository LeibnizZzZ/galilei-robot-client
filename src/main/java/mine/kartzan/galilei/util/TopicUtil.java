package mine.kartzan.galilei.util;

/**
 * MQTT topic 占位符处理。
 */
public final class TopicUtil {

    private TopicUtil() {
    }

    /**
     * 将 topic 中的 {sn} 替换为实际机器狗标识。
     */
    public static String format(String topic, String sn) {
        if (topic == null) {
            return null;
        }
        return topic.replace("{sn}", sn == null ? "" : sn);
    }
}
