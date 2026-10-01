// @ts-check
/**
 * 聊天页专用 WebSocket（纯下行, 未来承载"正在输入"等上行状态流）。
 * 连接生命周期挂在聊天页: 进页 connect、离开 disconnect; 页内断线自动退避重连。
 * 通知类推送不走这里（全局 SSE 门铃, 见 utils/notifySse.js）。
 */
import { buildChatWebSocketUrl, getValidAccessToken, getToken } from "../api/http";

let socket = null;
/** 离开聊天页 / 登出时为 true */
let intentionalClose = false;
let reconnectTimer = null;
let activeHandlers = null;
let reconnectAttempt = 0;
/** 每次 disconnect 或发起新连接时递增，用于忽略旧 socket 的 close（避免误 scheduleReconnect） */
let wsGeneration = 0;

function clearReconnectTimer() {
  if (reconnectTimer != null) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
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
  if (isChatWebSocketOpen()) return;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    if (intentionalClose || !getToken() || isChatWebSocketOpen()) return;
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

  // access 过期则先续签再连, 保证重连时握手 token 可用
  void getValidAccessToken().then((token) => {
    if (intentionalClose || socket != null || !token) return;
    openSocket(buildChatWebSocketUrl(token), handlers);
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
  const { onMessage } = handlers;

  ws.addEventListener("open", () => {
    if (gen !== wsGeneration || socket !== ws) return;
    reconnectAttempt = 0;
    clearReconnectTimer();
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

  ws.addEventListener("close", () => {
    if (gen !== wsGeneration) return;
    if (socket === ws) socket = null;
    if (!intentionalClose && getToken()) {
      reconnectAttempt += 1;
      scheduleReconnect();
    }
  });

  return ws;
}

export function isChatWebSocketOpen() {
  return socket != null && socket.readyState === WebSocket.OPEN;
}

/** 进聊天页时调用; 断线自动重连 */
export function connectChatWebSocket(handlers = {}) {
  disconnectChatWebSocket();
  intentionalClose = false;
  activeHandlers = handlers;
  reconnectAttempt = 0;
  connectOnce(handlers);
}

/** 离开聊天页 / 登出时调用 */
export function disconnectChatWebSocket() {
  intentionalClose = true;
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
