# robot-node-console（机器狗接口联调控制台）

一个纯 Node.js 的联调小工具：**前端页面点按钮下发机器狗指令，后端接收机器狗的回调并实时显示**。

- 下发：`POST {机器狗地址}/robot/api/common`（`{ code, authKey, reqJson }`）
- 接收：`POST /api/robot/callBackMess`（异步指令执行结果回调）
- 接收：`POST /api/robot/uploadFile`（业务文件上传通知）
- 展示：SSE（`/api/events`）实时推送到页面，无需刷新

> 与同仓库的 Java 版 `galilei-robot-client` 是两套独立实现，互不影响；
> Java 版跑在 8899，本工具默认跑在 **3100**，机器狗回调地址指向哪个服务，就由哪个服务收到回调。

---

## 1. 快速开始

```bash
cd robot-node-console
npm install          # 只依赖 express
npm start            # 或 node server/index.js
```

启动后终端会打印页面地址与「机器狗应该回调的地址」：

```
 页面地址      : http://127.0.0.1:3100
 机器狗地址    : http://192.168.100.106:8888  (POST http://192.168.100.106:8888/robot/api/common)
 请把机器狗的回调地址配置为（任选本机可达的 IP）:
   [WLAN 172.35.170.197]
     POST http://172.35.170.197:3100/api/robot/callBackMess
     POST http://172.35.170.197:3100/api/robot/uploadFile
```

浏览器打开 `http://127.0.0.1:3100`，在页面顶部「机器狗配置」里把地址改成真机地址（例如
`http://192.168.100.106:8888`）保存即可，无需重启。

### 命令行参数

```bash
node server/index.js --port 3100 --robot http://192.168.100.106:8888 --sn galilei-001 --timeout 30000 --retries 0
node server/index.js --help
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--port` | `3100`（环境变量 `PORT`） | 页面与回调接口的监听端口 |
| `--host` | `0.0.0.0` | 监听地址；机器狗要能回调就不要改成 127.0.0.1 |
| `--robot` | `http://192.168.100.106:8888` | 机器狗本体地址 |
| `--sn` | `galilei-001` | 机器狗 SN（仅展示 / 日志） |
| `--timeout` | `30000` | 单次调用超时（毫秒） |
| `--retries` | `0` | 调用失败重试次数（不含首次） |

页面里改过的配置会写入 `data/settings.json`，下次启动生效；命令行参数优先级最高。

---

## 2. 页面功能

### 2.1 指令按钮（9 个）

左侧是一排按钮，**点一下就下发指令**：

- 不需要参数的指令（104/108/109/401/309/319）点一下直接发；
- 需要参数的指令（116 模式切换、404 设置狗窝、402 一键导航）点一下**弹出输入框**，
  填完点「下发」才发（回车也可以，Esc 或点「取消」不发）；
- 按钮会闪一下表示结果：绿=成功、黄=已受理等待回调、红=失败，收到回调后颜色跟着更新；
- 请求与响应、以及机器狗回调上来的消息，统一显示在右侧消息流里。

| code | 按钮 | 弹框参数（reqJson） | 同步/异步 |
|---|---|---|---|
| 104 | 回充 | 无 | **异步**，到位后回调 |
| 108 | 起立 | 无 | 同步 |
| 109 | 趴下 | 无 | 同步 |
| 116 | 模式切换 | `{"mode":1}`，1 SALM / 2 手柄 | 同步 |
| 401 | 机器狗运行状态 | 无 | 同步，结果同时显示在按钮上方的状态条（sta/net/dl/wd/xyz…） |
| 404 | 设置狗窝 | `{"id":1,"xyz":"x,y,z","safe_xyz":"x,y,z"}` | 同步 |
| 402 | 一键导航 | `{"presetCode":"10","xyz":"x,y,z"}` | **异步**，到位后回调 |
| 309 | 开始建图 | 无 | **异步** |
| 319 | 关闭建图 | 无 | 同步 |

