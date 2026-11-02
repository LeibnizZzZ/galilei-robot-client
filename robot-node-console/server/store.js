'use strict';

/**
 * 内存消息中心：记录「下发指令 / 收到回调 / 收到文件上传通知」，
 * 并通过 SSE 实时推给前端页面。
 */

const { EventEmitter } = require('events');

const emitter = new EventEmitter();
// 页面可能开多个标签页，放开监听器上限
emitter.setMaxListeners(0);

let seq = 0;
let capacity = 500;
const messages = [];

function setCapacity(value) {
  const size = Number(value);
  if (Number.isFinite(size) && size > 0) {
    capacity = Math.round(size);
    trim();
  }
}

function trim() {
  while (messages.length > capacity) {
    messages.shift();
  }
}

/**
 * 记录一条消息并广播。
 *
 * @param {object} entry
 * @param {string} entry.kind        command | callBackMess | uploadFile | system
 * @param {string} entry.direction   out（本服务 -> 机器狗） | in（机器狗 -> 本服务） | local
 * @param {string} entry.title       展示标题
 * @param {object} [entry.payload]   请求 / 收到的报文
 * @param {object} [entry.response]  机器狗返回的报文
 */
function record(entry) {
  const message = Object.assign(
    {
      id: ++seq,
      time: new Date().toISOString(),
      kind: 'system',
      direction: 'local',
      title: '',
      code: null,
      name: null,
      async: false,
      success: null,
      pending: false,
      payload: null,
      response: null,
      error: null,
      durationMs: null
    },
    entry
  );

  messages.push(message);
  trim();
  emitter.emit('message', message);
  return message;
}

/** 按时间正序返回（前端自己决定展示顺序） */
function list(limit) {
  const size = Number(limit);
  if (Number.isFinite(size) && size > 0 && messages.length > size) {
    return messages.slice(messages.length - size);
  }
  return messages.slice();
}

function clear() {
  messages.length = 0;
  return true;
}

function subscribe(listener) {
  emitter.on('message', listener);
  return () => emitter.off('message', listener);
}

module.exports = {
  setCapacity,
  record,
  list,
  clear,
  subscribe
};
