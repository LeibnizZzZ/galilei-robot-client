'use strict';

/**
 * 摄像头抓图客户端。
 *
 * 参考《前言》/抓图接口文档：大华（Dahua）系摄像头抓图接口为
 *   GET http://<ip>:<port>/cgi-bin/snapshot.cgi?channel=<通道号>
 * 返回一张 jpg 图片，但需要 HTTP Digest（摘要）认证：
 *   1. 先不带认证请求一次，从 401 响应的 WWW-Authenticate 头里拿到
 *      realm / nonce / qop / opaque；
 *   2. 用 MD5 按文档公式算出 response，带上 Authorization 头再请求一次。
 *
 * 通道号：1 = 可见光，2 = 热成像（部分设备 0 也是可见光）。
 *
 * 本模块不依赖任何第三方库，只用 Node 自带的 fetch（Node 18+）与 crypto。
 */

const crypto = require('crypto');

/** 抓图接口路径 */
const SNAPSHOT_PATH = '/cgi-bin/snapshot.cgi';

/** 32 位小写 MD5 */
function md5(text) {
  return crypto.createHash('md5').update(String(text), 'utf8').digest('hex');
}

/**
 * 解析 WWW-Authenticate 里的 Digest 参数。
 * 形如：Digest realm="Login to XXX", qop="auth", nonce="220711551", opaque="..."
 */
function parseDigestChallenge(header) {
  const text = String(header || '').replace(/^Digest\s+/i, '');
  const params = {};
  const re = /([a-zA-Z0-9_-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))/g;
  let match;
  while ((match = re.exec(text)) !== null) {
    const key = match[1].toLowerCase();
    const value = match[2] !== undefined ? match[2].replace(/\\(.)/g, '$1') : match[3];
    params[key] = value;
  }
  return params;
}

/**
 * 按 Digest 规范拼出 Authorization 头。
 *
 * HA1      = MD5(username:realm:password)
 * HA2      = MD5(method:uri)
 * response = MD5(HA1:nonce:nc:cnonce:qop:HA2)      // 有 qop
 * response = MD5(HA1:nonce:HA2)                    // 无 qop
 */
function buildAuthorization(options) {
  const username = options.username;
  const password = options.password;
  const method = options.method || 'GET';
  const uri = options.uri;
  const challenge = options.challenge || {};

  const realm = challenge.realm || '';
  const nonce = challenge.nonce || '';
  const opaque = challenge.opaque;
  const algorithm = String(challenge.algorithm || '').toUpperCase();
  const qop = String(challenge.qop || '').split(',')[0].trim();
  const cnonce = crypto.randomBytes(8).toString('hex');
  const nc = '00000001';

  let ha1 = md5(`${username}:${realm}:${password}`);
  if (algorithm === 'MD5-SESS') {
    ha1 = md5(`${ha1}:${nonce}:${cnonce}`);
  }
  const ha2 = md5(`${method}:${uri}`);
  const response = qop
    ? md5(`${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}`)
    : md5(`${ha1}:${nonce}:${ha2}`);

  const parts = [
    `username="${username}"`,
    `realm="${realm}"`,
    `nonce="${nonce}"`,
    `uri="${uri}"`,
    `response="${response}"`
  ];
  if (algorithm) {
    parts.push(`algorithm=${algorithm}`);
  }
  if (opaque) {
    parts.push(`opaque="${opaque}"`);
  }
  if (qop) {
    parts.push(`qop=${qop}`, `nc=${nc}`, `cnonce="${cnonce}"`);
  }
  return `Digest ${parts.join(', ')}`;
}

/** 简易图片魔数判断（JPEG / PNG / BMP），用于识别「HTTP 200 但其实是报错文本」 */
function looksLikeImage(buffer) {
  if (!buffer || buffer.length < 4) {
    return false;
  }
  if (buffer[0] === 0xff && buffer[1] === 0xd8) {
    return true;
  }
  if (buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) {
    return true;
  }
  if (buffer[0] === 0x42 && buffer[1] === 0x4d) {
    return true;
  }
  return false;
}

/**
 * 拼接摄像头接口地址。
 * host 可写纯 IP（8.148.157.75），也可写完整 URL（http://8.148.157.75）。
 * 无论哪种写法，只要 host 里没显式写端口，就补上配置里的 port。
 *
 * @param {object} options   host / port
 * @param {string} pathWithQuery 形如 /cgi-bin/ptz.cgi?action=start
 */
