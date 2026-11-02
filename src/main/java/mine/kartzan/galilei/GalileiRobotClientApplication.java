package mine.kartzan.galilei;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 机器狗 MQTT 指令转发 / 中间桥接服务入口。
 *
 * 流程：MQTT 接收指令 -> 调用机器狗 HTTP 接口 -> 成功后 MQTT 回发结果。
 */
@SpringBootApplication
@EnableScheduling
public class GalileiRobotClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(GalileiRobotClientApplication.class, args);
    }
}
