// @ts-check
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

/**
 * 按 id 在若干份列表里找已有行的播放地址，避免为了一个 URL 再请求 /videos/detail。
 * 列表由调用方传入，使本函数保持无状态。
 */
export function resolveVideoUrlFromLists(videoId, hintItem, lists = []) {
  const id = videoId != null ? String(videoId) : "";
  if (!id) return "";
  const direct = resolveVideoUrl(hintItem);
  if (direct) return direct;
  for (const list of lists) {
    const row = (list || []).find((x) => x && String(x.id) === id);
    if (row) {
      const url = resolveVideoUrl(row);
      if (url) return url;
    }
  }
  return "";
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

/** 秒 → m:ss（列表内预览时长展示） */
export function formatShortsTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) {
    return "0:00";
  }
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60);
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/** 解析卡片标签为数组（兼容 tags 数组 / 逗号分隔字符串 / # 前缀） */
export function parseSearchTagsArray(card) {
  const raw = card?.tags ?? card?.tagNames ?? "";
  const arr = Array.isArray(raw)
    ? raw.map((x) => String(x).trim()).filter(Boolean)
    : String(raw || "")
        .split(/[\s,，;]+/)
        .map((x) => x.trim().replace(/^#/, ""))
        .filter(Boolean);
  return arr;
}

/** 卡片相对时间展示 */
export function formatSearchRelTime(card) {
  const t = card?.createTime ?? card?.publishTime ?? "";
  if (!t) return "";
  try {
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return "";
    const diff = Date.now() - d.getTime();
    const day = Math.floor(diff / 86400000);
    if (day <= 0) return "今天";
    if (day === 1) return "昨天";
    if (day < 7) return `${day}天前`;
    return d.toLocaleDateString();
  } catch {
    return "";
  }
}

/** 从 video 元素读取有效时长（含 seekable 兜底，流媒体 duration 可能为 Infinity） */
export function readVideoDurationFromVideoEl(v) {
  if (!v) {
    return 0;
  }
  let d = Number(v.duration);
  if (Number.isFinite(d) && d > 0 && d !== Number.POSITIVE_INFINITY) {
    return d;
  }
  try {
    if (v.seekable && v.seekable.length > 0) {
      const end = Number(v.seekable.end(v.seekable.length - 1));
      if (Number.isFinite(end) && end > 0 && end !== Number.POSITIVE_INFINITY) {
        return end;
      }
    }
  } catch {
    /* ignore */
  }
  return 0;
}
