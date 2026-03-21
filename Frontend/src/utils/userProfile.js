/**
 * 用户资料展示：兼容后端字段命名差异，并在无有效 URL 时交给前端使用默认图。
 */

export function sanitizeImageUrl(url) {
  if (url == null) {
    return "";
  }
  const s = String(url).trim();
  if (!s || s === "null" || s === "undefined") {
    return "";
  }
  return s;
}

/**
 * 将接口返回的用户对象规范为统一字段（nickName / avatarUrl / bgImage 等）
 */
export function normalizeUserProfile(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const nickName = String(
    raw.nickName ?? raw.nickname ?? raw.nick_name ?? raw.NickName ?? ""
  ).trim();
  const username = String(raw.username ?? raw.userName ?? raw.user_name ?? "").trim();
  const avatarUrl = sanitizeImageUrl(raw.avatarUrl ?? raw.avatar_url);
  const bgImage = sanitizeImageUrl(raw.bgImage ?? raw.bg_image);
  const introduction =
    raw.introduction != null && raw.introduction !== undefined
      ? String(raw.introduction).trim()
      : "";

  return {
    ...raw,
    nickName,
    username,
    avatarUrl: avatarUrl || undefined,
    bgImage: bgImage || undefined,
    introduction,
  };
}
