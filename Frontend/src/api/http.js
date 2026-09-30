// @ts-check
/**
 * Nginx 反代时 API 应与页面同源（如 /login、/user/info），否则会跨域直连 :8080，OPTIONS 常 403。
 * - 生产环境：默认强制相对路径，忽略误打包进 dist 的 VITE_API_BASE_URL（除非 VITE_API_ALLOW_CROSS_ORIGIN=true）。
 * - 开发环境：仍读环境变量；空则走 Vite proxy。
 */
function resolveApiBaseUrl() {
  const allowCrossOrigin =
    String(import.meta.env.VITE_API_ALLOW_CROSS_ORIGIN || "").toLowerCase() === "true";
  const raw = (import.meta.env.VITE_API_BASE_URL || "").trim();

  if (import.meta.env.PROD && !allowCrossOrigin) {
    if (raw) {
      console.warn(
        "[myyoutube] 生产环境已强制使用同源 API 路径（忽略 VITE_API_BASE_URL）。需跨域直连请设 VITE_API_ALLOW_CROSS_ORIGIN=true。"
      );
    }
    return "";
  }

  if (!raw) return "";
  if (allowCrossOrigin) return raw;

  try {
    const apiOrigin = new URL(raw).origin;
    if (apiOrigin !== window.location.origin) {
      console.warn(
        "[myyoutube] VITE_API_BASE_URL 与当前页面不同源，已改为同源请求（请走 Nginx 反代）。"
      );
      return "";
    }
  } catch {
    return raw;
  }
  return raw;
}

const API_BASE_URL = resolveApiBaseUrl();
const TOKEN_KEY = "myyoutube_token";
const REFRESH_TOKEN_KEY = "myyoutube_refresh_token";
const AUTH_EXPIRED_EVENT = "myyoutube:auth-expired";

/**
 * 与后端约定：
 * - HTTP：请求头 Authorization: Bearer <accessToken>（后端签发的 JWT）。
 * - WebSocket /ws/notify：查询串 token= 直接传裸 accessToken，不加 Bearer，见 buildNotifyWebSocketUrl。
 */
/** @param {any} raw @returns {string} */
export function normalizeAuthToken(raw) {
  if (raw == null) return "";
  let s = String(raw).trim();
  if (/^bearer\s+/i.test(s)) {
    s = s.replace(/^bearer\s+/i, "").trim();
  }
  return s;
}

/** @returns {string} */
export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY) || "";
}

/** @param {any} token */
export function setRefreshToken(token) {
  const t = String(token || "").trim();
  if (!t) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    return;
  }
  localStorage.setItem(REFRESH_TOKEN_KEY, t);
}

/** access JWT 是否已过期(留 30s 余量); 非 JWT 形态视为未过期 */
/** @param {string} token @returns {boolean} */
function isAccessTokenExpired(token) {
  try {
    const [, payloadB64] = token.split(".");
    const payload = JSON.parse(atob(payloadB64.replace(/-/g, "+").replace(/_/g, "/")));
    return Number(payload.exp) * 1000 < Date.now() + 30_000;
  } catch {
    return false;
  }
}

/** @type {Promise<boolean>|null} 全局单飞: 并发 401 共享同一次 /refresh 调用 */
let refreshingPromise = null;

/**
 * 401 后用 refresh token 续签(每次续签后端会轮换 refresh token)。
 * 成功返回 true 并已更新本地双 token; 失败清空登录态并触发 auth-expired。
 * @returns {Promise<boolean>}
 */
export function refreshAccessToken() {
  if (!refreshingPromise) {
    refreshingPromise = doRefreshAccessToken().finally(() => {
      refreshingPromise = null;
    });
  }
  return refreshingPromise;
}

/** @returns {Promise<boolean>} */
async function doRefreshAccessToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  try {
    const response = await fetch(buildUrl("/refresh"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    const payload = await response.json();
    const data = payload?.data;
    if (response.ok && payload?.code === 200 && data?.accessToken && data?.refreshToken) {
      setToken(data.accessToken);
      setRefreshToken(data.refreshToken);
      return true;
    }
  } catch {
    // 网络异常按续签失败处理
  }
  emitAuthExpired();
  return false;
}

/** WebSocket 等场景: access 过期则先续签, 返回可用 token(可能为空) */
/** @returns {Promise<string>} */
export async function getValidAccessToken() {
  let token = getToken();
  if (token && isAccessTokenExpired(token)) {
    const ok = await refreshAccessToken();
    token = getToken();
    if (!ok || !token) return "";
  }
  return token;
}

/** 登出时尽力通知后端作废 refresh token, 失败不阻塞 */
export function revokeRefreshToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return;
  if (typeof window === "undefined" || typeof fetch !== "function") return;
  try {
    fetch(buildUrl("/logout"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    }).catch(() => {});
  } catch {
    // ignore
  }
}

function emitAuthExpired(reason = "登录状态已过期，请重新登录") {
  clearToken();
  try {
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, { detail: { reason } }));
  } catch {
    // ignore
  }
}

function isLikelyAuthExpiredStatus(status) {
  return status === 401 || status === 403;
}

