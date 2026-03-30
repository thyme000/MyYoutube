/**
 * 用户资料展示：兼容后端字段命名差异，并在无有效 URL 时使用 static 默认图（仅前端，不改后端）。
 */
import defaultAvatarUrl from "../../static/cat.jpg";
import defaultBgUrl from "../../static/cartoon.jpg";
import { safeSnowflakeId } from "./snowflakeId.js";

/** 默认头像 / 背景（与 App 顶栏、个人页一致） */
export const DEFAULT_PROFILE_AVATAR_URL = defaultAvatarUrl;
export const DEFAULT_PROFILE_BG_URL = defaultBgUrl;
/** 短视频作者头像缺省：与上面同源，均用 static/cat.jpg */
export const DEFAULT_SHORTS_AUTHOR_AVATAR_URL = defaultAvatarUrl;

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
  const id =
    raw.id != null && raw.id !== "" ? safeSnowflakeId(raw.id) : raw.id;

  return {
    ...raw,
    id,
    nickName,
    username,
    avatarUrl: avatarUrl || defaultAvatarUrl,
    bgImage: bgImage || defaultBgUrl,
    introduction,
  };
}
