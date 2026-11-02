'use strict';

/**
 * 运行配置。
 *
 * 优先级：data/settings.json（页面里改过的值） > 环境变量 > 默认值。
 * 端口 / 监听地址只能通过环境变量调整，改动后需要重启。
 */

const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT_DIR = path.resolve(__dirname, '..');
const PUBLIC_DIR = path.join(ROOT_DIR, 'public');
const DATA_DIR = path.join(ROOT_DIR, 'data');
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json');

const DEFAULTS = {
  // 默认与后端 galilei-robot-client 保持一致（application.yml 的 server.port），可用 PORT 或 --port 修改
  port: Number(process.env.PORT || 8899),
  host: process.env.HOST || '0.0.0.0',
  // 机器狗本体（中间层 API）地址，指令都发到 {robotBaseUrl}/robot/api/common
  robotBaseUrl: process.env.ROBOT_BASE_URL || 'http://192.168.100.106:8888',
  // 机器狗唯一标识，仅用于展示 / 日志
  robotSn: process.env.ROBOT_SN || 'galilei-001',
  // 单次接口调用超时（毫秒）
  timeoutMs: Number(process.env.ROBOT_TIMEOUT_MS || 30000),
  // 调用失败后的重试次数（不含首次）
  maxRetries: Number(process.env.ROBOT_MAX_RETRIES || 0),
  // 内存里保留的消息条数上限
  maxMessages: Number(process.env.MAX_MESSAGES || 500)
};

/** 可以通过页面修改、并持久化到 data/settings.json 的字段 */
const EDITABLE_KEYS = ['robotBaseUrl', 'robotSn', 'timeoutMs', 'maxRetries'];

function normalizeBaseUrl(url) {
  let value = String(url == null ? '' : url).trim();
  if (!value) {
    return DEFAULTS.robotBaseUrl;
  }
  if (!/^https?:\/\//i.test(value)) {
    value = 'http://' + value;
  }
  return value.replace(/\/+$/, '');
}

function loadPersisted() {
  try {
    if (!fs.existsSync(SETTINGS_FILE)) {
      return {};
    }
    const parsed = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
    const result = {};
    for (const key of EDITABLE_KEYS) {
      if (parsed[key] !== undefined && parsed[key] !== null && parsed[key] !== '') {
        result[key] = parsed[key];
      }
    }
    return result;
  } catch (err) {
    console.warn('[config] 读取 settings.json 失败，使用环境变量/默认值:', err.message);
    return {};
  }
}

let settings = Object.assign({}, DEFAULTS, loadPersisted());
settings.robotBaseUrl = normalizeBaseUrl(settings.robotBaseUrl);

function persist() {
  const payload = {};
  for (const key of EDITABLE_KEYS) {
    payload[key] = settings[key];
  }
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(payload, null, 2), 'utf8');
}

/** 返回可暴露给前端的配置 */
function get() {
  return Object.assign({}, settings);
}

/**
 * 更新运行期配置（页面「保存配置」）。
 * @returns {{settings: object, changed: string[]}}
 */
function update(patch) {
  const changed = [];
  const next = Object.assign({}, settings);

  if (patch.robotBaseUrl !== undefined) {
    const url = normalizeBaseUrl(patch.robotBaseUrl);
    if (url !== next.robotBaseUrl) {
      changed.push('robotBaseUrl');
    }
    next.robotBaseUrl = url;
  }
  if (patch.robotSn !== undefined) {
    const sn = String(patch.robotSn).trim() || DEFAULTS.robotSn;
    if (sn !== next.robotSn) {
      changed.push('robotSn');
    }
    next.robotSn = sn;
  }
  if (patch.timeoutMs !== undefined) {
    const timeout = Number(patch.timeoutMs);
    if (Number.isFinite(timeout) && timeout > 0) {
      if (timeout !== next.timeoutMs) {
        changed.push('timeoutMs');
      }
      next.timeoutMs = Math.round(timeout);
    }
  }
  if (patch.maxRetries !== undefined) {
    const retries = Number(patch.maxRetries);
    if (Number.isFinite(retries) && retries >= 0) {
      if (retries !== next.maxRetries) {
        changed.push('maxRetries');
      }
      next.maxRetries = Math.round(retries);
    }
  }

  settings = next;
  if (changed.length > 0) {
    persist();
  }
  return { settings: get(), changed };
}

/**
 * 命令行参数覆盖（优先级最高，不落盘）。
 * 支持：--port --host --robot --sn --timeout --retries
 */
function applyCliOverrides(argv) {
  const args = argv || process.argv.slice(2);
  const read = (name) => {
    const index = args.indexOf(`--${name}`);
    if (index >= 0 && args[index + 1] && !args[index + 1].startsWith('--')) {
      return args[index + 1];
    }
    const withEquals = args.find((item) => item.startsWith(`--${name}=`));
    return withEquals ? withEquals.slice(name.length + 3) : undefined;
  };

  const port = Number(read('port'));
  if (Number.isFinite(port) && port > 0) {
    settings.port = Math.round(port);
  }
  const host = read('host');
  if (host) {
    settings.host = host;
  }
  const robot = read('robot') || read('base-url');
  if (robot) {
    settings.robotBaseUrl = normalizeBaseUrl(robot);
  }
  const sn = read('sn');
  if (sn) {
    settings.robotSn = sn;
  }
  const timeout = Number(read('timeout'));
  if (Number.isFinite(timeout) && timeout > 0) {
    settings.timeoutMs = Math.round(timeout);
  }
  const retries = Number(read('retries'));
  if (Number.isFinite(retries) && retries >= 0) {
    settings.maxRetries = Math.round(retries);
  }
  return get();
}

/** 本机所有 IPv4 地址，用于在页面上提示机器狗该回调哪个地址 */
function localAddresses() {
  const result = [];
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const item of interfaces[name] || []) {
      if (item.family === 'IPv4' && !item.internal) {
        result.push({ name, address: item.address });
      }
    }
  }
  return result;
}

module.exports = {
  ROOT_DIR,
  PUBLIC_DIR,
  DATA_DIR,
  SETTINGS_FILE,
  DEFAULTS,
  EDITABLE_KEYS,
  normalizeBaseUrl,
  localAddresses,
  applyCliOverrides,
  get,
  update
};