function isLikelyAuthExpiredPayload(payload) {
  const code = Number(payload?.code);
  const msg = String(payload?.message || "");
  if (code === 401 || code === 403) return true;
  if (msg.includes("登录") && (msg.includes("过期") || msg.includes("失效") || msg.includes("未登录"))) {
    return true;
  }
  return false;
}

/**
 * 非 2xx 响应：后端异常时返回 JSON 统一格式（{ code, message }），优先取 message；否则回退原始文本。
 */
/** @param {Response} response @returns {Promise<never>} */
async function throwRequestError(response) {
  if (isLikelyAuthExpiredStatus(response.status)) {
    emitAuthExpired();
  }
  const errorText = await response.text();
  let payload = null;
  try {
    payload = JSON.parse(errorText);
  } catch {
    // 非 JSON 响应体
  }
  if (payload && typeof payload === "object" && payload.message) {
    if (isLikelyAuthExpiredPayload(payload)) {
      emitAuthExpired(payload.message);
    }
    throw new Error(payload.message);
  }
  throw new Error(errorText || `请求失败：${response.status}`);
}

/** @param {string} path @param {Record<string, any>} [query] @returns {string} */
function buildUrl(path, query) {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin);
  if (query && typeof query === "object") {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }
  return API_BASE_URL ? url.toString() : `${url.pathname}${url.search}`;
}

/**
 * multipart/form-data 上传（如头像、背景图），不要手动设置 Content-Type，以便浏览器自动带 boundary。
 */
/** @typedef {{ code: number, message: string, data: any }} ApiEnvelope 后端统一响应 */
/** @typedef {{ query?: Record<string, any>, headers?: Record<string, string>, method?: string }} MultipartOpts */

/**
 * @param {string} path
 * @param {FormData} formData
 * @param {MultipartOpts} [opts]
 * @returns {Promise<any>}
 */
export async function uploadMultipart(path, formData, { query, headers, method = "POST" } = {}, retried = false) {
  const finalUrl = buildUrl(path, query);
  const token = getToken();
  const m = String(method || "POST").toUpperCase();
  const httpMethod = m === "PUT" || m === "PATCH" ? m : "POST";
  const response = await fetch(finalUrl, {
    method: httpMethod,
    headers: {
      ...headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: formData,
  });

  if (response.status === 401 && !retried && path !== "/refresh") {
    if (await refreshAccessToken()) {
      return uploadMultipart(path, formData, { query, headers, method }, true);
    }
  }

  if (!response.ok) {
    await throwRequestError(response);
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await response.json();
    if (payload && typeof payload === "object" && "code" in payload && "message" in payload) {
      if (payload.code !== 200) {
        if (isLikelyAuthExpiredPayload(payload)) {
          emitAuthExpired(payload.message);
        }
        throw new Error(payload.message || "请求失败");
      }
      return payload.data;
    }
    return payload;
  }
  return response.text();
}

/** @typedef {{ method?: string, query?: Record<string, any>, body?: any, headers?: Record<string, string> }} HttpOpts */

/**
 * @param {string} path
 * @param {HttpOpts} [opts]
 * @returns {Promise<any>} 拆包后的 data（非 JSON 响应为文本）
 */
export async function httpRequest(path, { method = "GET", query, body, headers } = {}, retried = false) {
  const finalUrl = buildUrl(path, query);
  const token = getToken();
  const response = await fetch(finalUrl, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  // access 过期: 单飞续签后重放一次; 失败则 refreshAccessToken 内部已触发登出
  if (response.status === 401 && !retried && path !== "/refresh") {
    if (await refreshAccessToken()) {
      return httpRequest(path, { method, query, body, headers }, true);
    }
  }

  if (!response.ok) {
    await throwRequestError(response);
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await response.json();
    // 兼容后端统一返回格式：{ code, message, data }
    if (payload && typeof payload === "object" && "code" in payload && "message" in payload) {
      if (payload.code !== 200) {
        if (isLikelyAuthExpiredPayload(payload)) {
          emitAuthExpired(payload.message);
        }
        throw new Error(payload.message || "请求失败");
      }
      return payload.data;
    }
    return payload;
  }
  return response.text();
}

/** @returns {string} */
export function getApiBaseUrl() {
  return API_BASE_URL || "(同源，由 Nginx 反代到后端)";
}

/** @returns {string} */
export function getToken() {
  return normalizeAuthToken(localStorage.getItem(TOKEN_KEY) || "");
}

/** @param {any} token */
export function setToken(token) {
  const t = normalizeAuthToken(token);
  if (!t) {
    localStorage.removeItem(TOKEN_KEY);
    return;
  }
  localStorage.setItem(TOKEN_KEY, t);
}

/**
 * 通知 WebSocket；query 传裸 accessToken（无 Bearer 前缀）。
 * 生产环境 Nginx 需将 `/ws/` 反代到后端并配置 `Upgrade` / `Connection`（与 Vite 里 `/ws` 代理一致）。
 */
/** @param {string} [token] @returns {string} */
export function buildNotifyWebSocketUrl(token = getToken()) {
  if (!token) return "";
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  return `${proto}//${host}/ws/notify?token=${encodeURIComponent(token)}`;
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

/** @returns {string} */
export function getAuthExpiredEventName() {
  return AUTH_EXPIRED_EVENT;
}
