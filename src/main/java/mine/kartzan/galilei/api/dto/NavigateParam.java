package mine.kartzan.galilei.api.dto;

import lombok.Data;

/**
 * 导航参数。
 *
 * <p>一键导航 reqJson：{ "presetCode": "10", "xyz": "1.037931,-0.309902,1.030920,0" }
 *
 * <p>坐标格式为 {@code x,y,z[,w]}：x、y 为机器狗驻足点位置，<b>z 为朝向</b>
 * （机器狗方已确认：坐标是 xyzw，z 就是朝向，不是 z 轴高度），w 为第 4 个分量，按需透传。
 */
@Data
public class NavigateParam {

    /** 摄像机预置位 */
    private String presetCode;

    /** 机器狗驻足点，格式 {@code x,y,z[,w]}，其中 z 为朝向 */
    private String xyz;

    /** 只指定位置，朝向由机器狗自行决定。 */
    public static NavigateParam of(double x, double y) {
        return of(x, y, null, null);
    }

    /** 指定位置与朝向（z）。 */
    public static NavigateParam of(double x, double y, double heading) {
        return of(x, y, Double.valueOf(heading), null);
    }

    /** 指定位置、朝向（z）与第 4 个分量（w）。 */
    public static NavigateParam of(double x, double y, Double heading, Double w) {
        StringBuilder sb = new StringBuilder();
        sb.append(x).append(',').append(y);
        if (heading != null) {
            sb.append(',').append(heading);
        }
        if (w != null) {
            sb.append(',').append(w);
        }
        NavigateParam param = new NavigateParam();
        param.setXyz(sb.toString());
        return param;
    }

    public NavigateParam preset(String presetCode) {
        this.presetCode = presetCode;
        return this;
    }
}
