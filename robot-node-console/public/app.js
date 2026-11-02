'use strict';

/**
 * 机器狗接口联调控制台 —— 前端逻辑。
 *
 * 左侧是一排简洁的指令按钮：点一下就把指令发到机器狗，
 * 请求与响应、以及机器狗回调上来的消息，统一显示在右侧消息流里。
 */

// ==================== 全局状态 ====================

const state = {
  settings: null,
  commands: [],
  messages: [],
  pendings: [],
  filter: 'all',
  autoScroll: true,
  connection: 'connecting',
  lastStatus: null
};

const MAX_RENDER = 300;

// ==================== 工具 ====================

const $ = (selector) => document.querySelector(selector);

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[ch]));
}

/** JSON 语法着色（先转义再着色，避免 XSS） */
function highlightJson(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  return escapeHtml(text).replace(
    /("(?:\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+\-]?\d+)?)/g,
    (match) => {
      let cls = 'j-num';
      if (match.startsWith('"')) {
        cls = /:\s*$/.test(match) ? 'j-key' : 'j-str';
      } else if (match === 'true' || match === 'false') {
        cls = 'j-bool';
      } else if (match === 'null') {
        cls = 'j-null';
      }
      return `<span class="${cls}">${match}</span>`;
    }
  );
}

function formatTime(iso) {
  const date = iso ? new Date(iso) : new Date();
  if (Number.isNaN(date.getTime())) {
    return String(iso);
  }
  const pad = (n, len = 2) => String(n).padStart(len, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

let toastTimer = null;
function notify(text, type) {
  let box = $('#toast');
  if (!box) {
    box = document.createElement('div');
    box.id = 'toast';
    box.style.cssText = [
      'position:fixed', 'right:20px', 'bottom:20px', 'z-index:99',
      'padding:10px 14px', 'border-radius:8px', 'font-size:13px',
      'background:#1e222b', 'border:1px solid #2a2f3a', 'color:#e6e9ef',
      'box-shadow:0 8px 24px rgba(0,0,0,.45)', 'max-width:420px', 'transition:opacity .2s'
    ].join(';');
    document.body.appendChild(box);
  }
  box.textContent = text;
  box.style.borderColor = type === 'error' ? '#ef4444' : type === 'ok' ? '#22c55e' : '#2a2f3a';
  box.style.opacity = '1';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.style.opacity = '0'; }, 2600);
}

async function api(url, options) {
  const response = await fetch(url, Object.assign({ headers: { 'Content-Type': 'application/json' } }, options));
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (err) {
    throw new Error(`返回不是合法 JSON: ${text.slice(0, 200)}`);
  }
  if (!response.ok) {
    throw new Error((data && data.msg) || `HTTP ${response.status}`);
  }
  return data;
}

// ==================== 指令按钮 ====================

function renderCommands() {
  const grid = $('#btn-grid');
  grid.innerHTML = '';

  for (const command of state.commands) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'cmd-btn';
    button.dataset.code = String(command.code);
    button.title = command.description || command.name;
    button.innerHTML =
      `<span class="cmd-label">${escapeHtml(command.name)}</span>` +
      `<span class="cmd-code">${command.code}</span>` +
      (command.async ? '<span class="cmd-async">异步</span>' : '');
    button.addEventListener('click', () => sendCommand(command, button));
    grid.appendChild(button);
  }
}

/** 弹框里用的一个输入项 */
function buildParamField(param) {
  const label = document.createElement('label');
  const caption = document.createElement('span');
  caption.textContent = param.label || param.name;
  label.appendChild(caption);

  let input;
  if (param.type === 'select') {
    input = document.createElement('select');
    for (const option of param.options || []) {
      const opt = document.createElement('option');
      opt.value = String(option.value);
      opt.textContent = option.label;
      input.appendChild(opt);
    }
    if (param.default !== undefined) {
      input.value = String(param.default);
    }
  } else {
    input = document.createElement('input');
    input.type = param.type === 'number' ? 'number' : 'text';
    if (param.placeholder) {
      input.placeholder = param.placeholder;
    }
    if (param.default !== undefined && param.default !== null) {
      input.value = String(param.default);
    }
  }
  input.dataset.param = param.name;
  input.dataset.valueType = param.valueType || (param.type === 'number' ? 'number' : 'string');
  label.appendChild(input);
  return label;
}

