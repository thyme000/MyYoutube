import { httpRequest } from "./http";

/**
 * 获取 OSS STS 临时凭证
 * GET /oss/sts
 */
function tryParseJson(raw) {
  if (typeof raw !== "string") return raw;
  const text = raw.trim();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function normalizeWrappedPayload(raw) {
  const parsed = tryParseJson(raw) ?? raw;
  if (!parsed || typeof parsed !== "object") return null;
  if ("code" in parsed && "message" in parsed && "data" in parsed) {
    return parsed.code === 200 ? parsed.data : null;
  }
  return parsed;
}

export async function getOssSts() {
  const candidates = [
    "/oss/sts",
    "/api/oss/sts",
    "http://localhost:8080/oss/sts",
    `${window.location.protocol}//${window.location.hostname}:8080/oss/sts`,
  ];
  let lastRaw = null;
  for (const path of candidates) {
    try {
      const raw = await httpRequest(path, { method: "GET" });
      lastRaw = raw;
      const data = normalizeWrappedPayload(raw);
      if (data && typeof data === "object") {
        return data;
      }
    } catch (e) {
      lastRaw = e;
    }
  }
  throw new Error(
    `获取 STS 失败：已尝试 ${candidates.join("、")}。请检查 Nginx 反代或后端跨域配置。`,
  );
}

