'use strict';

/**
 * HTTP 接口层。
 *
 * ┌ 前端页面调用 ───────────────────────────────────────────────┐
 * │ GET  /api/bootstrap        页面初始化数据（配置 / 指令 / 历史消息）│
 * │ GET  /api/events           SSE 实时消息推送                      │
 * │ POST /api/robot/command    下发指令到机器狗 /robot/api/common     │
 * │ GET  /api/camera/snapshot  摄像头抓图（代理 Digest 认证，返回图片）│
 * │ POST /api/camera/ptz       云台控制（上/下/左/右，Digest 认证）      │
 * │ POST /api/camera/ptz/preset  当前位置存为预置点（默认 999）        │
 * │ POST /api/camera/ptz/powerup 把预置点设为开机位置（默认 999）      │
 * │ POST /api/messages/clear   清空消息                              │
 * │ PUT  /api/settings         修改运行期配置                        │
 * └──────────────────────────────────────────────────────────────┘
 * ┌ 机器狗调用（上行） ─────────────────────────────────────────┐
 * │ POST /api/robot/callBackMess  异步指令执行结果回调              │
 * │ POST /api/robot/uploadFile    业务文件上传通知                  │
 * └──────────────────────────────────────────────────────────────┘
 */

const express = require('express');
const config = require('./config');
const store = require('./store');
const pending = require('./pending');
const commands = require('./commands');
const robotApi = require('./robotApi');
const camera = require('./camera');
const ptz = require('./ptz');

const router = express.Router();

/** 业务文件类别（接口文档 fileType） */
const FILE_TYPES = {
  1: 'PCD图',
  2: '灰度图',
  3: 'YAML文件'
};

function fileTypeName(fileType) {
  const key = Number(fileType);
  return FILE_TYPES[key] || `未知类型(${fileType})`;
}

function callbackUrls(req) {
  const current = config.get();
  const port = current.port;
  const urls = config.localAddresses().map((item) => ({
    name: item.name,
    address: item.address,
    callBackMess: `http://${item.address}:${port}/api/robot/callBackMess`,
    uploadFile: `http://${item.address}:${port}/api/robot/uploadFile`
  }));
  if (req) {
    urls.unshift({
      name: '本次请求来源',
      address: req.hostname,
      callBackMess: `${req.protocol}://${req.get('host')}/api/robot/callBackMess`,
      uploadFile: `${req.protocol}://${req.get('host')}/api/robot/uploadFile`
    });
  }
  return urls;
}

// ==================== 前端：初始化 / 实时推送 ====================

router.get('/api/bootstrap', (req, res) => {
  res.json({
    code: 200,
    msg: 'ok',
    data: {
      settings: config.get(),
      commands: commands.list(),
      messages: store.list(),
      pendings: pending.list(),
      server: {
        port: config.get().port,
        callbackUrls: callbackUrls(req)
      }
    }
  });
});

