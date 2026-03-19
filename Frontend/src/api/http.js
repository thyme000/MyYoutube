const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";
const TOKEN_KEY = "myyoutube_token";

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

export async function httpRequest(path, { method = "GET", query, body, headers } = {}) {
  const finalUrl = buildUrl(path, query);
  const token = getToken();
  const response = await fetch(finalUrl, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: token } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(errorText || `请求失败：${response.status}`);
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const payload = await response.json();
    // 兼容后端统一返回格式：{ code, message, data }
    if (payload && typeof payload === "object" && "code" in payload && "message" in payload) {
      if (payload.code !== 200) {
        throw new Error(payload.message || "请求失败");
      }
      return payload.data;
    }
    return payload;
  }
  return response.text();
}

export function getApiBaseUrl() {
  return API_BASE_URL || "(同源 /api)";
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}