/** 从容器里的输入框拼出 reqJson */
function collectReqJson(command, container) {
  const reqJson = {};
  for (const input of container.querySelectorAll('[data-param]')) {
    const raw = input.value;
    if (raw === undefined || raw === null || String(raw).trim() === '') {
      continue;
    }
    if (input.dataset.valueType === 'number') {
      const num = Number(raw);
      reqJson[input.dataset.param] = Number.isFinite(num) ? num : raw;
    } else {
      reqJson[input.dataset.param] = raw;
    }
  }
  // 404 设置狗窝：安全位置留空时与狗窝位置一致
  if (command.key === 'setDogHouse' && !reqJson.safe_xyz && reqJson.xyz) {
    reqJson.safe_xyz = reqJson.xyz;
  }
  return reqJson;
}

/**
 * 需要参数的指令：点按钮后弹输入框。
 * @returns {Promise<object|null>} 确定返回 reqJson，取消返回 null
 */
function openParamDialog(command) {
  return new Promise((resolve) => {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';

    const dialog = document.createElement('form');
    dialog.className = 'modal';

    const title = document.createElement('h3');
    title.className = 'modal-title';
    title.innerHTML = `${escapeHtml(command.name)} <span class="cmd-code">${command.code}</span>`;
    dialog.appendChild(title);

    if (command.description) {
      const desc = document.createElement('p');
      desc.className = 'modal-desc';
      desc.textContent = command.description;
      dialog.appendChild(desc);
    }

    const fields = document.createElement('div');
    fields.className = 'modal-fields';
    for (const param of command.params || []) {
      fields.appendChild(buildParamField(param));
    }
    dialog.appendChild(fields);

    const actions = document.createElement('div');
    actions.className = 'modal-actions';
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'btn btn-ghost';
    cancel.textContent = '取消';
    const confirm = document.createElement('button');
    confirm.type = 'submit';
    confirm.className = 'btn btn-primary';
    confirm.textContent = '下发';
    actions.append(cancel, confirm);
    dialog.appendChild(actions);

    overlay.appendChild(dialog);

    const close = (result) => {
      document.removeEventListener('keydown', onKeydown);
      overlay.remove();
      resolve(result);
    };
    const onKeydown = (event) => {
      if (event.key === 'Escape') {
        close(null);
      }
    };

    cancel.addEventListener('click', () => close(null));
    overlay.addEventListener('mousedown', (event) => {
      if (event.target === overlay) {
        close(null);
      }
    });
    dialog.addEventListener('submit', (event) => {
      event.preventDefault();
      close(collectReqJson(command, dialog));
    });
    document.addEventListener('keydown', onKeydown);

    document.body.appendChild(overlay);
    const first = dialog.querySelector('input, select');
    if (first) {
      first.focus();
      if (first.select) {
        first.select();
      }
    }
  });
}

/** 按钮闪一下表示结果：绿色成功 / 红色失败 / 黄色等待回调 */
function flash(button, result) {
  const cls = `is-${result}`;
  button.classList.remove('is-ok', 'is-err', 'is-wait');
  button.classList.add(cls);
  if (result !== 'wait') {
    clearTimeout(button._flashTimer);
    button._flashTimer = setTimeout(() => button.classList.remove(cls), 2500);
  }
}

async function sendCommand(command, button) {
  // 需要参数的指令：先弹输入框
  let reqJson = {};
  if ((command.params || []).length > 0) {
    const input = await openParamDialog(command);
    if (input === null) {
      return;
    }
    reqJson = input;
  }

  button.disabled = true;
  try {
    const res = await api('/api/robot/command', {
      method: 'POST',
      body: JSON.stringify({ code: command.code, name: command.name, reqJson })
    });
    const data = res.data || {};
    if (!data.accepted) {
      flash(button, 'err');
      notify(`${command.name} 下发失败：${data.error || '机器狗返回异常'}`, 'error');
      return;
    }
    if (data.async) {
      flash(button, 'wait');
      notify(`${command.name} 已受理，等待回调`, 'ok');
    } else {
      flash(button, 'ok');
      if (command.resultView === 'status') {
        updateStatus(data.response);
      }
    }
  } catch (err) {
    flash(button, 'err');
    notify(`${command.name} 请求失败：${err.message}`, 'error');
  } finally {
    button.disabled = false;
  }
}

