package mine.kartzan.galilei.api.dto;

import lombok.Data;

/**
 * 机器狗通用请求体：POST /robot/api/common
 */
@Data
public class RobotCommonRequest {

    /** 指令码 */
    private Integer code;

    /** 请求参数 JSON 字符串 */
    private String reqJson;
}
