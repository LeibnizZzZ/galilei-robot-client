'use strict';

/**
 * 机器狗接口联调控制台（Node）—— 启动入口。
 *
 * 启动后：
 *   1. 浏览器打开 http://127.0.0.1:{PORT} 使用按钮下发指令；
 *   2. 把机器狗的回调地址配成本机 http://{本机IP}:{PORT}/api/robot/callBackMess
 *      与 http://{本机IP}:{PORT}/api/robot/uploadFile，收到的消息会实时显示在页面上。
 */

const express = require('express');
const config = require('./config');
const store = require('./store');
const routes = require('./routes');

const USAGE = `
机器狗接口联调控制台

用法: node server/index.js [选项]

选项:
  --port <端口>        监听端口（默认 8899，也可用环境变量 PORT）
  --host <地址>        监听地址（默认 0.0.0.0，机器狗要回调就必须监听 0.0.0.0）
  --robot <地址>       机器狗地址（默认 http://192.168.100.106:8888）
  --sn <编号>          机器狗 SN（默认 galilei-001）
  --timeout <毫秒>     单次接口调用超时（默认 30000）
  --retries <次数>     调用失败重试次数（默认 0）
  -h, --help           显示帮助

示例:
  node server/index.js --port 8899 --robot http://192.168.100.106:8888
`;

if (process.argv.includes('-h') || process.argv.includes('--help')) {
  console.log(USAGE);
  process.exit(0);
}

config.applyCliOverrides(process.argv.slice(2));

const app = express();

app.disable('x-powered-by');
app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: false }));

// 允许跨域，方便页面被别的调试工具嵌入 / 直接调用接口
app.use((req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(204);
  }
  next();
});

// 简单访问日志：回调类请求在控制台也能直接看到
app.use((req, res, next) => {
  if (req.path.startsWith('/api/')) {
    const body = req.body && Object.keys(req.body).length > 0 ? ` ${JSON.stringify(req.body)}` : '';
    console.log(`[${new Date().toLocaleTimeString()}] ${req.method} ${req.originalUrl}${body}`);
  }
  next();
});

app.use(routes.router);
app.use(express.static(config.PUBLIC_DIR, { extensions: ['html'] }));

// 未匹配到的 /api/* 返回 JSON，避免前端拿到 HTML
app.use('/api', (req, res) => {
  res.status(404).json({ code: 404, msg: `接口不存在: ${req.method} ${req.originalUrl}` });
});

// JSON 解析失败等错误
app.use((err, req, res, next) => {
  console.error('[error]', err.message);
  if (res.headersSent) {
    return next(err);
  }
  res.status(err.status || 500).json({ code: err.status || 500, msg: err.message });
});

const settings = config.get();
store.setCapacity(settings.maxMessages);

const server = app.listen(settings.port, settings.host, () => {
  const urls = routes.callbackUrls(null);
  console.log('');
  console.log('=========================================================');
  console.log(' 机器狗接口联调控制台已启动');
  console.log('---------------------------------------------------------');
  console.log(` 页面地址      : http://127.0.0.1:${settings.port}`);
  console.log(` 机器狗地址    : ${settings.robotBaseUrl}  (POST ${settings.robotBaseUrl}/robot/api/common)`);
  console.log(` 机器狗 SN     : ${settings.robotSn}`);
  console.log(' 请把机器狗的回调地址配置为（任选本机可达的 IP）:');
  if (urls.length === 0) {
    console.log(`   POST http://<本机IP>:${settings.port}/api/robot/callBackMess`);
    console.log(`   POST http://<本机IP>:${settings.port}/api/robot/uploadFile`);
  } else {
    for (const item of urls) {
      console.log(`   [${item.name} ${item.address}]`);
      console.log(`     POST ${item.callBackMess}`);
      console.log(`     POST ${item.uploadFile}`);
    }
  }
  console.log('=========================================================');
  console.log('');

  store.record({
    kind: 'system',
    direction: 'local',
    title: `控制台已启动，监听 ${settings.host}:${settings.port}`,
    payload: {
      robotBaseUrl: settings.robotBaseUrl,
      robotSn: settings.robotSn,
      timeoutMs: settings.timeoutMs,
      maxRetries: settings.maxRetries,
      callbackUrls: urls
    }
  });
});

function shutdown(signal) {
  console.log(`\n收到 ${signal}，正在关闭控制台...`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}

// 端口被占用时给出明确提示（Windows 上别的进程只监听 127.0.0.1 时，
// 本服务仍可能绑定 0.0.0.0 成功，但 127.0.0.1 的请求会被对方截走）
server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`\n[启动失败] 端口 ${settings.port} 已被占用，请换一个端口，例如：`);
    console.error(`  node server/index.js --port ${settings.port + 1}\n`);
  } else {
    console.error('\n[启动失败]', err);
  }
  process.exit(1);
});

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
