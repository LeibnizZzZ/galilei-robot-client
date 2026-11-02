'use strict';

/**
 * 机器狗中间层 API 客户端。
 *
 * 所有指令统一走 POST {robotBaseUrl}/robot/api/common，请求体：
 * { "code": 402, "authKey": "", "reqJson": "{\"xyz\":\"...\"}" }
 * 其中 reqJson 是「字符串」（与接口文档、Java 版 galilei-robot-client 一致）。
 */

const config = require('./config');

const COMMON_PATH = '/robot/api/common';

/** reqJson 允许传对象 / 字符串 / 空 */
function toReqJsonString(reqJson) {
  if (reqJson === undefined || reqJson === null || reqJson === '') {
    return '{}';
  }
  if (typeof reqJson === 'string') {
    return reqJson;
  }
  return JSON.stringify(reqJson);
}

function safeParseJson(text) {
  if (typeof text !== 'string' || text.trim() === '') {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (err) {
    return null;
  }
}

/**
 * 下发一条指令。
 *
 * @param {number} code  指令 code
 * @param {object|string|null} reqJson 指令参数
 * @returns {Promise<object>} 调用明细（含请求体、HTTP 状态、响应体），不会抛异常
 */
async function callCommon(code, reqJson, options) {
  const opts = options || {};
  const current = config.get();
  const baseUrl = config.normalizeBaseUrl(opts.baseUrl || current.robotBaseUrl);
  const url = baseUrl + COMMON_PATH;
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : current.timeoutMs;
  const maxRetries = Number.isFinite(Number(opts.maxRetries)) ? Number(opts.maxRetries) : current.maxRetries;

  const requestBody = {
    code: Number(code),
    authKey: '',
    reqJson: toReqJsonString(reqJson)
  };

  const startedAt = Date.now();
  let attempt = 0;
  let lastError = null;

  while (attempt <= maxRetries) {
    attempt += 1;
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(requestBody),
        signal: AbortSignal.timeout(timeoutMs)
      });

      const text = await response.text();
      const parsed = safeParseJson(text);
      const durationMs = Date.now() - startedAt;

      if (!response.ok) {
        lastError = `HTTP ${response.status} ${response.statusText || ''}`.trim();
        if (attempt <= maxRetries) {
          continue;
        }
        return {
          ok: false,
          url,
          requestBody,
          httpStatus: response.status,
          responseText: text,
          response: parsed,
          error: lastError,
          attempts: attempt,
          durationMs
        };
      }

      return {
        ok: true,
        url,
        requestBody,
        httpStatus: response.status,
        responseText: text,
        response: parsed,
        // 业务成功口径：接口文档约定 code == 200
        businessOk: parsed ? parsed.code === 200 : false,
        error: null,
        attempts: attempt,
        durationMs
      };
    } catch (err) {
      lastError = err && err.name === 'TimeoutError'
        ? `请求超时（${timeoutMs}ms）`
        : (err && err.message) || String(err);
      if (attempt > maxRetries) {
        break;
      }
    }
  }

  return {
    ok: false,
    url,
    requestBody,
    httpStatus: null,
    responseText: null,
    response: null,
    error: lastError,
    attempts: attempt,
    durationMs: Date.now() - startedAt
  };
}

module.exports = {
  COMMON_PATH,
  toReqJsonString,
  safeParseJson,
  callCommon
};
