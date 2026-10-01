// @ts-check
/**
 * SSE 门铃通知（登录后全局常驻，切后台断开、回前台重连）。
 * 服务端每 25s 注入注释行心跳；浏览器 EventSource 自带断线重连，
 * 这里补两类它做不了的事：401/永久关闭后的退避重连、以及后台断开省连接。
 */
import { buildNotifySseUrl, getValidAccessToken } from "../api/http";

let eventSource = null;
/** 用户主动 stop / 登出 / 切后台时为 true */
let paused = false;
let reconnectTimer = null;
let reconnectAttempt = 0;
let activeHandlers = null;
let lastReceivedAtMs = 0;
/** 心跳看护：服务端 25s 一跳，超过 75s 没有任何报文就视为半开连接，强制重建 */
let watchdogTimer = null;

function clearReconnectTimer() {
  if (reconnectTimer != null) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function reconnectDelayMs() {
  const base = 1500;
  const max = 30000;
  const exp = Math.min(max, base * Math.pow(2, reconnectAttempt));
  return exp + Math.floor(Math.random() * 400);
}

function scheduleReconnect() {
  clearReconnectTimer();
  if (paused || !activeHandlers) return;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    if (paused || !activeHandlers) return;
    void connectOnce(activeHandlers);
  }, reconnectDelayMs());
}

function stopWatchdog() {
  if (watchdogTimer != null) {
    window.clearInterval(watchdogTimer);
    watchdogTimer = null;
  }
}

function startWatchdog() {
  stopWatchdog();
  watchdogTimer = window.setInterval(() => {
    if (paused || !activeHandlers) return;
    if (eventSource && eventSource.readyState === EventSource.OPEN && lastReceivedAtMs > 0) {
      if (Date.now() - lastReceivedAtMs > 75000) {
        closeSource();
        reconnectAttempt = Math.min(reconnectAttempt, 6);
        scheduleReconnect();
      }
    }
  }, 30000);
}

function closeSource() {
  if (!eventSource) return;
  const es = eventSource;
  eventSource = null;
  try {
    es.close();
  } catch {
    /* ignore */
  }
}

function connectOnce(handlers) {
  if (!handlers || paused) return;
  if (eventSource && eventSource.readyState !== EventSource.CLOSED) return;

  // access 过期则先续签再连, 保证重连/切回前台时握手 token 可用
  void getValidAccessToken().then((token) => {
    if (paused || !activeHandlers || (eventSource && eventSource.readyState !== EventSource.CLOSED)) return;
    const url = buildNotifySseUrl(token);
    if (!url) return;
    let es;
    try {
      es = new EventSource(url);
    } catch {
      scheduleReconnect();
      return;
    }
    eventSource = es;

    es.addEventListener("open", () => {
      if (eventSource !== es) return;
      reconnectAttempt = 0;
      clearReconnectTimer();
      lastReceivedAtMs = Date.now();
    });

    es.addEventListener("message", (ev) => {
      if (eventSource !== es) return;
      lastReceivedAtMs = Date.now();
      let parsed;
      try {
        parsed = JSON.parse(typeof ev.data === "string" ? ev.data : String(ev.data));
      } catch {
        return;
      }
      handlers.onSignal?.(parsed?.type ?? "", parsed);
    });

    // 服务端正常重连时 readyState 会回到 CONNECTING 并自动恢复;
    // 只有握手被拒(401 等)才会进入 CLOSED, 那时才需要自己退避重试
    es.addEventListener("error", () => {
      if (eventSource !== es) return;
      if (es.readyState === EventSource.CLOSED) {
        eventSource = null;
        reconnectAttempt += 1;
        scheduleReconnect();
      }
    });
  });
}

function hookLifecycleOnce() {
  if (hookLifecycleOnce.done) return;
  hookLifecycleOnce.done = true;
  // 切后台断开省连接, 回前台立即重连(EventSource 无法挂起只能关掉)
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      if (eventSource) {
        closeSource();
      }
    } else if (!paused && activeHandlers) {
      reconnectAttempt = 0;
      clearReconnectTimer();
      void connectOnce(activeHandlers);
    }
  });
  window.addEventListener("online", () => {
    if (paused || !activeHandlers) return;
    reconnectAttempt = 0;
    clearReconnectTimer();
    void connectOnce(activeHandlers);
  });
}

export function isNotifySseOpen() {
  return eventSource != null && eventSource.readyState === EventSource.OPEN;
}

/**
 * 登录后启动门铃: onSignal(type) 收到轻量信号(NOTIFY_DIRTY / CHAT_DIRTY / CONNECTED)。
 */
export function startNotifySse(handlers = {}) {
  stopNotifySse();
  paused = false;
  activeHandlers = handlers;
  reconnectAttempt = 0;
  hookLifecycleOnce();
  void connectOnce(handlers);
  startWatchdog();
}

export function stopNotifySse() {
  paused = true;
  stopWatchdog();
  clearReconnectTimer();
  activeHandlers = null;
  reconnectAttempt = 0;
  closeSource();
}
