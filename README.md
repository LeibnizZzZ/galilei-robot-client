# galilei-robot-client

机器狗 MQTT 指令转发 / 中间桥接服务。

```
平台 / 上游系统
   │  ① MQTT 下发指令
   ▼
galilei-robot-client（本项目）
   │  ② HTTP 调用机器狗接口（POST /robot/api/common）
   ▼
机器狗本体
   │  ③ 同步返回 code/msg；异步指令通过 POST /api/robot/callBackMess 回调
   ▲
   │  ④ MQTT 回发执行结果
平台 / 上游系统
```

## 技术栈

- Java 8 + Spring Boot 2.6.13（Maven）
- MQTT：Eclipse Paho（`org.eclipse.paho.client.mqttv3`），MQTT 核心实现参考 `sz-paxini-client`
- HTTP：OkHttp
- JSON：fastjson2

## 目录结构

```
src/main/java/mine/kartzan/galilei/
├── GalileiRobotClientApplication.java   # 启动类
├── config/                              # 配置绑定与 Bean 装配
│   ├── RobotProperties.java             # robot.* 配置
│   └── RobotClientConfig.java           # API 客户端 / MQTT 管理器装配
├── controller/                          # HTTP 接口层
│   ├── RobotCallbackController.java     # 接收机器狗异步回调
│   └── RobotFileUploadController.java   # 接收机器狗业务文件上传通知
├── job/                                 # 定时任务
│   └── RobotStatusReportJob.java        # 电量/状态轮询上报
├── service/                             # 业务处理
│   ├── RobotCommandService.java         # MQTT 指令 -> 机器狗接口 -> MQTT 结果
│   ├── PointStorageService.java         # 本地点位 JSON 读写（保存点位 / 批量点位上报）
│   ├── DogHouseInitializer.java         # 启动时 404 设置狗窝位置与安全位置
│   └── PendingAsyncRegistry.java        # 异步指令待回执登记
├── mqtt/                                # MQTT 通信
│   ├── MqttManager.java                 # MQTT 管理器接口
│   ├── MqttRequest.java                 # 请求/响应封装
│   ├── RobotMqttManager.java            # Paho 实现
│   └── MqttClientInitializer.java       # 启动连接与订阅
├── api/                                 # 机器狗 HTTP 接口客户端
│   ├── BaseApiClient.java
│   ├── RobotApiClient.java              # /robot/api/common 调用
│   └── dto/                             # 请求/响应 DTO
├── model/                               # 消息模型
│   ├── RobotMqttMessage.java            # MQTT 消息体
│   ├── CommandMessage.java              # 下行指令
│   ├── CallbackMessage.java             # 异步回调
│   └── FileUploadMessage.java           # 业务文件上传通知
├── enums/                               # 枚举（指令 -> code）
└── util/
```

## 配置（application.yml）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `robot.sn` | `galilei-001` | 机器狗唯一标识，用于 clientId 与 topic 占位符 `{sn}` |
| `robot.api.base-url` | `http://192.168.100.106:8888` | 机器狗本体 HTTP 地址 |
| `robot.api.max-retries` | `2` | 接口调用失败重试次数（不含首次） |
| `robot.mqtt.broker` | `tcp://127.0.0.1:1883` | MQTT Broker |
| `robot.mqtt.command-topic` | `robot/{sn}/command` | 订阅：平台下发指令 |
| `robot.mqtt.reply-topic` | `robot/{sn}/reply` | 发布：指令执行结果 / 异步回调结果 |
| `robot.mqtt.status-topic` | `robot/{sn}/status` | 发布：电量 / 状态上报 |
| `robot.status.poll-enabled` | `true` | 是否轮询上报状态 |
| `robot.status.poll-interval-ms` | `30000` | 轮询间隔（毫秒） |
| `robot.dog-house.enabled` | `true` | 启动时是否下发狗窝设置（404） |
| `robot.dog-house.id` | `1` | 狗窝 ID |
| `robot.dog-house.xyz` | 空 | 狗窝位置 `x,y,z[,w]`（z 为朝向）；留空则启动时跳过设置 |
| `robot.dog-house.safe-xyz` | 空 | 安全位置 `x,y,z[,w]`；留空则与狗窝位置相同 |
| `robot.point.file` | `./data/points.json` | 本地点位 JSON 文件（保存点位写入、批量点位上报读取） |

