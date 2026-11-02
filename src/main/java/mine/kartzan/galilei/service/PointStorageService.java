package mine.kartzan.galilei.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mine.kartzan.galilei.api.dto.PointInfo;
import mine.kartzan.galilei.config.RobotProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地点位存储：点位保存到本地 JSON 文件（{@code robot.point.file}，默认 {@code ./data/points.json}）。
 *
 * <p>文件结构（{@code sn} + {@code pointList} 与后台批量点位上传的报文一致，平台可直接转发）：
 *
 * <pre>
 * {
 *   "sn": "galilei-001",
 *   "updateTime": "2026-10-27 16:20:00",
 *   "total": 2,
 *   "nextId": 3,
 *   "pointList": [ { "id": 1, "pointName": "点位 1", "pointPositionX": "1.037931", ... } ]
 * }
 * </pre>
 *
 * <p>{@code nextId} 是只增不减的序号（{@code total} / {@code nextId} 为文件内部记账字段，
 * 批量点位上报的回执里不带）：id 在平台侧是「机器人点位 ID」，删掉点位后再保存**不会复用**已用过的 id，
 * 否则新点位会和已上报后台的那条撞号。
 *
 * <p>落盘策略：读-改-写整体加锁（单实例串行），先写同目录临时文件再原子替换，
 * 避免写入过程中断电 / 进程被杀导致文件损坏；同名点位视为重新打点，覆盖坐标并保留原 ID。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointStorageService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String FIELD_SN = "sn";
    private static final String FIELD_UPDATE_TIME = "updateTime";
    private static final String FIELD_TOTAL = "total";
    private static final String FIELD_NEXT_ID = "nextId";
    private static final String FIELD_POINT_LIST = "pointList";

    /** {@code robot.point.file} 配成空值时的兜底路径 */
    private static final String DEFAULT_FILE = "./data/points.json";

    private final RobotProperties properties;

    /**
     * 保存一条点位：同名点位覆盖（保留原 ID），否则追加并分配新 ID。
     *
     * <p>ID 由文件里持久化的 {@code nextId} 序号分配，只增不减：删掉点位后再保存不会复用已用过的 ID。
     *
     * @return 落盘后的点位（含分配好的 ID 与创建时间）
     */
    public synchronized PointInfo save(PointInfo point) {
        if (point == null) {
            throw new IllegalArgumentException("点位为空，无法保存");
        }

        JSONObject stored = readFile();
        List<PointInfo> points = pointsOf(stored);
        int nextId = resolveNextId(stored, points);

        String pointName = StringUtils.defaultIfBlank(point.getPointName(), "点位 " + (points.size() + 1));
        point.setPointName(pointName);
        point.setCreateTime(LocalDateTime.now().format(TIME_FORMATTER));

        int existsIndex = indexOfName(points, pointName);
        if (existsIndex >= 0) {
            // 同名点位按重新打点处理：覆盖坐标，保留原 ID（保留成功时不消耗序号）
            Integer existsId = points.get(existsIndex).getId();
            if (existsId != null) {
                point.setId(existsId);
            } else {
                point.setId(nextId++);
            }
            points.set(existsIndex, point);
            log.info("保存点位：覆盖同名点位, name: {}, id: {}, xyz: {}", pointName, point.getId(), xyzOf(point));
        } else {
            point.setId(nextId++);
            points.add(point);
            log.info("保存点位：新增点位, name: {}, id: {}, xyz: {}", pointName, point.getId(), xyzOf(point));
        }

        write(points, nextId);
        return point;
    }

    /**
     * 删除一条点位：按 {@code id} / {@code pointName} / {@code outsideId} 依次匹配（命中即返回）。
     *
     * <p>只删列表里的这一条，不回退 {@code nextId} 序号，所以删掉的 ID 不会被后面的保存复用。
     *
     * @return 被删掉的点位
     * @throws IllegalArgumentException 三个条件都没给，或没匹配到点位
     */
    public synchronized PointInfo delete(Integer id, String pointName, Integer outsideId) {
        pointName = StringUtils.trimToNull(pointName);
        if (id == null && pointName == null && outsideId == null) {
            throw new IllegalArgumentException("删除点位需要指定 id、pointName 或 outsideId");
        }

        JSONObject stored = readFile();
        List<PointInfo> points = pointsOf(stored);
        int index = indexOf(points, id, pointName, outsideId);
        if (index < 0) {
            throw new IllegalArgumentException("点位不存在: " + describe(id, pointName, outsideId));
        }

        PointInfo deleted = points.remove(index);
        // 序号照旧落盘（只增不减），保证删掉的 id 不会被复用
        write(points, resolveNextId(stored, points));
        log.info("删除点位成功, name: {}, id: {}, xyz: {}, 剩余{}个",
                deleted.getPointName(), deleted.getId(), xyzOf(deleted), points.size());
        return deleted;
    }

    /**
     * 读取全部点位。
     */
    public synchronized List<PointInfo> list() {
        return readPoints();
    }

    /**
     * 点位 JSON 快照：{@code {sn, updateTime, total, pointList}}，批量点位上报直接回传它。
     */
    public synchronized JSONObject snapshot() {
        JSONObject stored = readFile();
        JSONArray array = stored == null ? null : stored.getJSONArray(FIELD_POINT_LIST);
        List<PointInfo> points = array == null ? new ArrayList<>() : new ArrayList<>(array.toJavaList(PointInfo.class));

        JSONObject snapshot = new JSONObject();
        snapshot.put(FIELD_SN, properties.getSn());
        snapshot.put(FIELD_UPDATE_TIME, stored == null ? null : stored.getString(FIELD_UPDATE_TIME));
        snapshot.put(FIELD_TOTAL, points.size());
        snapshot.put(FIELD_POINT_LIST, points);
        return snapshot;
    }

    /**
     * 点位文件绝对路径，日志与排查用。
     */
    public Path filePath() {
        String file = properties.getPoint() == null ? null : properties.getPoint().getFile();
        return Paths.get(StringUtils.defaultIfBlank(file, DEFAULT_FILE)).toAbsolutePath().normalize();
    }

    // ==================== 内部实现 ====================

    private List<PointInfo> readPoints() {
        return pointsOf(readFile());
    }

    private List<PointInfo> pointsOf(JSONObject stored) {
        if (stored == null) {
            return new ArrayList<>();
        }
        JSONArray array = stored.getJSONArray(FIELD_POINT_LIST);
        if (array == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(array.toJavaList(PointInfo.class));
    }

    /**
     * 下一个可用 ID：取文件里持久化的 {@code nextId} 与现有点位最大 ID + 1 的较大值。
     *
     * <p>序号只增不减，删掉点位（含删掉最后一条）后再保存不会复用已用过的 ID；
     * 手工编辑文件漏了 {@code nextId} 时，按现有点位最大 ID 兜底。
     */
    private int resolveNextId(JSONObject stored, List<PointInfo> points) {
        int next = stored == null ? 1 : Math.max(1, stored.getIntValue(FIELD_NEXT_ID, 1));
        for (PointInfo point : points) {
            if (point.getId() != null && point.getId() >= next) {
                next = point.getId() + 1;
            }
        }
        return next;
    }

    /**
     * 读取点位文件；文件不存在或为空返回 null。
     *
     * <p>文件存在但读不出来 / 不是合法 JSON 时抛异常而不是当成空列表，
     * 否则一次保存就会把已有的点位全部覆盖掉。
     */
    private JSONObject readFile() {
        Path path = filePath();
        if (!Files.exists(path)) {
            return null;
        }
        String content;
        try {
            content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取本地点位文件失败: " + path + ", " + e.getMessage(), e);
        }
        if (StringUtils.isBlank(content)) {
            return null;
        }
        try {
            return JSON.parseObject(content);
        } catch (Exception e) {
            throw new IllegalStateException("本地点位文件不是合法 JSON: " + path, e);
        }
    }

    private void write(List<PointInfo> points, int nextId) {
        Path path = filePath();
        JSONObject json = new JSONObject();
        json.put(FIELD_SN, properties.getSn());
        json.put(FIELD_UPDATE_TIME, LocalDateTime.now().format(TIME_FORMATTER));
        json.put(FIELD_TOTAL, points.size());
        json.put(FIELD_NEXT_ID, nextId);
        json.put(FIELD_POINT_LIST, points);

        Path tmp = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.write(tmp, json.toJSONString().getBytes(StandardCharsets.UTF_8));
            move(tmp, path);
            tmp = null;
            log.info("本地点位文件已更新, path: {}, 共{}个点位", path, points.size());
        } catch (IOException e) {
            throw new IllegalStateException("写入本地点位文件失败: " + path + ", " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("清理本地点位临时文件失败, path: {}", tmp, e);
                }
            }
        }
    }

    /** 优先原子替换，文件系统不支持时退化为普通替换 */
    private void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int indexOfName(List<PointInfo> points, String pointName) {
        for (int i = 0; i < points.size(); i++) {
            if (StringUtils.equals(pointName, points.get(i).getPointName())) {
                return i;
            }
        }
        return -1;
    }

    /** 按 id -> pointName -> outsideId 依次匹配，命中即返回（调用方只给了一个条件时即精确匹配） */
    private int indexOf(List<PointInfo> points, Integer id, String pointName, Integer outsideId) {
        if (id != null) {
            for (int i = 0; i < points.size(); i++) {
                if (id.equals(points.get(i).getId())) {
                    return i;
                }
            }
        }
        if (pointName != null) {
            int index = indexOfName(points, pointName);
            if (index >= 0) {
                return index;
            }
        }
        if (outsideId != null) {
            for (int i = 0; i < points.size(); i++) {
                if (outsideId.equals(points.get(i).getOutsideId())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String describe(Integer id, String pointName, Integer outsideId) {
        StringBuilder text = new StringBuilder();
        if (id != null) {
            text.append("id=").append(id);
        }
        if (pointName != null) {
            text.append(text.length() > 0 ? ", " : "").append("pointName=").append(pointName);
        }
        if (outsideId != null) {
            text.append(text.length() > 0 ? ", " : "").append("outsideId=").append(outsideId);
        }
        return text.toString();
    }

    private String xyzOf(PointInfo point) {
        return point.getPointPositionX() + "," + point.getPointPositionY() + "," + point.getRotation();
    }
}
