// @ts-check
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

/** 接口有时把 userId 当昵称返回（形如「用户 123」），这类占位名不该展示给用户 */
export function stripUserIdLikeName(name) {
  const n = String(name || "").trim();
  if (!n || /^用户\s*\d+$/i.test(n)) return "";
  return n;
}

/** 头像图加载失败时回落到默认图；顶栏、评论头像、通知列表共用同一处理 */
export function applyAvatarFallback(evt, fallback = defaultAvatarUrl) {
  const el = evt?.target;
  if (el) {
    el.src = fallback;
  }
}

/**
 * 关注/粉丝等列表接口里用户对象字段名不统一时，尽量取出头像 URL（支持一层嵌套 user / followee）。
 */
export function pickRelationAvatarUrl(raw) {
  if (!raw || typeof raw !== "object") {
    return "";
  }
  const candidates = [
    raw.avatarUrl,
    raw.avatar_url,
    raw.avatar,
    raw.headImg,
    raw.headimg,
    raw.headUrl,
    raw.profileAvatar,
    raw.profile_avatar,
  ];
  for (const v of candidates) {
    if (v == null || v === "") {
      continue;
    }
    const s = sanitizeImageUrl(typeof v === "string" ? v : String(v));
    if (s) {
      return s;
    }
  }
  const nested = raw.user ?? raw.followee ?? raw.followUser ?? raw.fanUser;
  if (nested && typeof nested === "object") {
    return pickRelationAvatarUrl(nested);
  }
  return "";
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