// ==================== 运行状态条 ====================

function updateStatus(response) {
  if (!response || typeof response !== 'object') {
    return;
  }
  const data = response.data && typeof response.data === 'object' ? response.data : response;
  const fields = [
    ['状态 sta', data.sta],
    ['电量 dl', data.dl === undefined || data.dl === null ? null : `${data.dl}%`],
    ['网络 net', data.net],
    ['温度 wd', data.wd],
    ['坐标 xyz', data.xyz],
    ['GPS', data.gps],
    ['噪声', data.decibel],
    ['点位 posId', data.posId]
  ].filter((item) => item[1] !== undefined && item[1] !== null && item[1] !== '');

  if (fields.length === 0) {
    return;
  }
  state.lastStatus = data;
  const strip = $('#status-strip');
  strip.hidden = false;
  strip.innerHTML = fields
    .map(([key, value]) => `<span class="status-cell"><b>${escapeHtml(key)}</b>${escapeHtml(value)}</span>`)
    .join('');
}

// ==================== 消息流 ====================

const KIND_LABEL = {
  callBackMess: '回调',
  uploadFile: '文件上传',
  command: '指令',
  system: '系统'
};

function matchFilter(message) {
  switch (state.filter) {
    case 'in': return message.direction === 'in';
    case 'callBackMess': return message.kind === 'callBackMess';
    case 'uploadFile': return message.kind === 'uploadFile';
    case 'command': return message.kind === 'command';
    case 'fail': return message.success === false || Boolean(message.error);
    default: return true;
  }
}

function renderFeed() {
  const feed = $('#feed');
  const list = state.messages.filter(matchFilter).slice(-MAX_RENDER);

  if (list.length === 0) {
    feed.innerHTML = '<div class="empty">暂无消息。点击左侧按钮下发指令，或等待机器狗回调。</div>';
    return;
  }

  feed.innerHTML = '';
  for (const message of list) {
    feed.appendChild(buildMessageNode(message));
  }
  if (state.autoScroll) {
    feed.scrollTop = feed.scrollHeight;
  }
}

function buildMessageNode(message) {
  const node = document.createElement('div');
  node.className = 'msg';
  node.classList.add(message.direction === 'in' ? 'msg-in' : message.direction === 'out' ? 'msg-out' : 'msg-system');
  if (message.pending) {
    node.classList.add('msg-pending');
  } else if (message.success === false || message.error) {
    node.classList.add('msg-fail');
  }

  const head = document.createElement('div');
  head.className = 'msg-head';

  const badges = [];
  if (message.direction === 'in') {
    badges.push('<span class="badge badge-in">收到</span>');
  } else if (message.direction === 'out') {
    badges.push('<span class="badge badge-out">下发</span>');
  }
  badges.push(`<span class="badge">${KIND_LABEL[message.kind] || message.kind}</span>`);
  if (message.code !== null && message.code !== undefined) {
    badges.push(`<span class="badge badge-code">${message.code}</span>`);
  }
  if (message.pending) {
    badges.push('<span class="badge badge-warn">等待回调</span>');
  } else if (message.success === true) {
    badges.push('<span class="badge badge-ok">成功</span>');
  } else if (message.success === false) {
    badges.push('<span class="badge badge-err">失败</span>');
  }
  if (message.durationMs !== null && message.durationMs !== undefined) {
    badges.push(`<span class="badge">${message.durationMs}ms</span>`);
  }

  head.innerHTML = `<span class="msg-time">${formatTime(message.time)}</span>${badges.join('')}`;
  const title = document.createElement('span');
  title.className = 'msg-title';
  title.textContent = message.title || '';
  head.appendChild(title);
  node.appendChild(head);

  const body = document.createElement('div');
  body.className = 'msg-body';

  if (message.payload !== null && message.payload !== undefined) {
    body.appendChild(buildSection(message.direction === 'in' ? '收到的报文' : '请求报文', message.payload));
  }
  if (message.response !== null && message.response !== undefined) {
    body.appendChild(buildSection('机器狗返回', message.response));
  }
  if (message.matchedPending) {
    body.appendChild(buildSection('匹配到的待回执指令', message.matchedPending));
  }
  if (message.error) {
    const err = document.createElement('div');
    err.className = 'err-text';
    err.textContent = `错误：${message.error}`;
    body.appendChild(err);
  }

  if (body.childNodes.length > 0) {
    node.appendChild(body);
  }
  return node;
}

