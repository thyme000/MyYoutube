// @ts-check
import { httpRequest } from "./http";
// @ts-check
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/** @typedef {import("./types").PageResult} PageResult */
/** @typedef {import("./types").VideoCard} VideoCard */
/** @typedef {import("./types").VideoDetail} VideoDetail */

const moduleApiMap = {
  home: "/api/videos/home",
  shorts: "/videos/shorts/feed",
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

/** 与 safeSnowflakeId 一致，供列表/详情规范化 */
/** @param {any} id @param {string} fallback @returns {string} */
function safeVideoId(id, fallback) {
  return safeSnowflakeId(id, fallback);
}

/** @param {any} item @returns {string} */
function pickAuthorNickname(item) {
  const n =
    item.authorNickname ??
    item.nickname ??
    item.nickName ??
    item.authorNickName ??
    "";
  return typeof n === "string" && n.trim() ? n.trim() : "";
}

/** @param {any} item @returns {any} */
function pickDateTime(item) {
  return (
    item.createTime ??
    item.createAt ??
    item.createdAt ??
    item.publishTime ??
    item.updateTime ??
    ""
  );
}

/** @param {any} item @returns {any} */
function pickVisibility(item) {
  return item.visibility ?? item.visible ?? item.scope ?? "PUBLIC";
}

/**
 * 列表项「当前用户是否已赞」：无字段或显式 null 时返回 undefined，避免 Boolean(undefined) 误成 false
 * （赞过列表接口常不带 isLike，全屏播放器需走 feedScope==='liked' 的默认已赞逻辑）
 */
/** @param {any} item @returns {boolean|undefined} */
function pickListItemIsLike(item) {
  if (item == null || typeof item !== "object") {
    return undefined;
  }
  if (Object.prototype.hasOwnProperty.call(item, "isLike")) {
    const v = item.isLike;
    if (v === null || v === undefined || v === "") {
      return undefined;
    }
    if (v === false || v === 0 || v === "0") {
      return false;
    }
    return Boolean(v);
  }
  if (Object.prototype.hasOwnProperty.call(item, "liked")) {
    const v = item.liked;
    if (v === null || v === undefined || v === "") {
      return undefined;
    }
    if (v === false || v === 0 || v === "0") {
      return false;
    }
    return Boolean(v);
  }
  return undefined;
}

/** 探索/模块等接口与首页列表共用：优先展示作者昵称，与详情页一致 */
/** @param {any} item @param {number} index @returns {VideoCard} */
function normalizeVideo(item, index) {
  const cover =
    item.coverUrl ??
    item.thumbnail ??
    item.poster ??
    item.image ??
    "";
  const authorAvatar =
    item.authorAvatarUrl ??
    item.authorAvatar ??
    item.avatarUrl ??
    item.avatar ??
    "";
  const nick = pickAuthorNickname(item);
  const authorLabel =
    nick ||
    (typeof item.author === "string" && item.author.trim() ? item.author.trim() : "") ||
    (typeof item.channelName === "string" && item.channelName.trim() ? item.channelName.trim() : "") ||
    (item.authorId != null ? `用户 ${item.authorId}` : "未知频道");
  return {
    id: safeVideoId(item.id, `video-${index}`),
    title: item.title ?? "未命名视频",
    description: item.description ?? item.desc ?? item.introduction ?? "",
    tags: item.tags ?? item.tagNames ?? item.tagList ?? "",
    author: authorLabel,
    viewsText: item.viewsText ?? item.viewCountText ?? "0次观看",
    videoUrl: item.videoUrl ?? item.playUrl ?? item.url ?? "",
    coverUrl: cover,
    avatarUrl: cover,
    authorAvatarUrl: authorAvatar,
    authorId:
      item.authorId != null && item.authorId !== "" ? safeVideoId(item.authorId, "") : item.authorId,
    authorNickname: nick || undefined,
  };
}

/** 后端 VideoListVO → 卡片 */
/** @param {any} item @param {number} index @returns {VideoCard} */
export function normalizeVideoListVo(item, index) {
  const play = item.playCount;
  const viewsText =
    play != null && play !== ""
      ? `${play} 次播放`
      : item.viewsText ?? "—";
  const cover = item.coverUrl ?? item.thumbnail ?? item.poster ?? "";
  const authorAvatar =
    item.authorAvatarUrl ??
    item.authorAvatar ??
    item.avatarUrl ??
    item.avatar ??
    "";
  const nick = pickAuthorNickname(item);
  const likeRaw = item.likeCount ?? item.likes ?? item.likeNum;
  const commentRaw = item.commentCount ?? item.comments ?? item.commentNum;
  const shareRaw = item.shareCount ?? item.shares ?? item.shareNum;
  let likeCount;
  let commentCount;
  let shareCount;
  if (likeRaw != null && likeRaw !== "") {
    const n = Number(likeRaw);
    likeCount = Number.isFinite(n) ? n : undefined;
  } else {
    likeCount = undefined;
  }
  if (commentRaw != null && commentRaw !== "") {
    const n = Number(commentRaw);
    commentCount = Number.isFinite(n) ? n : undefined;
  } else {
    commentCount = undefined;
  }
  if (shareRaw != null && shareRaw !== "") {
    const n = Number(shareRaw);
    shareCount = Number.isFinite(n) ? n : undefined;
  } else {
    shareCount = undefined;
  }
  let playCount = 0;
  if (play != null && play !== "") {
    const n = Number(play);
    playCount = Number.isFinite(n) ? n : 0;
  }
  return {
    id: safeVideoId(item.id, `v-${index}`),
    title: item.title ?? "未命名视频",
    description: item.description ?? item.desc ?? item.introduction ?? "",
    tags: item.tags ?? item.tagNames ?? item.tagList ?? "",
    author: nick || (item.authorId != null ? `用户 ${item.authorId}` : "频道"),
    viewsText,
    playCount,
    likeCount,
    commentCount,
    shareCount,
    duration: item.duration,
    videoUrl: item.videoUrl ?? "",
    coverUrl: cover,
    /** 封面图（与 coverUrl 一致，兼容旧模板） */
    avatarUrl: cover,
    authorAvatarUrl: authorAvatar,
    authorId:
      item.authorId != null && item.authorId !== "" ? safeVideoId(item.authorId, "") : item.authorId,
    category: item.category,
    visibility: pickVisibility(item),
    createTime: pickDateTime(item),
    playbackTime: item.playbackTime ?? 0,
    authorNickname: nick || undefined,
    /** 当前用户是否已关注作者（搜索 ShortsVO 等接口） */
    isFollow:
      item.isFollow === true || item.isFollow === 1
        ? true
        : item.isFollow === false || item.isFollow === 0
          ? false
          : undefined,
    /** 当前用户是否已点赞（短视频流等接口返回；缺省为 undefined） */
    isLike: pickListItemIsLike(item),
    /** 是否允许评论：0/1 或 boolean（我的作品列表等） */
    allowComment:
      item.allowComment == null
        ? undefined
        : typeof item.allowComment === "boolean"
          ? item.allowComment
            ? 1
            : 0
          : Number(item.allowComment) === 1
            ? 1
            : 0,
  };
}

/**
 * 后端 VideoDetailVO → 前端详情
 */
/** @param {any} raw @returns {VideoDetail|null} */
export function normalizeVideoDetail(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const play = raw.playCount;
  const viewsText =
    play != null && play !== ""
      ? `${play} 次播放`
      : "—";
  const detailNick =
    pickAuthorNickname(raw) ||
    (raw.authorId != null ? `用户 ${raw.authorId}` : "频道");
  const playbackMsRaw = raw.playbackTime;
  const playbackMsNum = Number(playbackMsRaw);
  const playbackTime =
    Number.isFinite(playbackMsNum) && playbackMsNum > 0
      ? Math.floor(playbackMsNum)
      : 0;
  return {
    id: safeVideoId(raw.id, ""),
    title: raw.title ?? "未命名视频",
    description: raw.description ?? "",
    coverUrl: raw.coverUrl ?? "",
    videoUrl: raw.videoUrl ?? "",
    duration: raw.duration,
    playCount: play ?? 0,
    viewsText,
    likeCount: raw.likeCount ?? 0,
    commentCount: raw.commentCount ?? 0,
    authorId:
      raw.authorId != null && raw.authorId !== "" ? safeVideoId(raw.authorId, "") : raw.authorId,
    authorNickname: detailNick,
    authorAvatarUrl: raw.authorAvatarUrl ?? "",
    authorFanCount: raw.authorFanCount ?? 0,
    playbackTime,
    visibility: raw.visibility,
    createTime: raw.createTime,
    isFollow: raw.isFollow,
    isLike: Boolean(raw.isLike ?? raw.liked),
    /** 是否允许评论：0/1 或 boolean，缺省视为允许 */
    allowComment:
      raw.allowComment == null
        ? 1
        : typeof raw.allowComment === "boolean"
          ? raw.allowComment
            ? 1
            : 0
          : Number(raw.allowComment) === 1
            ? 1
            : 0,
  };
}

/** @param {any} payload @returns {VideoCard[]} */
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
 * 兼容部分接口使用 records（MyBatis Page）或其它字段名。
 */
/** @param {any} data @returns {PageResult} */
export function normalizePageResult(data) {
  if (!data || typeof data !== "object") {
    return { total: 0, pages: 0, list: [] };
  }
  const rawList = Array.isArray(data.list)
    ? data.list
    : Array.isArray(data.records)
      ? data.records
      : Array.isArray(data.content)
        ? data.content
        : [];
  return {
    total: data.total ?? 0,
    pages: data.pages ?? data.pageCount ?? 0,
    list: rawList.map((item, i) => normalizeVideoListVo(item, i)),
  };
}

/** GET /videos/list 条件分页（走 Nginx → 后端）
/** @param {{ pageNo?: number, pageSize?: number, category?: string, sort?: string, userId?: any, tags?: string, begin?: string, end?: string } & Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchVideoListPage(query = {}) {
  const q = { ...query };
  if (q.userId != null && q.userId !== "") {
    q.userId = safeSnowflakeId(q.userId);
  }
  const data = await httpRequest("/videos/list", { method: "GET", query: q });
  return normalizePageResult(data);
}

/**
 * GET /videos/detail/{videoId}
 */
/** @param {any} videoId @returns {Promise<any>} VideoDetailVO 原始 data（经 normalizeVideoDetail 落地） */
export async function fetchVideoDetail(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/detail/${encodeURIComponent(id)}`, { method: "GET" });
}

/**
 * GET /videos/{authorId} 作者作品分页
 */
/** @param {any} authorId @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchAuthorVideos(authorId, query = {}) {
  const id = safeSnowflakeId(authorId);
  if (!id) {
    throw new Error("authorId 不能为空");
  }
  const data = await httpRequest(`/videos/${encodeURIComponent(id)}`, { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/me/list 我的作品分页（需登录）
 */
/** @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchMyVideos(query = {}) {
  const data = await httpRequest("/videos/me/list", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/{userId}/list 他人主页作品分页（公开、已发布、未删除）
 */
/** @param {any} userId @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchOtherUserVideos(userId, query = {}) {
  const id = safeSnowflakeId(userId);
  if (!id) {
    throw new Error("userId 不能为空");
  }
  const data = await httpRequest(`/videos/${encodeURIComponent(id)}/list`, { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * PUT /videos/publish
 */
/** @param {{ title: string, description?: string, category: string, tags?: string, visibility: string, allowComment: number, videoUrl: string, duration: number }} body @returns {Promise<any>} */
export async function publishVideo(body) {
  return httpRequest("/videos/publish", { method: "PUT", body });
}

/** DELETE /videos/{videoId} 删除自己的视频 */
/** @param {any} videoId @returns {Promise<any>} */
export async function deleteMyVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** POST /videos/private/{videoId} 设为私密 */
/** @param {any} videoId @returns {Promise<any>} */
export async function setMyVideoPrivate(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/private/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/public/{videoId} 设为公开 */
/** @param {any} videoId @returns {Promise<any>} */
export async function setMyVideoPublic(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/public/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/open/comment/{videoId} 开启评论 */
/** @param {any} videoId @returns {Promise<any>} */
export async function openMyVideoComment(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/open/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/close/comment/{videoId} 关闭评论 */
/** @param {any} videoId @returns {Promise<any>} */
export async function closeMyVideoComment(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/close/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

/** @param {string} moduleKey @returns {Promise<VideoCard[]>} */
export async function fetchVideosByModule(moduleKey) {
  if (moduleKey === "shorts") {
    const data = await fetchShortsFeedPage({ pageNo: 1, pageSize: 24 });
    return data.list;
  }
  const apiPath = moduleApiMap[moduleKey] || moduleApiMap.home;
  const data = await httpRequest(apiPath);
  return normalizeVideoList(data);
}

/** @param {string} keyword @returns {Promise<VideoCard[]>} */
export async function searchVideos(keyword) {
  const data = await httpRequest("/api/search", {
    query: { keyword },
  });
  return normalizeVideoList(data);
}

/**
 * GET /videos/shorts/feed 短视频推荐流分页
 */
/** @param {{ pageNo?: number, pageSize?: number, category?: string }} [query] @returns {Promise<PageResult>} */
export async function fetchShortsFeedPage(query = {}) {
  const data = await httpRequest("/videos/shorts/feed", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /user/history/long 长视频历史分页（仅当前登录用户）
 */
/** @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchLongHistoryPage(query = {}) {
  const data = await httpRequest("/user/history/long", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/follow/list 关注视频流（分页，滚动加载）
 */
/** @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchFollowVideoFeedPage(query = {}) {
  const data = await httpRequest("/videos/follow/list", { method: "GET", query });
  return normalizePageResult(data);
}

