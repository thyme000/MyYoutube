/**
 * 视频卡片展示工具（纯函数，无状态）：各列表视图共用。
 */
import {
  sanitizeImageUrl,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
} from "./userProfile.js";

export function resolveVideoUrl(item) {
  if (!item || typeof item !== "object") return "";
  return (
    item.videoUrl ||
    item.playUrl ||
    item.url ||
    item.videoURL ||
    item.video_url ||
    ""
  );
}

export function videoCardPlayable(card) {
  return Boolean(resolveVideoUrl(card));
}

export function cardAuthorAvatar(card) {
  const raw = card?.authorAvatarUrl ?? card?.avatarUrl ?? "";
  const trimmed = String(raw).trim();
  if (!trimmed) {
    return profileAvatar;
  }
  const u = sanitizeImageUrl(trimmed);
  return u || profileAvatar;
}

export function cardCoverSrc(card) {
  return card?.coverUrl || card?.avatarUrl || profileBanner;
}

/** 列表/侧栏与详情同一套昵称：优先接口 authorNickname，否则用已拼好的 author */
export function cardAuthorLabel(card) {
  if (!card) {
    return "";
  }
  const nick = card.authorNickname;
  if (typeof nick === "string" && nick.trim()) {
    return nick.trim();
  }
  const a = card.author;
  if (typeof a === "string" && a.trim()) {
    return a.trim();
  }
  return "—";
}

export function formatHomeShortStat(v) {
  if (v === undefined || v === null || v === "") {
    return "0";
  }
  const num = Number(v);
  if (!Number.isFinite(num)) {
    return "0";
  }
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}万`;
  }
  return String(num);
}

export function formatWorkLikeText(item) {
  const v = item?.likeCount ?? item?.likes;
  if (v === undefined || v === null || v === "") {
    return "—";
  }
  if (typeof v === "string" && /[万千]/.test(v)) {
    return v;
  }
  const num = Number(v);
  if (!Number.isFinite(num)) {
    return "—";
  }
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}万`;
  }
  return String(num);
}
