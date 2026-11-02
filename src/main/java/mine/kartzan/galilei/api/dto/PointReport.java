package mine.kartzan.galilei.api.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * 机器狗当前坐标（点位）解析结果，来自机器狗 401 状态接口的 {@code xyz} / {@code posId} 等字段。
 *
 * <p>{@code savePoint} 保存点位时用它解析当前坐标，再转成 {@link PointInfo} 落盘。
 *
 * <p>坐标格式为 {@code "x,y,z[,w]"} 或带前缀的 {@code "X:1,Y:2,Z:3[,W:4]"}，两种格式都做兼容解析。
 *
 * <p><b>坐标语义（机器狗方已确认）</b>：坐标是 {@code xyzw}，其中 x、y 为位置，
 * <b>z 为朝向</b>（不是 z 轴高度），w 为第 4 个分量，原样保留。
 * {@link #rotation} 表示朝向，取值等同 z，保留该字段名以兼容既有平台协议。
 *
 * <p>无法解析时仅保留原始 {@link #xyz}。
 */
@Data
public class PointReport {

    /** 当前巡检点 ID */
    private Long posId;

    /** 是否定位成功：0 无，1 有 */
    private Integer hasLocation;

    /** GPS 坐标（需上装） */
    private String gps;

    /** 原始坐标字符串 */
    private String xyz;

    /** X 坐标（位置） */
    private Double x;

    /** Y 坐标（位置） */
    private Double y;

    /** Z 分量：朝向（机器狗方确认 z 就是朝向） */
    private Double z;

    /** 第 4 个分量，原样保留 */
    private Double w;

    /** 朝向，等同 z；若坐标里显式给了 R/YAW 则优先取显式值 */
    private Double rotation;

    public static PointReport from(JSONObject statusData) {
        PointReport report = new PointReport();
        if (statusData == null) {
            return report;
        }
        report.setPosId(statusData.getLong("posId"));
        report.setHasLocation(statusData.getInteger("hasLocation"));
        report.setGps(statusData.getString("gps"));
        String xyz = statusData.getString("xyz");
        report.setXyz(xyz);
        report.parseXyz(xyz);
        // 未显式给出 R/YAW 时，朝向取坐标里的 z
        if (report.getRotation() == null) {
            report.setRotation(report.getZ());
        }
        return report;
    }

    private void parseXyz(String xyz) {
        if (StringUtils.isBlank(xyz)) {
            return;
        }
        String value = xyz.trim();
        if (value.contains(":")) {
            parseKeyValue(value);
        } else {
            parsePlain(value);
        }
    }

    /** 解析 {@code "X:1,Y:2,Z:3[,W:4]"} 形式 */
    private void parseKeyValue(String value) {
        for (String part : value.split(",")) {
            int idx = part.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = part.substring(0, idx).trim().toUpperCase();
            Double num = toDouble(part.substring(idx + 1));
            if (num == null) {
                continue;
            }
            switch (key) {
                case "X":
                    setX(num);
                    break;
                case "Y":
                    setY(num);
                    break;
                case "Z":
                    // z 即朝向
                    setZ(num);
                    break;
                case "W":
                    setW(num);
                    break;
                case "R":
                case "ROTATION":
                case "YAW":
                    setRotation(num);
                    break;
                default:
                    break;
            }
        }
    }

    /** 解析 {@code "x,y,z[,w]"} 形式，z 为朝向 */
    private void parsePlain(String value) {
        String[] parts = value.split(",");
        if (parts.length > 0) {
            setX(toDouble(parts[0]));
        }
        if (parts.length > 1) {
            setY(toDouble(parts[1]));
        }
        if (parts.length > 2) {
            setZ(toDouble(parts[2]));
        }
        if (parts.length > 3) {
            setW(toDouble(parts[3]));
        }
    }

    private static Double toDouble(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public JSONObject toJson() {
        return JSON.parseObject(JSON.toJSONString(this));
    }
}