function buildSection(label, value) {
  const section = document.createElement('div');
  section.className = 'msg-section';
  const caption = document.createElement('div');
  caption.className = 'label';
  caption.textContent = label;
  const pre = document.createElement('pre');
  pre.innerHTML = highlightJson(value);
  section.append(caption, pre);
  return section;
}

function pushMessage(message) {
  state.messages.push(message);
  if (state.messages.length > 800) {
    state.messages.splice(0, state.messages.length - 800);
  }

  // 按钮上的状态跟随回调更新
  if (message.kind === 'callBackMess') {
    const button = document.querySelector(`.cmd-btn[data-code="${message.code}"]`);
    if (button) {
      flash(button, message.success === true ? 'ok' : 'err');
    }
  }
  if (message.kind === 'command' && message.code === 401 && message.success) {
    updateStatus(message.response);
  }

  if (matchFilter(message)) {
    const feed = $('#feed');
    const empty = feed.querySelector('.empty');
    if (empty) {
      empty.remove();
    }
    feed.appendChild(buildMessageNode(message));
    while (feed.childElementCount > MAX_RENDER) {
      feed.removeChild(feed.firstElementChild);
    }
    if (state.autoScroll) {
      feed.scrollTop = feed.scrollHeight;
    }
  }
}

/** SSE 重连 / 多标签页时补齐消息 */
async function resyncMessages() {
  try {
    const res = await api('/api/messages');
    state.messages = res.data || [];
    renderFeed();
  } catch (err) {
    // 忽略：SSE 仍在线，下一批消息会补上
  }
}

// ==================== 待回执（异步指令） ====================

async function refreshPendings() {
  try {
    const res = await api('/api/pendings');
    state.pendings = res.data || [];
  } catch (err) {
    state.pendings = [];
  }
  renderPendings();
}

function renderPendings() {
  const bar = $('#pending-bar');
  if (state.pendings.length === 0) {
    bar.hidden = true;
    bar.innerHTML = '';
    return;
  }
  bar.hidden = false;
  bar.innerHTML = '<b>等待回调：</b>' + state.pendings
    .map((item) => `<span>#${item.id} ${item.code} ${escapeHtml(item.name || '')}（${formatTime(item.time)} 下发）</span>`)
    .join('');
}

// ==================== 配置 ====================

function renderSettings() {
  const settings = state.settings || {};
  $('#set-base-url').value = settings.robotBaseUrl || '';
  $('#set-sn').value = settings.robotSn || '';
  $('#set-timeout').value = settings.timeoutMs || 30000;
  $('#set-retries').value = settings.maxRetries || 0;

  const chips = $('#endpoint-chips');
  chips.innerHTML = '';
  for (const item of (settings.callbackUrls || []).slice(0, 2)) {
    const chip = document.createElement('span');
    chip.className = 'chip';
    chip.title = `点击复制\n${item.callBackMess}\n${item.uploadFile}`;
    chip.innerHTML = `回调地址 <b>${escapeHtml(item.callBackMess)}</b>`;
    chip.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(item.callBackMess);
        notify('已复制回调地址', 'ok');
      } catch (err) {
        notify(item.callBackMess);
      }
    });
    chips.appendChild(chip);
  }
  const robotChip = document.createElement('span');
  robotChip.className = 'chip';
  robotChip.innerHTML = `机器狗 <b>${escapeHtml(settings.robotBaseUrl || '')}</b>`;
  chips.appendChild(robotChip);
}

async function saveSettings() {
  const hint = $('#settings-hint');
  try {
    const res = await api('/api/settings', {
      method: 'PUT',
      body: JSON.stringify({
        robotBaseUrl: $('#set-base-url').value,
        robotSn: $('#set-sn').value,
        timeoutMs: Number($('#set-timeout').value),
        maxRetries: Number($('#set-retries').value)
      })
    });
    state.settings = Object.assign({}, state.settings, res.data);
    renderSettings();
    hint.className = 'hint is-ok';
    hint.textContent = res.changed.length > 0 ? `已保存：${res.changed.join('、')}` : '没有变化';
    notify('配置已保存', 'ok');
  } catch (err) {
    hint.className = 'hint is-err';
    hint.textContent = `保存失败：${err.message}`;
    notify(`保存失败：${err.message}`, 'error');
  }
}

