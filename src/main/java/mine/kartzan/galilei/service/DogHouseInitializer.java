package mine.kartzan.galilei.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.api.RobotApiClient;
import mine.kartzan.galilei.api.dto.DogHouseParam;
import mine.kartzan.galilei.config.RobotProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 服务启动（重启）时通过 404 设置狗窝位置与安全位置。
 *
 * <p>机器狗侧狗窝配置可能丢失，因此本项目每次启动都按 {@code robot.dog-house.*} 重新下发一次：
 * 未配置 {@code robot.dog-house.xyz} 或 {@code enabled=false} 时跳过；
 * {@code safe-xyz} 未配置时安全位置取狗窝位置。
 *
 * <p>下发失败只记录日志，不影响服务启动与 MQTT 订阅。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DogHouseInitializer implements ApplicationRunner {

    private final RobotApiClient robotApi;
    private final RobotProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        RobotProperties.DogHouse dogHouse = properties.getDogHouse();
        if (!dogHouse.isEnabled()) {
            log.info("狗窝设置已关闭(robot.dog-house.enabled=false)，跳过 404 设置狗窝");
            return;
        }
        if (StringUtils.isBlank(dogHouse.getXyz())) {
            log.warn("未配置 robot.dog-house.xyz，跳过 404 设置狗窝");
            return;
        }

        DogHouseParam param = new DogHouseParam();
        param.setId(dogHouse.getId());
        param.setXyz(dogHouse.getXyz().trim());
        param.setSafeXyz(StringUtils.isBlank(dogHouse.getSafeXyz())
                ? param.getXyz()
                : dogHouse.getSafeXyz().trim());

        log.info("启动设置狗窝, id: {}, xyz: {}, safeXyz: {}", param.getId(), param.getXyz(), param.getSafeXyz());
        Optional<JSONObject> result;
        try {
            result = robotApi.setDogHouse(param);
        } catch (Exception e) {
            log.error("设置狗窝调用异常, id: {}, xyz: {}", param.getId(), param.getXyz(), e);
            return;
        }
        if (result.isPresent()) {
            log.info("设置狗窝成功, id: {}, 返回: {}", param.getId(), result.get());
        } else {
            log.error("设置狗窝失败, id: {}, xyz: {}, safeXyz: {}",
                    param.getId(), param.getXyz(), param.getSafeXyz());
        }
    }
}