function buildCameraUrl(options, pathWithQuery) {
  const opts = options || {};
  const host = String(opts.host || '').trim();
  if (!host) {
    throw new Error('未配置摄像头地址（cameraHost）');
  }
  const port = Number(opts.port) > 0 ? Math.round(Number(opts.port)) : 80;
  const path = String(pathWithQuery || '');
  if (!path) {
    throw new Error('缺少摄像头接口路径（path）');
  }

  const scheme = /^https:\/\//i.test(host) ? 'https' : 'http';
  // 取出 authority 部分（去掉 scheme、路径、查询串）
  const matched = /^https?:\/\/([^/?#]+)/i.exec(host);
  let authority = (matched ? matched[1] : host).replace(/\/+$/, '');
  // 只有没写端口时才补配置端口，写了就以 host 里的为准
  if (!/:\d+$/.test(authority)) {
    authority = `${authority}:${port}`;
  }

  return `${scheme}://${authority}${path.startsWith('/') ? path : `/${path}`}`;
}

/** 拼接摄像头快照地址 */
function buildSnapshotUrl(options) {
  const opts = options || {};
  const channel = opts.channel === undefined || opts.channel === null || opts.channel === ''
    ? 1
    : Number(opts.channel);
  return buildCameraUrl(opts, `${SNAPSHOT_PATH}?channel=${encodeURIComponent(channel)}`);
}

async function readResult(response, extra, expectImage) {
  const contentType = response.headers.get('content-type') || 'application/octet-stream';
  const buffer = Buffer.from(await response.arrayBuffer());
  const info = Object.assign({
    httpStatus: response.status,
    contentType,
    buffer,
    text: buffer.toString('utf8'),
    bytes: buffer.length
  }, extra);

  if (response.status !== 200) {
    const detail = info.text.slice(0, 200).trim();
    const err = new Error(`摄像头返回 HTTP ${response.status}${detail ? `：${detail}` : ''}`);
    err.httpStatus = response.status;
    err.url = info.url;
    throw err;
  }
  if (expectImage && !looksLikeImage(buffer)) {
    const detail = info.text.slice(0, 200).trim();
    const err = new Error(`摄像头返回的不是图片${detail ? `：${detail}` : ''}（认证失败或通道号不对？）`);
    err.httpStatus = 200;
    err.url = info.url;
    throw err;
  }
  return info;
}

/** 把 fetch 抛出的底层错误换成更易懂的提示 */
function wrapFetchError(err, url, timeoutMs) {
  if (err && (err.name === 'TimeoutError' || err.name === 'AbortError')) {
    const error = new Error(`请求摄像头超时（${timeoutMs}ms）：${url}`);
    error.httpStatus = 504;
    error.url = url;
    return error;
  }
  const cause = err && err.cause ? (err.cause.code || err.cause.message) : '';
  const error = new Error(`无法连接摄像头 ${url}${cause ? `（${cause}）` : ''}`);
  error.httpStatus = 502;
  error.url = url;
  return error;
}

/** 丢弃 401 响应的 body，避免连接一直挂着 */
async function drain(response) {
  try {
    if (response && response.body && typeof response.body.cancel === 'function') {
      await response.body.cancel();
    }
  } catch (err) {
    // 忽略
  }
}

/**
 * 以 Digest 认证请求摄像头的任意 CGI 接口。
 *
 * @param {object} options
 * @param {string} options.host        摄像头 IP 或 http(s)://ip:port
 * @param {number} [options.port]      端口，默认 80
 * @param {string} options.path        接口路径（含查询串），如 /cgi-bin/ptz.cgi?action=start
 * @param {string} [options.method]    HTTP 方法，默认 GET
 * @param {string} [options.username]  用户名
 * @param {string} [options.password]  密码
 * @param {number} [options.timeoutMs] 超时（毫秒）
 * @param {boolean} [options.expectImage] 是否要求返回图片（抓图时传 true）
 * @returns {Promise<{buffer: Buffer, text: string, contentType: string, httpStatus: number, bytes: number, url: string, durationMs: number}>}
 */
async function request(options) {
  const opts = options || {};
  const pathWithQuery = opts.path;
  if (!pathWithQuery) {
    throw new Error('缺少摄像头接口路径（path）');
  }
  const url = buildCameraUrl(opts, pathWithQuery);
  const username = opts.username || 'admin';
  const password = opts.password || '';
  const method = String(opts.method || 'GET').toUpperCase();
  const expectImage = Boolean(opts.expectImage);
  const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : 10000;

  const parsed = new URL(url);
  const uri = parsed.pathname + parsed.search;
  const startedAt = Date.now();

  const first = await fetch(url, {
    method,
    redirect: 'manual',
    signal: AbortSignal.timeout(timeoutMs)
  }).catch((err) => {
    throw wrapFetchError(err, url, timeoutMs);
  });

  if (first.status !== 401) {
    return Object.assign(await readResult(first, { url }, expectImage), { durationMs: Date.now() - startedAt });
  }

  const challengeHeader = first.headers.get('www-authenticate') || '';
  await drain(first);

  if (!/^Digest/i.test(challengeHeader)) {
    const err = new Error(`摄像头要求认证，但返回的不是 Digest 认证头：${challengeHeader || '（空）'}`);
    err.httpStatus = 401;
    err.url = url;
    throw err;
  }

  const challenge = parseDigestChallenge(challengeHeader);
  const authorization = buildAuthorization({ username, password, method, uri, challenge });

  const second = await fetch(url, {
    method,
    redirect: 'manual',
    headers: { Authorization: authorization },
    signal: AbortSignal.timeout(timeoutMs)
  }).catch((err) => {
    throw wrapFetchError(err, url, timeoutMs);
  });

  return Object.assign(await readResult(second, { url }, expectImage), { durationMs: Date.now() - startedAt });
}

/**
 * 抓一张图。
 *
 * @param {object} options
 * @param {string} options.host      摄像头 IP 或 http(s)://ip:port
 * @param {number} [options.port]    端口，默认 80
 * @param {number} [options.channel] 通道号：1 可见光 / 2 热成像
 * @param {string} [options.username] 用户名
 * @param {string} [options.password] 密码
 * @param {number} [options.timeoutMs] 超时（毫秒）
 * @returns {Promise<{buffer: Buffer, contentType: string, httpStatus: number, bytes: number, url: string, durationMs: number}>}
 */
async function snapshot(options) {
  const opts = options || {};
  const channel = opts.channel === undefined || opts.channel === null || opts.channel === ''
    ? 1
    : Number(opts.channel);

  return request({
    host: opts.host,
    port: opts.port,
    username: opts.username,
    password: opts.password,
    timeoutMs: opts.timeoutMs,
    path: `${SNAPSHOT_PATH}?channel=${encodeURIComponent(channel)}`,
    expectImage: true
  });
}

module.exports = {
  SNAPSHOT_PATH,
  md5,
  parseDigestChallenge,
  buildAuthorization,
  buildCameraUrl,
  buildSnapshotUrl,
  looksLikeImage,
  request,
  snapshot
};
