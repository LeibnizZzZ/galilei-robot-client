'use strict';

/**
 * 异步指令待回执登记表。
 *
 * 下发异步指令（104 回充 / 402 一键导航 / 309 开始建图）后，HTTP 200 只代表已受理，
 * 机器狗会在执行结束时回调 /api/robot/callBackMess。这里记下「谁发的、什么时间发的」，
 * 回调到达时就能把结果和原始指令对上（并处理 402 <-> 320 这类 code 不一致的情况）。
 */

const { resolveCallbackCode } = require('./commands');

const DEFAULT_TTL_MS = 30 * 60 * 1000;

let seq = 0;
const pendings = [];

function register({ code, name, reqJson, ttlMs }) {
  const pending = {
    id: ++seq,
    code: Number(code),
    name: name || null,
    reqJson: reqJson == null ? null : reqJson,
    time: new Date().toISOString(),
    expireAt: Date.now() + (Number(ttlMs) > 0 ? Number(ttlMs) : DEFAULT_TTL_MS)
  };
  pendings.push(pending);
  return pending;
}

function prune() {
  const now = Date.now();
  for (let i = pendings.length - 1; i >= 0; i--) {
    if (pendings[i].expireAt <= now) {
      pendings.splice(i, 1);
    }
  }
}

/**
 * 按回调的 messType 取出并移除一条待回执记录。
 * 优先精确匹配，其次按别名（320 -> 402）匹配；同一 code 多条时取最早的一条（FIFO）。
 */
function consume(messType) {
  prune();
  const code = resolveCallbackCode(messType);
  let index = pendings.findIndex((item) => item.code === code);
  if (index < 0) {
    index = pendings.findIndex((item) => item.code === Number(messType));
  }
  if (index < 0) {
    return null;
  }
  return pendings.splice(index, 1)[0];
}

function list() {
  prune();
  return pendings.slice();
}

function clear() {
  pendings.length = 0;
}

module.exports = {
  register,
  consume,
  list,
  clear
};
