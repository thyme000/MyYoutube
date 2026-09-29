// @ts-check
/**
 * 本地网易云解析服务（默认 localhost:5000），经 Vite / Nginx 前缀 /music-svc 同源代理。
 * 响应约定：{ status, success, message, data }
 */

const PREFIX = "/music-svc";

function shortHttpHint(status, text) {
  const t = text || "";
  const isHtml =
    /<!DOCTYPE|<\s*html[\s>]|<\s*head[\s>]|<title>\s*405/i.test(t) || (t.includes("<center>") && t.includes("nginx"));
  if (status === 405 || (isHtml && status >= 400)) {
    return `HTTP ${status}：请求未到达本地解析服务（常见于 Nginx 未反代 ${PREFIX} 或需执行 nginx -s reload）。请确认 conf 中存在「location ^~ ${PREFIX}/」并 proxy_pass 到 127.0.0.1:5000。`;
  }
  if (isHtml) {
    return `服务返回了 HTML 而非 JSON（HTTP ${status}），请检查反代与解析服务是否已启动。`;
  }
  return null;
}

async function parseJsonResponse(res) {
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    const hint = shortHttpHint(res.status, text);
    throw new Error(hint || "响应不是 JSON");
  }
  if (!res.ok) {
    const hint = shortHttpHint(res.status, text);
    throw new Error(hint || json.message || `HTTP ${res.status}`);
  }
  if (json.success === false) {
    throw new Error(json.message || "请求失败");
  }
  if (json.status != null && Number(json.status) !== 200) {
    throw new Error(json.message || "服务返回错误");
  }
  return json.data;
}

/** @returns {Promise<any>} */
export async function musicHealth() {
  const res = await fetch(`${PREFIX}/health`, { method: "GET" });
  return parseJsonResponse(res);
}

/** @param {string} keyword @param {number} [limit] @returns {Promise<any>} */
export async function musicSearch(keyword, limit = 30) {
  const res = await fetch(`${PREFIX}/search`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ keyword: String(keyword ?? "").trim(), limit }),
  });
  return parseJsonResponse(res);
}

/**
 * @param {{ url: string, level?: string, type?: string }} payload - url 为歌曲 ID 或链接
 */
export async function musicResolveSong(payload) {
  const res = await fetch(`${PREFIX}/song`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      url: payload.url,
      level: payload.level ?? "lossless",
      type: payload.type ?? "json",
    }),
  });
  return parseJsonResponse(res);
}

/** @param {string} id @returns {Promise<any>} */
export async function musicPlaylist(id) {
  const res = await fetch(`${PREFIX}/playlist`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: String(id).trim() }),
  });
  return parseJsonResponse(res);
}

/** @param {string} id @returns {Promise<any>} */
export async function musicAlbum(id) {
  const res = await fetch(`${PREFIX}/album`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: String(id).trim() }),
  });
  return parseJsonResponse(res);
}
