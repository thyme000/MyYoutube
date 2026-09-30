// @ts-check
import { buildNotifyWebSocketUrl, getValidAccessToken, getToken } from "../api/http";

let socket = null;
/** 用户主动 stop / 登出时为 true */
let intentionalClose = false;
let reconnectTimer = null;
let activeHandlers = null;
let reconnectAttempt = 0;
/** 每次 stop 或发起新连接时递增，用于忽略旧 socket 的 close（避免误 scheduleReconnect） */
let wsGeneration = 0;
let healthTimer = null;
let lifecycleHooked = false;

function clearReconnectTimer() {
  if (reconnectTimer != null) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function stopHealthTimer() {
  if (healthTimer != null) {
    window.clearInterval(healthTimer);
    healthTimer = null;
  }
}

function reconnectDelayMs() {
  const base = 1200;
  const max = 30000;
  const exp = Math.min(max, base * Math.pow(2, reconnectAttempt));
  const jitter = Math.floor(Math.random() * 400);
  return exp + jitter;
}

function scheduleReconnect() {
  clearReconnectTimer();
  if (intentionalClose) return;
  if (!getToken()) return;
  if (isNotifyWebSocketOpen()) return;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    if (intentionalClose || !getToken() || isNotifyWebSocketOpen()) return;
    if (!activeHandlers) return;
    connectOnce(activeHandlers);
  }, reconnectDelayMs());
}

function normalizeMessageData(data) {
  if (typeof data === "string") return Promise.resolve(data);
  if (data instanceof Blob) return data.text();
  if (data instanceof ArrayBuffer) {
    return Promise.resolve(new TextDecoder("utf-8", { fatal: false }).decode(data));
  }
  if (ArrayBuffer.isView(data)) {
    const u8 = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    return Promise.resolve(new TextDecoder("utf-8", { fatal: false }).decode(u8));
  }
  return Promise.resolve(String(data));
}

function connectOnce(handlers) {
  if (!handlers || intentionalClose) return null;

  if (socket != null) {
    const rs = socket.readyState;
    if (rs === WebSocket.OPEN || rs === WebSocket.CONNECTING) {
      return socket;
    }
    try {
      socket.close();
    } catch {
      /* ignore */
    }
    socket = null;
  }

  // access 过期则先续签再连, 保证重连/切回前台时握手 token 可用
  void getValidAccessToken().then((token) => {
    if (intentionalClose || socket != null || !token) return;
    openSocket(buildNotifyWebSocketUrl(token), handlers);
  });
  return null;
}

function openSocket(url, handlers) {
  if (!url) return null;

  wsGeneration += 1;
  const gen = wsGeneration;

  let ws;
  try {
    ws = new WebSocket(url);
  } catch {
    reconnectAttempt += 1;
    scheduleReconnect();
    return null;
  }
  socket = ws;
  const { onOpen, onMessage, onError, onClose } = handlers;

  ws.addEventListener("open", () => {
    if (gen !== wsGeneration || socket !== ws) return;
    reconnectAttempt = 0;
    clearReconnectTimer();
    onOpen?.();
  });

  ws.addEventListener("message", (ev) => {
    if (gen !== wsGeneration || socket !== ws) return;
    void normalizeMessageData(ev.data).then(
      (text) => {
        if (gen !== wsGeneration || socket !== ws) return;
        onMessage?.(text);
      },
      () => {}
    );
  });

  ws.addEventListener("error", () => {
    if (gen !== wsGeneration || socket !== ws) return;
    onError?.();
  });

  ws.addEventListener("close", () => {
    if (gen !== wsGeneration) return;
    if (socket === ws) socket = null;
    onClose?.();
    if (!intentionalClose && getToken()) {
      reconnectAttempt += 1;
      scheduleReconnect();
    }
  });

  return ws;
}

export function isNotifyWebSocketOpen() {
  return socket != null && socket.readyState === WebSocket.OPEN;
}

function startHealthTimer() {
  stopHealthTimer();
  healthTimer = window.setInterval(() => {
    if (intentionalClose || !getToken() || !activeHandlers) return;
    if (isNotifyWebSocketOpen()) return;
    reconnectAttempt = Math.min(reconnectAttempt, 6);
    clearReconnectTimer();
    connectOnce(activeHandlers);
  }, 18000);
}

function hookReconnectLifecycleOnce() {
  if (lifecycleHooked || typeof window === "undefined") return;
  lifecycleHooked = true;
  const bump = () => {
    if (intentionalClose || !getToken()) return;
    if (!activeHandlers) return;
    if (isNotifyWebSocketOpen()) return;
    reconnectAttempt = 0;
    clearReconnectTimer();
    connectOnce(activeHandlers);
  };
  window.addEventListener("online", bump);
  window.addEventListener("focus", bump);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") bump();
  });
}

/**
 * 登录后若发现未连接，可主动拉一把（例如从后台切回前台已由 hook 处理）。
 */
export function reconnectNotifyWebSocketIfNeeded() {
  if (intentionalClose || !getToken() || !activeHandlers) return;
  if (isNotifyWebSocketOpen()) return;
  clearReconnectTimer();
  connectOnce(activeHandlers);
}

/**
 * 使用与 HTTP 相同的登录 token，通过 URL 查询参数 token= 连接。
 * 断线自动重连 + 周期性检查（后端重启、代理超时等）。
 */
export function startNotifyWebSocket(handlers = {}) {
  stopNotifyWebSocket();
  intentionalClose = false;
  activeHandlers = handlers;
  reconnectAttempt = 0;
  hookReconnectLifecycleOnce();
  const w = connectOnce(handlers);
  startHealthTimer();
  return w;
}

export function stopNotifyWebSocket() {
  intentionalClose = true;
  stopHealthTimer();
  clearReconnectTimer();
  activeHandlers = null;
  reconnectAttempt = 0;
  wsGeneration += 1;
  if (!socket) return;
  const ws = socket;
  socket = null;
  try {
    ws.close();
  } catch {
    /* ignore */
  }
}