支持通过环境变量覆盖（见 `application.yml` 中的 `${...}`）。

### 启动设置狗窝（404）

服务启动（重启）时会读取 `robot.dog-house.*`，通过 404 重新设置狗窝位置与安全位置：

```yaml
robot:
  dog-house:
    enabled: true
    id: 123456798
    xyz: "1.037931,-0.309902,1.030920"        # 狗窝位置
    safe-xyz: "1.037931,-0.309902,1.030920"   # 安全位置，留空则同 xyz
```

- 未配置 `xyz` 或 `enabled=false` 时跳过，仅打印日志；
- 下发失败按 `robot.api.max-retries` 重试，仍失败只记录日志，不影响服务启动与 MQTT 订阅；
- 平台也可通过 MQTT 下发 `command=setDogHouse`（或 `code=404`）手动设置，`data` 透传 `{id, xyz, safe_xyz}`。

## MQTT 协议

> 完整协议见 [`docs/MQTT协议.md`](docs/MQTT协议.md)，以下为要点摘要。

### 下行指令（平台 -> 本项目）

订阅 `robot/{sn}/command`，消息体：

```json
{
  "msgId": "1234567890",
  "command": "navigate",
  "data": { "presetCode": "10", "xyz": "1.037931,-0.309902,1.030920,0" }
}
```

- `msgId`：必填，用于结果关联。
- `command`：语义化指令名；也可改用 `code` 直接下发机器狗接口 code。
- `data`：透传为机器狗接口的 `reqJson`。

> 坐标格式为 `x,y,z[,w]`：x、y 为位置，**z 为朝向**（机器狗方已确认坐标是 xyzw，z 就是朝向，不是 z 轴），w 为第 4 个分量。

支持的命令：

| command | code | 说明 | 同步/异步 |
|---|---|---|---|
| `navigate` | 402 | 导航到指定坐标（一键导航） | 异步 |
| `startNavigate` | 320 | 开始导航 | 异步 |
| `stopNavigate` | 321 | 结束导航 | 同步 |
| `standUp` | 108 | 起立 | 同步 |
| `lieDown` | 109 | 趴下 | 同步 |
| `charge` | 104 | 一键返航（回桩充电） | 异步 |
| `status` | 401 | 机器狗运行状态 | 同步 |
| `savePoint` | 401 | 保存点位（取当前坐标写入本地 JSON） | 同步 |
| `batchReportPoints` | 900 | 批量点位上报（读取本地 JSON 回传） | 同步 |
| `deletePoint` | 901 | 删除点位（从本地 JSON 删除） | 同步 |
| `setDogHouse` | 404 | 设置狗窝（狗窝位置和安全位置） | 同步 |

### 上行结果（本项目 -> 平台）

发布 `robot/{sn}/reply`：

```json
{
  "timestamp": 1700000000000,
  "msgId": "987654321",
  "replyMsgId": "1234567890",
  "sn": "galilei-001",
  "command": "navigate",
  "code": 402,
  "success": true,
  "pending": false,
  "msg": "ok",
  "data": { "presetCode": "10", "xyz": "1.037931,-0.309902,1.030920,0" }
}
```

异步指令（导航、一键返航）分两次回传：先回一条 `success=true, pending=true, msg="指令已受理"`（HTTP 200 仅代表已受理），机器狗在**导航结束（到位）时**回调 `POST /api/robot/callBackMess`，本项目再发布一条 `pending=false`、带有相同 `replyMsgId` 的结果，`success` 反映最终成败（`messVal=1` 为到达成功）。回调 `messType` 一般为下发时的 code（如 `104`），导航场景可能为 `402` 或 `320`，本项目按 402↔320 兜底并还原原始指令。