- 坐标格式 `x,y,z[,w]`：**z 是朝向**（机器狗方已确认坐标是 xyzw）；
- 404 的「安全位置」留空时自动回填为狗窝位置；
- 面板下方还有「自定义指令」，可下发任意 `code` + 原始 `reqJson`。

### 2.2 消息流（显示收到的信息）

右侧实时显示三类消息，可按「收到 / 回调 / 文件 / 下发 / 失败」筛选：

- **回调**：`POST /api/robot/callBackMess` 收到的内容，自动关联是哪个异步指令的结果；
- **文件上传**：`POST /api/robot/uploadFile` 收到的内容（`fileType` 1 PCD图 / 2 灰度图 / 3 YAML文件）；
- **下发**：本工具发出去的请求与机器狗返回。

页面顶部有「回调地址」标签，点击即复制，方便填到机器狗配置里。

> 消息流里的回调一定来自真实机器狗：看那条回调的 `ip` / `port` 即可确认来源，
> 应当是你配置的机器狗地址（例如 `192.168.100.106:8888`）。

---

## 3. 后端接口一览

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/bootstrap` | 页面初始化：配置、指令定义、历史消息、待回执 |
| GET | `/api/events` | SSE 实时消息流 |
| POST | `/api/robot/command` | 下发指令，body：`{ code, name?, reqJson }` |
| GET | `/api/messages` | 历史消息（`?limit=`） |
| POST | `/api/messages/clear` | 清空消息与待回执 |
| GET | `/api/pendings` | 当前等待回调的异步指令 |
| GET/PUT | `/api/settings` | 读取 / 修改机器狗地址等配置 |
| GET | `/api/health` | 健康检查 |
| POST | **`/api/robot/callBackMess`** | **机器狗回调**：`{ ip, port, messType, messVal, readme }` |
| POST | **`/api/robot/uploadFile`** | **机器狗文件上传通知**：`{ fileType, filePath, ip, port }` |

回调接口按接口文档返回：`callBackMess` → `{"code":200,"msg":"ok"}`；
`uploadFile` → `{"code":0,"msg":"","data":null}`。

---

## 4. 目录结构

```
robot-node-console/
├── server/
│   ├── index.js       # 启动入口：中间件、静态资源、监听、启动横幅
│   ├── config.js      # 配置（环境变量 / data/settings.json / 命令行参数）
│   ├── commands.js    # 9 个指令的定义（前端按钮的唯一数据来源）
│   ├── robotApi.js    # 调用机器狗 POST /robot/api/common
│   ├── routes.js      # 所有 HTTP 接口（含两个回调接口）
│   ├── store.js       # 内存消息中心 + SSE 广播
│   └── pending.js     # 异步指令待回执登记（回调结果与原指令对应）
├── public/
│   ├── index.html
│   ├── styles.css
│   └── app.js
├── data/settings.json # 运行期配置（自动生成，已 gitignore）
└── package.json
```

---

## 5. 常见问题

**机器狗回调不到？**
1. 确认机器狗侧配置的回调地址是本机 **可达的局域网 IP**（不是 `127.0.0.1`），端口为 3100；
2. Windows 防火墙放行 3100；
3. 页面右上角「实时连接正常」表示页面到控制台的 SSE 正常；
4. 用 `curl` 手工打一次回调接口，若消息流立刻出现该条消息，说明控制台本身没问题，
   问题在机器狗到本机的网络：
   ```bash
   curl -X POST http://127.0.0.1:3100/api/robot/callBackMess \
     -H "Content-Type: application/json" \
     -d '{"ip":"192.168.100.106","port":8888,"messType":402,"messVal":"1","readme":""}'
   ```

**端口被占用？**
换端口启动：`node server/index.js --port 3200`（`--port` 优先于环境变量与配置文件）。
Windows 上有一种特殊情况：别的程序只监听 `127.0.0.1:3100`，本服务仍能绑定 `0.0.0.0:3100`，
但 `127.0.0.1` 的请求会被对方截走，表现为接口返回莫名其妙的内容 —— 换端口即可。
