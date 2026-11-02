package mine.kartzan.galilei.enums;

/**
 * 机器狗工作状态（状态接口 401 的 sta 字段，字典 robot_work_status）。
 */
public enum RobotWorkStatusEnum {

    OFFLINE(-1, "离线"),
    RESERVED(0, "预留"),
    IDLE(1, "空闲"),
    AUTO_INSPECTING(2, "自动巡检中"),
    MANUAL_INSPECTING(3, "手动巡检中"),
    CHARGING(4, "充电中"),
    PAUSED(5, "暂停中"),
    FAULT(6, "故障中"),
    BAD_WEATHER(7, "异常天气"),
    RETURNING(8, "一键返航中"),
    CHARGE_RETURNING(9, "人工充电返回中"),
    CHARGE_FAILED(10, "充电失败");

    private final int code;
    private final String description;

    RobotWorkStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RobotWorkStatusEnum fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RobotWorkStatusEnum item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}