### 状态上报（本项目 -> 平台）

发布 `robot/{sn}/status`：

```json
{
  "msgId": "...",
  "sn": "galilei-001",
  "command": "status",
  "code": 401,
  "success": true,
  "msg": "ok",
  "data": { "sta": 1, "net": 1, "dl": 76, "wd": 96, "xyz": "X:1,Y:2,Z:3", "gps": "...", "decibel": "25.65" }
}
```

> `xyz` 中 `z` 为朝向（机器狗方确认），上报点位时 `rotation` 即取该值。

### 本地点位（保存点位 / 删除点位 / 批量点位上报）

机器狗本体没有站点列表接口，点位由平台通过 MQTT 打到本服务本地：

1. 平台下发 `savePoint`（`code=401`，与 `status` 共用 code，只能按 command 名下发）：
   本服务取机器狗**当前坐标**（401 状态接口的 `xyz`）写入本地点位 JSON；
2. 平台下发 `deletePoint`（`code=901`，本服务自定义，不下发机器狗）：
   本服务按 `id` / `pointName` / `outsideId` 从该 JSON 删掉一条点位；
3. 平台下发 `batchReportPoints`（`code=900`，本服务自定义，不下发机器狗）：
   本服务把该 JSON 里的点位原样回传。

保存点位（`data` 可带点位名等平台已知字段，坐标一律取机器狗当前坐标，不接收平台传的坐标）：

```json
{
  "msgId": "1234567890",
  "command": "savePoint",
  "data": { "pointName": "巡检点A", "devicesId": 3, "locationId": 9 }
}
```

回执 `data` 为落盘后的点位，字段与 sz-paxini-client 的 `RobotLocationsMapPoint`（即 `PaxiniApi` 的点位上传报文）对齐：

```json
{
  "id": 1,
  "pointName": "巡检点A",
  "pointPositionX": "1.037931",
  "pointPositionY": "-0.309902",
  "pointPositionZ": "0",
  "rotation": "1.03092",
  "devicesId": 3,
  "locationId": 9,
  "outsideId": 7,
  "pointId": "7",
  "createTime": "2026-10-27 16:20:00"
}
```

- 坐标是 `xyzw`，其中 **z 为朝向**，所以 `pointPositionZ` 固定为 `0`、朝向写 `rotation`（与 `PaxiniApi` 上报点位一致）；
- `outsideId` / `pointId` 取 401 状态里的 `posId`；`createTime` 由本服务落盘时生成；
- `id` 按本地点位文件里的 `nextId` 序号分配，**只增不减**：删掉点位（含删掉最后一条）后再保存不会复用已用过的 `id`，避免与已上报后台的点位撞号；
- `data` 还支持 `pointCode`、`pointType`（1 默认正常 2 充电桩 3 停车位）；
- **同名点位再次保存视为重新打点**：覆盖坐标，保留原 `id`（也不占用新序号）。

删除点位（`data` 给 `id`、`pointName`、`outsideId` 之一即可，按这个顺序依次匹配、命中即删）：

```json
{ "msgId": "1234567891", "command": "deletePoint", "data": { "id": 1 } }
```

回执 `data` 为被删掉的点位；没匹配到点位（或三个条件都没给）时以 `success=false` 失败，`msg` 里是原因。
**删掉的 `id` 不会被复用**：点位文件里的 `nextId` 序号只增不减，之后保存的新点位顺延取号。

批量点位上报回执 `data` 就是本地点位 JSON 的内容：

```json
{
  "sn": "galilei-001",
  "updateTime": "2026-10-27 16:20:00",
  "total": 1,
  "pointList": [ { "id": 1, "pointName": "巡检点A", "pointPositionX": "1.037931", "rotation": "1.03092" } ]
}
```