router.get('/api/events', (req, res) => {
  res.set({
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  res.flushHeaders();

  res.write('retry: 3000\n\n');
  res.write(`event: ready\ndata: ${JSON.stringify({ time: new Date().toISOString() })}\n\n`);

  const unsubscribe = store.subscribe((message) => {
    res.write(`event: message\ndata: ${JSON.stringify(message)}\n\n`);
  });

  // 心跳，避免代理断开空闲连接
  const heartbeat = setInterval(() => {
    res.write(`event: ping\ndata: ${JSON.stringify({ time: new Date().toISOString() })}\n\n`);
  }, 25000);

  req.on('close', () => {
    clearInterval(heartbeat);
    unsubscribe();
    res.end();
  });
});

router.get('/api/messages', (req, res) => {
  res.json({ code: 200, msg: 'ok', data: store.list(req.query.limit) });
});

router.post('/api/messages/clear', (req, res) => {
  store.clear();
  pending.clear();
  res.json({ code: 200, msg: 'ok' });
});

router.get('/api/pendings', (req, res) => {
  res.json({ code: 200, msg: 'ok', data: pending.list() });
});

// ==================== 前端：配置 ====================

router.get('/api/settings', (req, res) => {
  res.json({ code: 200, msg: 'ok', data: config.get() });
});

router.put('/api/settings', (req, res) => {
  const result = config.update(req.body || {});
  if (result.changed.length > 0) {
    store.record({
      kind: 'system',
      direction: 'local',
      title: `配置已更新：${result.changed.join('、')}`,
      payload: result.settings
    });
  }
  res.json({ code: 200, msg: 'ok', data: result.settings, changed: result.changed });
});

// ==================== 前端：下发指令 ====================

router.post('/api/robot/command', async (req, res) => {
  const body = req.body || {};
  const code = Number(body.code);
  if (!Number.isFinite(code)) {
    return res.status(400).json({ code: 400, msg: '缺少指令 code' });
  }

  const definition = commands.findByCode(code);
  const name = body.name || (definition ? definition.name : `code ${code}`);
  const isAsync = body.async !== undefined ? Boolean(body.async) : Boolean(definition && definition.async);

  // 支持直接给字符串 reqJson（原样下发），否则用对象序列化
  let reqJson = body.reqJson;
  if (reqJson === undefined && body.rawReqJson !== undefined) {
    reqJson = body.rawReqJson;
  }

  const call = await robotApi.callCommon(code, reqJson, {
    baseUrl: body.baseUrl,
    timeoutMs: body.timeoutMs
  });

  const accepted = call.ok && call.businessOk !== false;

  const message = store.record({
    kind: 'command',
    direction: 'out',
    title: `下发指令 ${code} ${name}`,
    code,
    name,
    async: isAsync,
    success: accepted,
    pending: accepted && isAsync,
    payload: {
      url: call.url,
      body: call.requestBody
    },
    response: call.response !== null && call.response !== undefined
      ? call.response
      : call.responseText,
    error: call.error,
    durationMs: call.durationMs
  });

  let pendingRecord = null;
  if (accepted && isAsync) {
    pendingRecord = pending.register({
      code,
      name,
      reqJson: call.requestBody.reqJson
    });
  }

  res.json({
    code: 200,
    msg: accepted ? 'ok' : 'failed',
    data: {
      accepted,
      async: isAsync,
      url: call.url,
      httpStatus: call.httpStatus,
      attempts: call.attempts,
      durationMs: call.durationMs,
      requestBody: call.requestBody,
      response: call.response !== null && call.response !== undefined ? call.response : call.responseText,
      error: call.error,
      messageId: message.id,
      pending: pendingRecord
        ? { id: pendingRecord.id, code: pendingRecord.code, name: pendingRecord.name, time: pendingRecord.time }
        : null
    }
  });
});

// ==================== 前端：摄像头抓图 ====================

/**
 * GET /api/camera/snapshot?channel=1
 *
 * 后端代理摄像头的 Digest 认证，成功后直接返回图片二进制（image/jpeg），
 * 失败返回 JSON { code, msg }。channel 缺省时用配置里的 cameraChannel。
 */
router.get('/api/camera/snapshot', async (req, res) => {
  const current = config.get();
  const channel = req.query.channel !== undefined && req.query.channel !== ''
    ? req.query.channel
    : current.cameraChannel;
  const host = req.query.host || current.cameraHost;

  try {
    const result = await camera.snapshot({
      host,
      port: current.cameraPort,
      channel,
      username: current.cameraUsername,
      password: current.cameraPassword,
      timeoutMs: current.cameraTimeoutMs
    });

    store.record({
      kind: 'camera',
      direction: 'out',
      title: `摄像头抓图成功：通道 ${channel}，${(result.bytes / 1024).toFixed(1)} KB`,
      success: true,
      payload: { url: result.url, channel },
      response: {
        httpStatus: result.httpStatus,
        contentType: result.contentType,
        bytes: result.bytes
      },
      durationMs: result.durationMs
    });

    res.set({
      'Content-Type': result.contentType,
      'Content-Length': String(result.bytes),
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      Pragma: 'no-cache'
    });
    res.end(result.buffer);
  } catch (err) {
    store.record({
      kind: 'camera',
      direction: 'out',
      title: `摄像头抓图失败：通道 ${channel}`,
      success: false,
      payload: { url: err.url || null, channel },
      error: err.message
    });
    res.status(502).json({ code: 500, msg: err.message });
  }
});

// ==================== 前端：摄像头云台控制（5.2） ====================

/**
 * POST /api/camera/ptz
 *
 * body：{ action: 'start'|'stop', code: 'Up'|'Down'|'Left'|'Right'|..., channel?, arg1?, arg2?, arg3?, arg4? }
 * 后端代理摄像头的 Digest 认证，请求 /cgi-bin/ptz.cgi，摄像头返回 OK / ERROR。
 * channel 缺省时用配置里的 cameraChannel。
 */
router.post('/api/camera/ptz', async (req, res) => {
  const body = req.body || {};
  const current = config.get();
  const action = body.action || 'start';
  const code = body.code;
  const channel = body.channel !== undefined && body.channel !== '' ? body.channel : current.cameraChannel;
  const args = {
    arg1: body.arg1,
    arg2: body.arg2,
    arg3: body.arg3,
    arg4: body.arg4
  };

  try {
    const result = await ptz.control({
      host: current.cameraHost,
      port: current.cameraPort,
      username: current.cameraUsername,
      password: current.cameraPassword,
      timeoutMs: current.cameraTimeoutMs,
      action,
      code,
      channel,
      arg1: args.arg1,
      arg2: args.arg2,
      arg3: args.arg3,
      arg4: args.arg4
    });

    const payload = Object.assign({ url: result.url, action, code, channel }, args);

    if (!result.ok) {
      const message = `云台返回：${result.reply || '（空）'}`;
      store.record({
        kind: 'ptz',
        direction: 'out',
        title: `云台 ${action} ${code} 失败：通道 ${channel}`,
        success: false,
        payload,
        error: message,
        durationMs: result.durationMs
      });
      return res.status(502).json({ code: 500, msg: message, data: { action, code, channel, reply: result.reply } });
    }

    store.record({
      kind: 'ptz',
      direction: 'out',
      title: `云台 ${action} ${code} 成功：通道 ${channel}`,
      success: true,
      payload,
      response: { httpStatus: result.httpStatus, reply: result.reply },
      durationMs: result.durationMs
    });

    res.json({
      code: 200,
      msg: 'ok',
      data: { action, code, channel, reply: result.reply, url: result.url, durationMs: result.durationMs }
    });
  } catch (err) {
    store.record({
      kind: 'ptz',
      direction: 'out',
      title: `云台 ${action} ${code} 请求失败：通道 ${channel}`,
      success: false,
      payload: { action, code, channel },
      error: err.message
    });
    res.status(502).json({ code: 500, msg: err.message });
  }
});

/**
 * POST /api/camera/ptz/preset
 *
 * body：{ presetId?, channel? }
 * 把云台当前位置保存为预置点（ptz.cgi code=SetPreset），presetId 缺省 999。
 */
router.post('/api/camera/ptz/preset', async (req, res) => {
  const body = req.body || {};
  const current = config.get();
  const channel = body.channel !== undefined && body.channel !== '' ? body.channel : current.cameraChannel;
  const presetId = body.presetId !== undefined && body.presetId !== '' ? Number(body.presetId) : 999;

  if (!Number.isFinite(presetId) || presetId <= 0) {
    return res.status(400).json({ code: 400, msg: `预置点号不合法：${body.presetId}` });
  }

  try {
    const result = await ptz.control({
      host: current.cameraHost,
      port: current.cameraPort,
      username: current.cameraUsername,
      password: current.cameraPassword,
      timeoutMs: current.cameraTimeoutMs,
      action: 'start',
      code: 'SetPreset',
      channel,
      arg1: 0,
      arg2: presetId,
      arg3: 0
    });

    const title = `设置预置点 ${presetId}：通道 ${channel}`;
    if (!result.ok) {
      const message = `云台返回：${result.reply || '（空）'}`;
      store.record({ kind: 'ptz', direction: 'out', title, success: false, payload: { presetId, channel }, error: message, durationMs: result.durationMs });
      return res.status(502).json({ code: 500, msg: message, data: { presetId, channel, reply: result.reply } });
    }

    store.record({ kind: 'ptz', direction: 'out', title, success: true, payload: { presetId, channel }, response: { httpStatus: result.httpStatus, reply: result.reply }, durationMs: result.durationMs });
    res.json({ code: 200, msg: 'ok', data: { presetId, channel, reply: result.reply, url: result.url } });
  } catch (err) {
    store.record({ kind: 'ptz', direction: 'out', title: `设置预置点 ${presetId} 失败：通道 ${channel}`, success: false, payload: { presetId, channel }, error: err.message });
    res.status(502).json({ code: 500, msg: err.message });
  }
});

/**
 * POST /api/camera/ptz/powerup
 *
 * body：{ presetId?, channel? }
 * 把指定预置点设为云台开机动作（configManager.cgi PowerUp），presetId 缺省 999。
 */
router.post('/api/camera/ptz/powerup', async (req, res) => {
  const body = req.body || {};
  const current = config.get();
  const channel = body.channel !== undefined && body.channel !== '' ? body.channel : current.cameraChannel;
  const presetId = body.presetId !== undefined && body.presetId !== '' ? Number(body.presetId) : 999;

  if (!Number.isFinite(presetId) || presetId <= 0) {
    return res.status(400).json({ code: 400, msg: `预置点号不合法：${body.presetId}` });
  }

  try {
    const result = await ptz.setPowerUp({
      host: current.cameraHost,
      port: current.cameraPort,
      username: current.cameraUsername,
      password: current.cameraPassword,
      timeoutMs: current.cameraTimeoutMs,
      channel,
      presetId
    });

    const title = `设置开机位置为预置点 ${presetId}：通道 ${channel}`;
    if (!result.ok) {
      const message = `摄像头返回：${result.reply || '（空）'}`;
      store.record({ kind: 'ptz', direction: 'out', title, success: false, payload: { presetId, channel }, error: message, durationMs: result.durationMs });
      return res.status(502).json({ code: 500, msg: message, data: { presetId, channel, reply: result.reply } });
    }

    store.record({ kind: 'ptz', direction: 'out', title, success: true, payload: { presetId, channel }, response: { httpStatus: result.httpStatus, reply: result.reply }, durationMs: result.durationMs });
    res.json({ code: 200, msg: 'ok', data: { presetId, channel, reply: result.reply, url: result.url } });
  } catch (err) {
    store.record({ kind: 'ptz', direction: 'out', title: `设置开机位置失败：通道 ${channel}`, success: false, payload: { presetId, channel }, error: err.message });
    res.status(502).json({ code: 500, msg: err.message });
  }
});

// ==================== 机器狗 -> 本服务：异步回调 ====================

router.post('/api/robot/callBackMess', (req, res) => {
  const body = req.body || {};
  const messType = body.messType !== undefined ? Number(body.messType) : null;
  const success = String(body.messVal) === '1';

  const matched = messType === null ? null : pending.consume(messType);
  const code = matched ? matched.code : (messType === null ? null : commands.resolveCallbackCode(messType));
  const definition = code === null ? null : commands.findByCode(code);

  store.record({
    kind: 'callBackMess',
    direction: 'in',
    title: `收到回调 callBackMess：messType=${body.messType} ${success ? '成功' : '失败'}`,
    code,
    name: matched && matched.name ? matched.name : definition ? definition.name : null,
    async: true,
    success,
    pending: false,
    payload: body,
    response: null,
    error: success ? null : body.readme || null,
    matchedPending: matched
      ? { id: matched.id, code: matched.code, name: matched.name, time: matched.time, reqJson: matched.reqJson }
      : null
  });

  // 按接口文档返回
  res.json({ code: 200, msg: 'ok' });
});

// ==================== 机器狗 -> 本服务：业务文件上传通知 ====================

router.post('/api/robot/uploadFile', (req, res) => {
  const body = req.body || {};
  const fileType = body.fileType;

  store.record({
    kind: 'uploadFile',
    direction: 'in',
    title: `收到文件上传通知：${fileTypeName(fileType)} ${body.filePath || ''}`,
    payload: body,
    response: null,
    success: true
  });

  res.json({ code: 0, msg: '', data: null });
});

// ==================== 其他 ====================

router.get('/api/health', (req, res) => {
  res.json({ code: 200, msg: 'ok', data: { time: new Date().toISOString(), uptime: process.uptime() } });
});

module.exports = {
  router,
  callbackUrls
};
