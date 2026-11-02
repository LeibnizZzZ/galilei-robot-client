package mine.kartzan.galilei.enums;

/**
 * 机器狗指令定义：语义化命令名与中间层 API code 的映射。
 *
 * <p>code 参考《机器人二次开发API接口文档-应用层》，均通过 POST /robot/api/common 下发。
 */
public enum RobotCommandEnum {

    /** 导航到指定 xyz 坐标（一键导航），异步：先返回已受理，到位后由 callBackMess 回调 */
    NAVIGATE("navigate", 402, true, "导航到指定坐标(一键导航)"),
    /** 开始导航，异步返回 */
    START_NAVIGATE("startNavigate", 320, true, "开始导航"),
    /** 结束导航 */
    STOP_NAVIGATE("stopNavigate", 321, false, "结束导航"),
    /** 起立 */
    STAND_UP("standUp", 108, false, "起立"),
    /** 趴下 / 站下 */
    LIE_DOWN("lieDown", 109, false, "趴下"),
    /** 一键返航（回桩充电），异步：先返回已受理，完成或失败时由 callBackMess 回调 */
    CHARGE("charge", 104, true, "一键返航(回桩充电)"),
    /** 机器狗运行状态（电量、状态等） */
    STATUS("status", 401, false, "机器狗运行状态"),
    /**
     * 保存点位（创建点位）：取机器狗当前坐标（底层就是 401 状态接口），
     * 按平台点位格式（参考 sz-paxini-client 的 {@code RobotLocationsMapPoint}）写入本地点位 JSON。
     *
     * <p>注意：与 {@link #STATUS} 共用 code 401，因此 {@link #fromCode(Integer)} 命中 STATUS；
     * 该指令只能通过 command 名 {@code savePoint} 下发。
     */
    SAVE_POINT("savePoint", 401, false, "保存点位(取当前坐标写入本地JSON)"),
    /**
     * 批量点位上报：读取本地点位 JSON，把里面的点位原样回传。
     *
     * <p>纯本地指令，不下发机器狗，因此 code 用本项目自定义的 900（机器狗接口没有这个 code）。
     */
    BATCH_REPORT_POINTS("batchReportPoints", 900, false, "批量点位上报(读取本地JSON)"),
    /**
     * 删除点位：按 {@code id} / {@code pointName} / {@code outsideId} 从本地点位 JSON 里删掉一条点位。
     *
     * <p>纯本地指令，不下发机器狗，code 用本项目自定义的 901（与批量点位上报的 900 同属本地区间）。
     * 删掉的 {@code id} 不会被复用（点位文件里的 {@code nextId} 序号只增不减）。
     */
    DELETE_POINT("deletePoint", 901, false, "删除点位(从本地JSON删除)"),
    /** 设置狗窝（狗窝位置和安全位置），服务启动时按 robot.dog-house.* 自动下发 */
    SET_DOG_HOUSE("setDogHouse", 404, false, "设置狗窝(狗窝位置和安全位置)");

    private final String command;
    private final int code;
    private final boolean async;
    private final String description;

    RobotCommandEnum(String command, int code, boolean async, String description) {
        this.command = command;
        this.code = code;
        this.async = async;
        this.description = description;
    }

    public String getCommand() {
        return command;
    }

    public int getCode() {
        return code;
    }

    /** 是否为异步指令：下发成功仅代表已受理，最终结果通过 callBackMess 回传 */
    public boolean isAsync() {
        return async;
    }

    public String getDescription() {
        return description;
    }

    public static RobotCommandEnum fromCommand(String command) {
        if (command == null) {
            return null;
        }
        for (RobotCommandEnum item : values()) {
            if (item.command.equalsIgnoreCase(command)) {
                return item;
            }
        }
        return null;
    }

    public static RobotCommandEnum fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RobotCommandEnum item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}
