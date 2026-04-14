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
}

export function getApiBaseUrl() {
  return API_BASE_URL || "(同源，由 Nginx/代理转发到后端)";
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

