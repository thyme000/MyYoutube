// @ts-check
/**
 * 雪花 ID / 大整数：在 JS 中一律用字符串承载，避免 Number 精度丢失。
 */

export function safeSnowflakeId(id, fallback = "") {
  if (id == null || id === "") {
    return fallback;
  }
  if (typeof id === "bigint") {
    return id.toString();
  }
  return String(id).trim();
}

/**
 * 仅当 id 为纯数字串时返回该串（用于 URL 路径、行为上报 body）。
 * 过滤 mock 卡片 id（如 immersive-1），避免误调真实接口。
 */
export function parseDigitSnowflakeId(id) {
  if (id == null || id === "") {
    return null;
  }
  const s = String(id).trim();
  if (!/^\d+$/.test(s)) {
    return null;
  }
  return s;
}
