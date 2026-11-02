package mine.kartzan.galilei.api.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 设置狗窝参数（指令 404）。
 *
 * <pre>
 * {
 *   "id": 123456798,                            // 狗窝ID
 *   "xyz": "1.037931,-0.309902,1.030920",       // 狗窝位置
 *   "safe_xyz": "1.037931,-0.309902,1.030920"   // 安全位置
 * }
 * </pre>
 *
 * <p>坐标格式为 {@code x,y,z[,w]}，其中 z 为朝向（机器狗方已确认）。
 * 注意接口字段名为下划线形式 {@code safe_xyz}。
 */
@Data
public class DogHouseParam {

    /** 狗窝 ID */
    private Long id;

    /** 狗窝位置，格式 {@code x,y,z[,w]}，其中 z 为朝向 */
    private String xyz;

    /** 安全位置，格式同 {@link #xyz}；为空时下发前回填为 {@link #xyz} */
    @JSONField(name = "safe_xyz")
    private String safeXyz;
}
