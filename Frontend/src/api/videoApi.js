import { httpRequest } from "./http";

const moduleApiMap = {
  home: "/api/videos/home",
  shorts: "/api/videos/shorts",
  subscribed: "/api/videos/subscribed",
  history: "/api/videos/history",
  liked: "/api/videos/liked",
  watchLater: "/api/videos/watch-later",
  music: "/api/videos/music",
  movies: "/api/videos/movies",
  live: "/api/videos/live",
  news: "/api/videos/news",
  fashion: "/api/videos/fashion",
};

/** 雪花 ID 等必须保持字符串，避免 JS Number 精度丢失 */
function safeVideoId(id, fallback) {
  if (id == null || id === "") {
    return fallback;
  }
  if (typeof id === "bigint") {
    return id.toString();
  }
  return String(id);
}

function pickAuthorNickname(item) {
  const n =
    item.authorNickname ??
    item.nickname ??
    item.nickName ??
    item.authorNickName ??
    "";
  return typeof n === "string" && n.trim() ? n.trim() : "";
}

/** 探索/模块等接口与首页列表共用：优先展示作者昵称，与详情页一致 */
function normalizeVideo(item, index) {
  const cover = item.coverUrl ?? item.avatarUrl ?? "";
  const nick = pickAuthorNickname(item);
  const authorLabel =
    nick ||
    (typeof item.author === "string" && item.author.trim() ? item.author.trim() : "") ||
    (typeof item.channelName === "string" && item.channelName.trim() ? item.channelName.trim() : "") ||
    (item.authorId != null ? `用户 ${item.authorId}` : "未知频道");
  return {
    id: safeVideoId(item.id, `video-${index}`),
    title: item.title ?? "未命名视频",
    author: authorLabel,
    viewsText: item.viewsText ?? item.viewCountText ?? "0次观看",
    videoUrl: item.videoUrl ?? item.playUrl ?? item.url ?? "",
    coverUrl: cover,
    avatarUrl: cover,
    authorAvatarUrl: item.authorAvatarUrl ?? item.authorAvatar ?? "",
    authorId:
      item.authorId != null && item.authorId !== "" ? safeVideoId(item.authorId, "") : item.authorId,
    videoType: item.videoType,
    authorNickname: nick || undefined,
  };
}

/** 后端 VideoListVO → 卡片 */
export function normalizeVideoListVo(item, index) {
  const play = item.playCount;
  const viewsText =
    play != null && play !== ""
      ? `${play} 次播放`
      : item.viewsText ?? "—";
  const cover = item.coverUrl ?? "";
  const nick = pickAuthorNickname(item);
  return {
    id: safeVideoId(item.id, `v-${index}`),
    title: item.title ?? "未命名视频",
    author: nick || (item.authorId != null ? `用户 ${item.authorId}` : "频道"),
    viewsText,
    videoUrl: item.videoUrl ?? "",
    coverUrl: cover,
    /** 封面图（与 coverUrl 一致，兼容旧模板） */
    avatarUrl: cover,
    authorAvatarUrl: item.authorAvatarUrl ?? item.authorAvatar ?? "",
    authorId:
      item.authorId != null && item.authorId !== "" ? safeVideoId(item.authorId, "") : item.authorId,
    videoType: item.videoType,
    category: item.category,
    authorNickname: nick || undefined,
  };
}

/**
 * 后端 VideoDetailVO → 前端详情
 */
export function normalizeVideoDetail(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const play = raw.playCount;
  const viewsText =
    play != null && play !== ""
      ? `${play} 次播放`
      : "—";
  const vt = raw.videoType;
  const videoTypeStr =
    typeof vt === "string"
      ? vt
      : vt && typeof vt === "object" && "name" in vt
        ? vt.name
        : vt != null
          ? String(vt)
          : "";
  const detailNick =
    pickAuthorNickname(raw) ||
    (raw.authorId != null ? `用户 ${raw.authorId}` : "频道");
  return {
    id: safeVideoId(raw.id, ""),
    title: raw.title ?? "未命名视频",
    description: raw.description ?? "",
    coverUrl: raw.coverUrl ?? "",
    videoUrl: raw.videoUrl ?? "",
    duration: raw.duration,
    videoType: videoTypeStr,
    playCount: play ?? 0,
    viewsText,
    likeCount: raw.likeCount ?? 0,
    commentCount: raw.commentCount ?? 0,
    authorId:
      raw.authorId != null && raw.authorId !== "" ? safeVideoId(raw.authorId, "") : raw.authorId,
    authorNickname: detailNick,
    authorAvatarUrl: raw.authorAvatarUrl ?? "",
    authorFanCount: raw.authorFanCount ?? 0,
    visibility: raw.visibility,
    createTime: raw.createTime,
  };
}

export function normalizeVideoList(payload) {
  if (Array.isArray(payload)) {
    return payload.map(normalizeVideo);
  }
  if (payload && Array.isArray(payload.list)) {
    return payload.list.map(normalizeVideo);
  }
  if (payload && Array.isArray(payload.records)) {
    return payload.records.map(normalizeVideo);
  }
  return [];
}

/**
 * 分页列表（Spring PageResult：total, pages, list）
 */
export function normalizePageResult(data) {
  if (!data || !Array.isArray(data.list)) {
    return { total: 0, pages: 0, list: [] };
  }
  return {
    total: data.total ?? 0,
    pages: data.pages ?? 0,
    list: data.list.map((item, i) => normalizeVideoListVo(item, i)),
  };
}

/**
 * GET /videos/list  条件分页（走 Nginx → 后端）
 * @param query { pageNo, pageSize, category?, sort?, userId?, tags?, begin?, end? }
 */
export async function fetchVideoListPage(query = {}) {
  const data = await httpRequest("/videos/list", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/detail/{videoId}
 */
export async function fetchVideoDetail(videoId) {
  return httpRequest(`/videos/detail/${videoId}`, { method: "GET" });
}

/**
 * GET /videos/{authorId} 作者作品分页
 * videoType: LONG_VIDEO | SHORT_VIDEO（与后端 VideoTypeEnum 枚举名一致；「全部」用 LONG_VIDEO 触发后端当前逻辑）
 */
export async function fetchAuthorVideos(authorId, query = {}) {
  const data = await httpRequest(`/videos/${authorId}`, { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * PUT /videos/publish
 */
export async function publishVideo(body) {
  return httpRequest("/videos/publish", { method: "PUT", body });
}

export async function fetchVideosByModule(moduleKey) {
  const apiPath = moduleApiMap[moduleKey] || moduleApiMap.home;
  const data = await httpRequest(apiPath);
  return normalizeVideoList(data);
}

export async function searchVideos(keyword) {
  const data = await httpRequest("/api/search", {
    query: { keyword },
  });
  return normalizeVideoList(data);
}
