package mine.kartzan.galilei.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.api.dto.DogHouseParam;
import mine.kartzan.galilei.api.dto.NavigateParam;
import mine.kartzan.galilei.api.dto.RobotCommonRequest;
import mine.kartzan.galilei.enums.RobotCommandEnum;

import java.util.Optional;

/**
 * 机器狗中间层 API 客户端。
 *
 * <p>所有指令统一走 POST /robot/api/common，请求体 { code, reqJson }。
 */
@Slf4j
public class RobotApiClient extends BaseApiClient {

    private static final String COMMON_PATH = "/robot/api/common";

    private final int maxRetries;
    private final String commonUrl;

    public RobotApiClient(String baseUrl, int connectTimeoutSeconds, int readTimeoutSeconds, int maxRetries) {
        super(connectTimeoutSeconds, readTimeoutSeconds);
        this.baseUrl = baseUrl;
        this.maxRetries = maxRetries;
        this.commonUrl = trimTrailingSlash(baseUrl) + COMMON_PATH;
    }

    /**
     * 按指令枚举下发指令。
     */
    public Optional<JSONObject> call(RobotCommandEnum command, Object reqJson) {
        return call(command.getCode(), reqJson);
    }

    /**
     * 按原始 code 下发指令，带重试。
     */
    public Optional<JSONObject> call(int code, Object reqJson) {
        RobotCommonRequest request = new RobotCommonRequest();
        request.setCode(code);
        request.setReqJson(toReqJson(reqJson));

        Optional<JSONObject> result = executePostRequest(commonUrl, request);
        for (int i = 1; i <= maxRetries && !result.isPresent(); i++) {
            log.warn("指令下发失败，第 {}/{} 次重试, code={}", i, maxRetries, code);
            result = executePostRequest(commonUrl, request);
        }
        return result;
    }

    // ==================== 业务方法 ====================

    /** 导航到指定坐标（一键导航，异步） */
    public Optional<JSONObject> navigate(NavigateParam param) {
        return call(RobotCommandEnum.NAVIGATE, param);
    }

    /** 开始导航（异步） */
    public Optional<JSONObject> startNavigate(NavigateParam param) {
        return call(RobotCommandEnum.START_NAVIGATE, param);
    }

    /** 结束导航 */
    public Optional<JSONObject> stopNavigate() {
        return call(RobotCommandEnum.STOP_NAVIGATE, null);
    }

    /** 起立 */
    public Optional<JSONObject> standUp() {
        return call(RobotCommandEnum.STAND_UP, null);
    }

    /** 趴下 / 站下 */
    public Optional<JSONObject> lieDown() {
        return call(RobotCommandEnum.LIE_DOWN, null);
    }

    /** 一键返航（回桩充电，异步） */
    public Optional<JSONObject> charge() {
        return call(RobotCommandEnum.CHARGE, null);
    }

    /** 获取运行状态（电量、状态等） */
    public Optional<JSONObject> getStatus() {
        return call(RobotCommandEnum.STATUS, null);
    }

    /** 获取当前点位（坐标），底层走 401 状态接口 */
    public Optional<JSONObject> getPoint() {
        return getStatus();
    }

    /** 设置狗窝（狗窝位置和安全位置） */
    public Optional<JSONObject> setDogHouse(DogHouseParam param) {
        return call(RobotCommandEnum.SET_DOG_HOUSE, param);
    }

    // ==================== 内部工具 ====================

    private String toReqJson(Object reqJson) {
        if (reqJson == null) {
            return "{}";
        }
        if (reqJson instanceof String) {
            return (String) reqJson;
        }
        return JSON.toJSONString(reqJson);
    }

    private static String trimTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
