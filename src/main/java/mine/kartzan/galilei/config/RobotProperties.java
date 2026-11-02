package mine.kartzan.galilei.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 机器狗客户端配置，对应 application.yml 中的 robot.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "robot")
public class RobotProperties {

    /** 机器狗唯一标识 */
    private String sn = "galilei-001";

    private Api api = new Api();

    private Mqtt mqtt = new Mqtt();

    private Status status = new Status();

    private DogHouse dogHouse = new DogHouse();

    private Point point = new Point();

    @Data
    public static class Api {
        /** 机器狗本体 HTTP 地址 */
        private String baseUrl = "http://127.0.0.1:8888";
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 30;
        /** 调用失败重试次数（不含首次） */
        private int maxRetries = 2;
    }

    @Data
    public static class Mqtt {
        private String broker = "tcp://127.0.0.1:1883";
        private String username = "robot";
        private String password = "robot123";
        private int qos = 1;
        private String commandTopic = "robot/{sn}/command";
        private String replyTopic = "robot/{sn}/reply";
        private String statusTopic = "robot/{sn}/status";
    }

    @Data
    public static class Status {
        private boolean pollEnabled = true;
        private long pollIntervalMs = 1000L;
    }

    /**
     * 狗窝（充电桩）配置：服务启动时通过 404 设置狗窝位置与安全位置。
     */
    @Data
    public static class DogHouse {
        /** 是否在服务启动时下发狗窝设置 */
        private boolean enabled = true;
        /** 狗窝 ID */
        private Long id = 1L;
        /** 狗窝位置，格式 {@code x,y,z[,w]}（z 为朝向）；为空则跳过设置 */
        private String xyz;
        /** 安全位置，格式同 {@link #xyz}；为空则与狗窝位置相同 */
        private String safeXyz;
    }

    /**
     * 本地点位：{@code savePoint} 保存的点位写入该 JSON 文件，{@code batchReportPoints} 从该文件读取回传。
     */
    @Data
    public static class Point {
        /** 点位 JSON 文件路径，相对服务启动目录 */
        private String file = "./data/points.json";
    }
}
