package mine.kartzan.galilei.api.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;

/**
 * 本地点位：字段与 sz-paxini-client 的 {@code RobotLocationsMapPoint} 对齐，
 * 即参考 {@code PaxiniApi} 里点位上传（批量点位上报）所用的报文格式。
 *
 * <p><b>坐标语义</b>：机器狗坐标是 {@code xyzw}，其中 x、y 为位置，<b>z 为朝向</b>（不是 z 轴高度），
 * 因此这里 {@link #pointPositionZ} 固定为 {@code "0"}，朝向写入 {@link #rotation}
 * （与 {@code PaxiniApi} 上报点位时的写法一致）。
 *
 * <p>{@link #devicesId} / {@link #locationId} 桥接服务不知道，由平台下发 {@code savePoint} 时带上；
 * 缺失则留空，平台上报后台前自行补齐。{@link #id} / {@link #createTime} 由
 * {@code PointStorageService} 落盘时分配。
 */
@Data
public class PointInfo {

    /** 本地点位 ID，落盘时自增分配 */
    private Integer id;

    /** 设备 ID（平台下发） */
    private Integer devicesId;

    /** 点位名称（平台下发；缺省时落盘按“点位 n”生成） */
    private String pointName;

    /** 位置编号（平台下发） */
    private String pointCode;

    /** 点位坐标信息-X 轴 */
    private String pointPositionX;

    /** 点位坐标信息-Y 轴 */
    private String pointPositionY;

    /** 点位坐标信息-Z 轴：机器狗坐标的 z 是朝向，位置 Z 固定为 0 */
    private String pointPositionZ;

    /** 朝向：取机器狗坐标的 z（显式 R/YAW 优先） */
    private String rotation;

    /** 场所 ID（平台下发） */
    private Integer locationId;

    /** 外部 ID：机器狗当前点位 ID（401 状态的 {@code posId}） */
    private Integer outsideId;

    /** 点位类型：1 默认正常，2 充电桩，3 停车位（平台下发） */
    private Integer pointType;

    /** 机器狗点位 ID 的字符串形式，取 {@code posId} */
    private String pointId;

    /** 创建时间 {@code yyyy-MM-dd HH:mm:ss}，落盘时生成 */
    private String createTime;

    /**
     * 用机器狗当前坐标（401 状态接口解析结果）与平台下发的点位参数组装一条点位。
     *
     * @param report 401 状态解析出的坐标，可为空
     * @param data   平台下发 {@code savePoint} 的 {@code data}，可为空
     */
    public static PointInfo from(PointReport report, JSONObject data) {
        PointInfo point = new PointInfo();
        if (report != null) {
            point.setPointPositionX(toText(report.getX()));
            point.setPointPositionY(toText(report.getY()));
            // 机器狗坐标的 z 是朝向，不是高度，位置 Z 固定为 0
            point.setPointPositionZ("0");
            point.setRotation(toText(report.getRotation()));
            if (report.getPosId() != null) {
                point.setOutsideId(report.getPosId().intValue());
                point.setPointId(String.valueOf(report.getPosId()));
            }
        } else {
            point.setPointPositionZ("0");
        }

        if (data != null) {
            String pointName = StringUtils.defaultIfBlank(data.getString("pointName"), data.getString("name"));
            point.setPointName(StringUtils.trimToNull(pointName));
            point.setPointCode(data.getString("pointCode"));
            point.setPointType(data.getInteger("pointType"));
            point.setDevicesId(data.getInteger("devicesId"));
            point.setLocationId(data.getInteger("locationId"));
        }
        return point;
    }

    /** 坐标转字符串：去掉多余的 0（{@code 1.0 -> "1"}），空值保持为空 */
    private static String toText(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public JSONObject toJson() {
        return JSON.parseObject(JSON.toJSONString(this));
    }
}
