import { httpRequest } from "./http";

/** 与后端 OtherService：视频等业务 work，聊天附件 chat */
export const STORAGE_BIZ_WORK = "work";
export const STORAGE_BIZ_CHAT = "chat";

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

/**
 * 获取前端直传预签名 PUT URL
 * GET /storage/presign?bizType=work|chat&filename=xxx
 */
export async function getStoragePresign(bizType, filename) {
  const bt = String(bizType || "").trim() || STORAGE_BIZ_WORK;
  const raw = await httpRequest("/storage/presign", {
    method: "GET",
    query: { bizType: bt, filename: String(filename || "") },
  });
  const data = normalizeWrappedPayload(raw);
  if (data && data.url && data.objectKey) {
    return data;
  }
  throw new Error("获取上传预签名失败：请检查 Nginx 反代或后端是否正常。");
}
