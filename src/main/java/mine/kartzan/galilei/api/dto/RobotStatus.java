package mine.kartzan.galilei.api.dto;

import lombok.Data;

/**
 * 机器狗运行状态（code 401 响应中的 data 部分）。
 *
 * <p>字段依据机器狗方提供的状态结构定义，字段含义见各属性注释。
 */
@Data
public class RobotStatus {

    /** 返回 Code，200 正常，其他异常 */
    private Integer code;

    /** 异常信息 */
    private String msg;

    /**
     * 工作状态：-1 离线，0 预留，1 空闲，2 自动巡检中，3 手动巡检中，4 充电中，
     * 5 暂停中，6 故障中，7 异常天气，8 一键返航中，9 人工充电返回中，10 充电失败。
     * 字典 robot_work_status，见 {@link mine.kartzan.galilei.enums.RobotWorkStatusEnum}
     */
    private Integer sta;

    /** 当前模式：1 SALM，2 手柄 */
    private Integer mode;

    /** 网络信号 1-5，越高代表信号越好 */
    private Integer net;

    /** 电池电量 0-100，百分比 */
    private Integer dl;

    /** 电机温度 */
    private Double wd;

    /** 电池温度 */
    private Double batteryWd;

    /** 开机时长，单位小时(h)，保留三位有效数字 */
    private Double uptime;

    /** 是否定位成功：0 无，1 有 */
    private Integer hasLocation;

    /** 驻足点位置 */
    private String xyz;

    /** GPS 坐标（需上装） */
    private String gps;

    /** 当前巡检点（可能是雪花 ID，故用 Long） */
    private Long posId;

    /** 速度，前后左右 */
    private Double speed1;

    /** 速度，转向 */
    private Double speed2;

    /** 噪声分贝信息（需上装） */
    private String decibel;

    /** 气体传感器（需上装） */
    private String gasDetectionData;

    /** 核载符（需上装） */
    private String nuclearLoadDataJsonStr;

    /** 消息唯一序列号 */
    private String seq;
}
