// @ts-check
/**
 * 聊天相关：头像等字段后端存对象存储 objectKey。
 * 若为完整 http(s) URL，则取 pathname（去掉前导 /）作为 objectKey；已是 key 则原样返回（仅去掉前导 /）。
 */
export function toObjectKeyOrPath(input) {
  const s = String(input ?? "").trim();
  if (!s) return "";
  if (/^https?:\/\//i.test(s)) {
    try {
      const { pathname } = new URL(s);
      const raw = pathname.startsWith("/") ? pathname.slice(1) : pathname;
      try {
        return decodeURIComponent(raw);
      } catch {
        return raw;
      }
    } catch {
      return s;
    }
  }
  return s.startsWith("/") ? s.slice(1) : s;
}
