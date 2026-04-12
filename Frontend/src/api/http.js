/**
 * Nginx 反代时 API 应与页面同源（如 /captcha、/user/info），否则会跨域直连 :8080，OPTIONS 常 403。
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
const AUTH_EXPIRED_EVENT = "myyoutube:auth-expired";

/**
 * 与后端约定：
 * - HTTP：请求头 Authorization 值为登录返回的 token 原样（Redis key 为完整 header 值），不要加 Bearer 前缀。
 * - WebSocket /ws/notify：查询串须以 token= 开头，见 buildNotifyWebSocketUrl。
 */
export function normalizeAuthToken(raw) {
  if (raw == null) return "";
  let s = String(raw).trim();
  if (/^bearer\s+/i.test(s)) {
    s = s.replace(/^bearer\s+/i, "").trim();
  }
  return s;
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
export async function uploadMultipart(path, formData, { query, headers, method = "POST" } = {}) {
  const finalUrl = buildUrl(path, query);
  const token = getToken();
  const m = String(method || "POST").toUpperCase();
  const httpMethod = m === "PUT" || m === "PATCH" ? m : "POST";
  const response = await fetch(finalUrl, {
    method: httpMethod,
    headers: {
      ...headers,
      ...(token ? { Authorization: token } : {}),
    },
    body: formData,
  });

  if (!response.ok) {
    if (isLikelyAuthExpiredStatus(response.status)) {
      emitAuthExpired();
    }
    const errorText = await response.text();
    throw new Error(errorText || `请求失败：${response.status}`);
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

export async function httpRequest(path, { method = "GET", query, body, headers } = {}) {
  const finalUrl = buildUrl(path, query);
  const token = getToken();
  const response = await fetch(finalUrl, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...headers,
      ...(token ? { Authorization: token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    if (isLikelyAuthExpiredStatus(response.status)) {
      emitAuthExpired();
    }
    const errorText = await response.text();
    throw new Error(errorText || `请求失败：${response.status}`);
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

export function getApiBaseUrl() {
  return API_BASE_URL || "(同源，由 Nginx 反代到后端)";
}

export function getToken() {
  return normalizeAuthToken(localStorage.getItem(TOKEN_KEY) || "");
}

export function setToken(token) {
  const t = normalizeAuthToken(token);
  if (!t) {
    localStorage.removeItem(TOKEN_KEY);
    return;
  }
  localStorage.setItem(TOKEN_KEY, t);
}

/**
 * 通知 WebSocket；后端要求 query 以 token= 开头（且宜为唯一参数）。
 * 生产环境 Nginx 需将 `/ws/` 反代到后端并配置 `Upgrade` / `Connection`（与 Vite 里 `/ws` 代理一致）。
 */
export function buildNotifyWebSocketUrl() {
  const t = getToken();
  if (!t) return "";
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  return `${proto}//${host}/ws/notify?token=${encodeURIComponent(t)}`;
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export function getAuthExpiredEventName() {
  return AUTH_EXPIRED_EVENT;
}
