function resolveApiBaseUrl() {
  const allowCrossOrigin =
    String(import.meta.env.VITE_API_ALLOW_CROSS_ORIGIN || "").toLowerCase() === "true";
  const raw = (import.meta.env.VITE_API_BASE_URL || "").trim();

  if (import.meta.env.PROD && !allowCrossOrigin) return "";
  if (!raw) return "";
  if (allowCrossOrigin) return raw;
  try {
    const apiOrigin = new URL(raw).origin;
    if (apiOrigin !== window.location.origin) return "";
  } catch {
    return raw;
  }
  return raw;
}

const API_BASE_URL = resolveApiBaseUrl();
const TOKEN_KEY = "myyoutube_admin_token";
const REFRESH_TOKEN_KEY = "myyoutube_admin_refresh_token";
const AUTH_EXPIRED_EVENT = "myyoutube:admin-auth-expired";

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

export function normalizeAuthToken(raw) {
  if (raw == null) return "";
  let s = String(raw).trim();
  if (/^bearer\s+/i.test(s)) {
    s = s.replace(/^bearer\s+/i, "").trim();
  }
  return s;
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY) || "";
}

export function setRefreshToken(token) {
  const t = String(token || "").trim();
  if (!t) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    return;
  }
  localStorage.setItem(REFRESH_TOKEN_KEY, t);
}

/** access JWT 是否已过期(留 30s 余量); 非 JWT 形态视为未过期 */
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
 * 401 后用 refresh token 续签(每次续签后端轮换 refresh token)。
 * 成功返回 true 并已更新本地双 token; 失败清空登录态并触发 auth-expired。
 */
export function refreshAccessToken() {
  if (!refreshingPromise) {
    refreshingPromise = doRefreshAccessToken().finally(() => {
      refreshingPromise = null;
    });
  }
  return refreshingPromise;
}

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
  clearToken();
  try {
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
  } catch {
    // ignore
  }
  return false;
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

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function getAuthExpiredEventName() {
  return AUTH_EXPIRED_EVENT;
}

export function getApiBaseUrl() {
  return API_BASE_URL || "(同源，由 Nginx/代理转发到后端)";
}

export async function httpRequest(path, { method = "GET", query, body, headers } = {}, retried = false) {
  const finalUrl = buildUrl(path, query);
  let token = getToken();
  if (token && isAccessTokenExpired(token) && path !== "/refresh") {
    await refreshAccessToken();
    token = getToken();
  }
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
    const errorText = await response.text();
    const err = new Error(errorText || `请求失败：${response.status}`);
    err.status = response.status;
    throw err;
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await response.json();
    if (payload && typeof payload === "object" && "code" in payload && "message" in payload) {
      if (payload.code !== 200) {
        const err = new Error(payload.message || "请求失败");
        err.code = payload.code;
        throw err;
      }
      return payload.data;
    }
    return payload;
  }
  return response.text();
}
