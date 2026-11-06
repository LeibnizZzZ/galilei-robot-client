'use strict';

/**
 * 云台（PTZ）控制客户端。
 *
 * 参考《Guide_HTTP_API_CGI》5.2 云台控制：
 *   GET http://<ip>:<port>/cgi-bin/ptz.cgi?action=<action>&channel=<ch>&code=<code>&arg1=&arg2=&arg3=
 *
 * - action：start（启动）/ stop（停止）
 * - channel：0、1 可见光 / 2 热成像（本控制台默认用配置里的 cameraChannel）
 * - code：云台操作指令，如 Up / Down / Left / Right / ZoomWide / ZoomTele / ...
 * - arg1、arg2、arg3：操作参数，含义随 code 变化（详见 5.2.2 表格）
 *   方向指令（Up / Down / Left / Right）：arg1=0，arg2=速度[1-8]，arg3=0
 *
 * 答复：OK 或 ERROR
 *
 * 复用 camera.js 的 Digest 认证请求能力，不额外依赖第三方库。
 */

const camera = require('./camera');

/** 云台接口路径 */
const PTZ_PATH = '/cgi-bin/ptz.cgi';

/** 配置管理接口路径（设置开机动作等） */
const CONFIG_MANAGER_PATH = '/cgi-bin/configManager.cgi';

/** 页面方向键用到的 code（5.2.2 表格节选，便于以后扩展） */
const PTZ_CODES = {
  Up: '云台垂直向上',
  Down: '云台垂直向下',
  Left: '云台水平逆时针',
  Right: '云台水平顺时针',
  LeftUp: '云台左上',
  RightUp: '云台右上',
  LeftDown: '云台左下',
  RightDown: '云台右下',
  ZoomWide: '变倍至广角',
  ZoomTele: '变倍至长焦',
  FocusNear: '变焦至近焦',
  FocusFar: '变焦至远焦'
};

/** 方向键（单方向）用到的 code */
const DIRECTION_CODES = ['Up', 'Down', 'Left', 'Right'];

/**
 * 拼出 ptz.cgi 的路径（含查询串）。
 *
 * @param {object} options
 * @param {string} options.action  start / stop
 * @param {string} options.code    Up / Down / ...
 * @param {number} [options.channel] 通道号，默认 1
 * @param {*} [options.arg1]
 * @param {*} [options.arg2]
 * @param {*} [options.arg3]
 * @param {*} [options.arg4]  仅 Continuously 会用到（移动时间）
 */
function buildPtzPath(options) {
  const opts = options || {};
  const action = String(opts.action || 'start').toLowerCase();
  if (action !== 'start' && action !== 'stop') {
    throw new Error(`云台 action 只能是 start / stop，收到：${opts.action}`);
  }
  const code = String(opts.code || '').trim();
  if (!code) {
    throw new Error('缺少云台指令 code');
  }
  const channel = opts.channel === undefined || opts.channel === null || opts.channel === ''
    ? 1
    : Number(opts.channel);

  const params = new URLSearchParams();
  params.set('action', action);
  params.set('channel', String(channel));
  params.set('code', code);

  // 文档示例里方向指令会带 arg1/arg2/arg3，缺省补 0；arg4 只有传了才带
  [opts.arg1, opts.arg2, opts.arg3, opts.arg4].forEach((value, index) => {
    const name = `arg${index + 1}`;
    if (value === undefined || value === null || value === '') {
      if (index < 3) {
        params.set(name, '0');
      }
      return;
    }
    params.set(name, String(value));
  });

  return `${PTZ_PATH}?${params.toString()}`;
}

/**
 * 下发一次云台控制指令。
 *
 * @param {object} options
 * @param {string} options.host       摄像头 IP 或 http(s)://ip:port
 * @param {number} [options.port]     端口，默认 80
 * @param {string} [options.username] 用户名
 * @param {string} [options.password] 密码
 * @param {number} [options.timeoutMs] 超时（毫秒）
 * @param {string} options.action     start / stop
 * @param {string} options.code       Up / Down / Left / Right / ...
 * @param {number} [options.channel]  通道号
 * @param {*} [options.arg1] / [options.arg2] / [options.arg3] / [options.arg4]
 * @returns {Promise<{ok: boolean, reply: string, path: string, url: string, httpStatus: number, durationMs: number}>}
 */
async function control(options) {
  const opts = options || {};
  const path = buildPtzPath(opts);
  const result = await camera.request({
    host: opts.host,
    port: opts.port,
    username: opts.username,
    password: opts.password,
    timeoutMs: opts.timeoutMs,
    path
  });

  const reply = String(result.text || '').trim();
  return Object.assign({}, result, {
    ok: /^ok$/i.test(reply),
    reply,
    path
  });
}

/**
 * 拼出 configManager.cgi 的路径，把某个预置点设为开机动作。
 *
 * 参考《Guide_HTTP_API_CGI》5.3.8 开机动作：
 *   PowerUp[<channel>].Enable=true
 *   PowerUp[<channel>].Function=Preset
 *   PowerUp[<channel>].PresetId=<预置点号>
 *
 * @param {object} options
 * @param {number} [options.channel]  通道号
 * @param {number} options.presetId   预置点号
 */
function buildPowerUpPath(options) {
  const opts = options || {};
  const channel = opts.channel === undefined || opts.channel === null || opts.channel === ''
    ? 1
    : Number(opts.channel);
  const presetId = Number(opts.presetId);
  if (!Number.isFinite(presetId) || presetId <= 0) {
    throw new Error(`开机动作预置点号不合法：${opts.presetId}`);
  }

  const params = new URLSearchParams();
  params.set('action', 'setConfig');
  params.set(`PowerUp[${channel}].Enable`, 'true');
  params.set(`PowerUp[${channel}].Function`, 'Preset');
  params.set(`PowerUp[${channel}].PresetId`, String(presetId));
  return `${CONFIG_MANAGER_PATH}?${params.toString()}`;
}

/**
 * 将指定预置点设为云台开机动作。
 *
 * @param {object} options  同 control，另需 options.presetId
 */
async function setPowerUp(options) {
  const opts = options || {};
  const path = buildPowerUpPath(opts);
  const result = await camera.request({
    host: opts.host,
    port: opts.port,
    username: opts.username,
    password: opts.password,
    timeoutMs: opts.timeoutMs,
    path
  });

  const reply = String(result.text || '').trim();
  return Object.assign({}, result, {
    ok: /^ok$/i.test(reply),
    reply,
    path
  });
}

module.exports = {
  PTZ_PATH,
  CONFIG_MANAGER_PATH,
  PTZ_CODES,
  DIRECTION_CODES,
  buildPtzPath,
  buildPowerUpPath,
  control,
  setPowerUp
};