> `sn` + `pointList` 与后台批量点位上传的报文一致，平台可直接转发；
> 文件路径由 `robot.point.file` 配置（默认 `./data/points.json`），写入走“临时文件 + 原子替换”；
> 文件里另有 `total` / `nextId` 两个记账字段（`nextId` 是点位 ID 序号），回执里不带；
> 文件损坏时保存 / 上报以 `success=false` 失败，不会清空已有点位。

### 异步回调（机器狗 -> 本项目）

本项目监听 `POST /api/robot/callBackMess`（默认端口 `8899`），请求体：

```json
{ "ip": "192.168.100.106", "port": 8888, "messType": 320, "messVal": "1", "readme": "" }
```

收到后按 `messType` 关联原始 `msgId`，转发为 `robot/{sn}/reply` 消息。

### 业务文件上传通知（机器狗 -> 本项目）

机器狗通过 FTP 把业务文件（PCD 图、灰度图、YAML、日志等）上传到服务器目录后，调用
`POST /api/robot/uploadFile`（默认端口 `8899`）通知文件已上传，请求体：

```json
{ "fileType": 1, "filePath": "/robotFile/test.pcd", "ip": "192.168.100.106", "port": 8888 }
```

`fileType`：1 PCD图、2 灰度图、3 YAML文件。

本项目不读取 / 落盘该文件，仅记录日志并转发为 `robot/{sn}/reply` 消息（`command=uploadFile`，
`data` 原样携带 `fileType/filePath/ip/port`），由平台侧决定后续处理：

```json
{
  "timestamp": 1700000000000,
  "msgId": "...",
  "sn": "galilei-001",
  "command": "uploadFile",
  "msg": "文件已上传",
  "data": { "fileType": 1, "filePath": "/robotFile/test.pcd", "ip": "192.168.100.106", "port": 8888 }
}
```

## 运行

```bash
# 开发运行
mvn spring-boot:run

# 打包运行
mvn clean package -DskipTests
java -jar target/galilei-robot-client-0.0.1-SNAPSHOT.jar
```

> 首次在 IntelliJ IDEA 中打开时，请以 Maven 项目导入 / Reload，以加载依赖并识别 `src/main/java`。

## 联调工具：robot-node-console（Node）

同仓库下另有一个独立的 Node.js 联调小工具 [`robot-node-console/`](robot-node-console/README.md)：
**前端页面点按钮下发指令，后端接收机器狗回调并实时显示**，与本 Java 服务互不影响（默认端口 `3100`，Java 服务为 `8899`）。

```bash
cd robot-node-console
npm install
npm start        # http://127.0.0.1:3100
```

- 按钮覆盖 104 回充、108 起立、109 趴下、116 模式切换、401 运行状态、404 设置狗窝、402 一键导航、309 开始建图、319 关闭建图；
- 接收并实时展示 `POST /api/robot/callBackMess` 与 `POST /api/robot/uploadFile`；
- 异步指令（104/402/309）自动把回调结果与下发指令对应起来；


## 待确认

> 接口层面待确认项详见 [`docs/待确认接口清单.md`](docs/待确认接口清单.md)，
> 当前阻塞项见 [`docs/TODO.md`](docs/TODO.md)，发给机器狗方的确认函见 [`docs/机器狗接口确认函.md`](docs/机器狗接口确认函.md)。

1. MQTT 协议已自定义（见 `docs/MQTT协议.md`）；若平台已有既定协议，需按平台协议调整 `mqtt/` 解析逻辑。
2. 异步指令的成功口径（已确认：HTTP 200 仅代表已受理，导航到位后由 `callBackMess` 回调最终结果）。
3. 状态字段 `qd` / `tx` 的含义。
4. 点位朝向 `rotation`：已确认坐标为 `xyzw`，其中 `z` 即朝向；`PointReport` 的 `rotation` 取 `z`（显式 `R`/`YAW` 优先），`w` 原样保留。