// ==================== 自定义指令 ====================

async function sendRaw() {
  const code = Number($('#raw-code').value);
  if (!Number.isFinite(code)) {
    notify('请填写 code', 'error');
    return;
  }
  let reqJson = $('#raw-reqjson').value.trim();
  if (reqJson) {
    try {
      reqJson = JSON.parse(reqJson);
    } catch (err) {
      // 不是合法 JSON 就当原始字符串下发
    }
  } else {
    reqJson = {};
  }
  try {
    const res = await api('/api/robot/command', {
      method: 'POST',
      body: JSON.stringify({ code, reqJson, name: `自定义 ${code}` })
    });
    notify(res.data && res.data.accepted ? `指令 ${code} 已下发` : `指令 ${code} 下发失败`, res.data && res.data.accepted ? 'ok' : 'error');
  } catch (err) {
    notify(`下发失败：${err.message}`, 'error');
  }
}

// ==================== SSE ====================

function setConnection(status) {
  state.connection = status;
  const box = $('#conn-state');
  box.classList.toggle('is-on', status === 'online');
  box.classList.toggle('is-off', status === 'offline');
  box.querySelector('.conn-text').textContent =
    status === 'online' ? '实时连接正常' : status === 'offline' ? '连接已断开，重连中…' : '连接中…';
}

function connectEvents() {
  const source = new EventSource('/api/events');
  source.addEventListener('ready', () => {
    const reconnected = state.connection !== 'online' && state.connection !== 'connecting';
    setConnection('online');
    if (reconnected) {
      resyncMessages();
    }
  });
  source.addEventListener('message', (event) => {
    setConnection('online');
    try {
      const message = JSON.parse(event.data);
      pushMessage(message);
      if (message.kind === 'callBackMess') {
        refreshPendings();
      }
    } catch (err) {
      console.error('解析 SSE 消息失败', err);
    }
  });
  source.addEventListener('ping', () => setConnection('online'));
  source.onerror = () => setConnection('offline');
}

// ==================== 初始化 ====================

async function bootstrap() {
  const res = await api('/api/bootstrap');
  const data = res.data || {};
  state.settings = data.settings || {};
  state.commands = data.commands || [];
  state.messages = data.messages || [];
  state.pendings = data.pendings || [];

  renderSettings();
  renderCommands();
  renderFeed();
  renderPendings();

  // 页面刷新后恢复最近一次 401 状态
  const lastStatusMessage = [...state.messages].reverse().find(
    (item) => item.kind === 'command' && item.code === 401 && item.success && item.response
  );
  if (lastStatusMessage) {
    updateStatus(lastStatusMessage.response);
  }
}

function bindEvents() {
  $('#toggle-settings').addEventListener('click', () => {
    const box = $('#settings-box');
    box.hidden = !box.hidden;
  });
  $('#save-settings').addEventListener('click', saveSettings);
  $('#raw-send').addEventListener('click', sendRaw);

  $('#clear-messages').addEventListener('click', async () => {
    try {
      await api('/api/messages/clear', { method: 'POST', body: '{}' });
      state.messages = [];
      state.pendings = [];
      renderFeed();
      renderPendings();
    } catch (err) {
      notify(`清空失败：${err.message}`, 'error');
    }
  });

  $('#auto-scroll').addEventListener('change', (event) => {
    state.autoScroll = event.target.checked;
    if (state.autoScroll) {
      const feed = $('#feed');
      feed.scrollTop = feed.scrollHeight;
    }
  });

  $('#filters').addEventListener('click', (event) => {
    const button = event.target.closest('.btn-filter');
    if (!button) {
      return;
    }
    state.filter = button.dataset.filter;
    document.querySelectorAll('.btn-filter').forEach((item) => item.classList.toggle('is-active', item === button));
    renderFeed();
  });
}

(async function main() {
  bindEvents();
  try {
    await bootstrap();
  } catch (err) {
    notify(`初始化失败：${err.message}`, 'error');
  }
  connectEvents();
  setInterval(refreshPendings, 15000);
})();
