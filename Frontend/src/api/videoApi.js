import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

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
function safeVideoId(id, fallback) {
  return safeSnowflakeId(id, fallback);
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

function pickVisibility(item) {
  return item.visibility ?? item.visible ?? item.scope ?? "PUBLIC";
}

/**
 * 列表项「当前用户是否已赞」：无字段或显式 null 时返回 undefined，避免 Boolean(undefined) 误成 false
 * （赞过列表接口常不带 isLike，全屏播放器需走 feedScope==='liked' 的默认已赞逻辑）
 */
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
  const favoriteRaw =
    item.favoriteCount ?? item.collectCount ?? item.starCount ?? item.favorites ?? item.collects;
  const shareRaw = item.shareCount ?? item.shares ?? item.shareNum;
  let likeCount;
  let commentCount;
  let favoriteCount;
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
  if (favoriteRaw != null && favoriteRaw !== "") {
    const n = Number(favoriteRaw);
    favoriteCount = Number.isFinite(n) ? n : undefined;
  } else {
    favoriteCount = undefined;
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
    favoriteCount,
    shareCount,
    videoUrl: item.videoUrl ?? "",
    coverUrl: cover,
    /** 封面图（与 coverUrl 一致，兼容旧模板） */
    avatarUrl: cover,
    authorAvatarUrl: authorAvatar,
    authorId:
      item.authorId != null && item.authorId !== "" ? safeVideoId(item.authorId, "") : item.authorId,
    videoType: item.videoType,
    category: item.category,
    visibility: pickVisibility(item),
    createTime: pickDateTime(item),
    playbackTime: item.playbackTime ?? 0,
    authorNickname: nick || undefined,
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

/**
 * GET /videos/list  条件分页（走 Nginx → 后端）
 * @param query { pageNo, pageSize, category?, sort?, userId?, tags?, begin?, end? }
 */
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
export async function fetchVideoDetail(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/detail/${encodeURIComponent(id)}`, { method: "GET" });
}

/**
 * GET /videos/{authorId} 作者作品分页
 * videoType: LONG_VIDEO | SHORT_VIDEO（与后端 VideoTypeEnum 枚举名一致；「全部」用 LONG_VIDEO 触发后端当前逻辑）
 */
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
export async function fetchMyVideos(query = {}) {
  const data = await httpRequest("/videos/me/list", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/{userId}/list 他人主页作品分页（公开、已发布、未删除）
 */
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
export async function publishVideo(body) {
  return httpRequest("/videos/publish", { method: "PUT", body });
}

/** DELETE /videos/{videoId} 删除自己的视频 */
export async function deleteMyVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** POST /videos/private/{videoId} 设为私密 */
export async function setMyVideoPrivate(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/private/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/public/{videoId} 设为公开 */
export async function setMyVideoPublic(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/public/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/open/comment/{videoId} 开启评论 */
export async function openMyVideoComment(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/open/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /videos/close/comment/{videoId} 关闭评论 */
export async function closeMyVideoComment(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/videos/close/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

export async function fetchVideosByModule(moduleKey) {
  if (moduleKey === "shorts") {
    const data = await fetchShortsFeedPage({ pageNo: 1, pageSize: 24 });
    return data.list;
  }
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

/**
 * GET /videos/shorts/feed 短视频推荐流分页
 */
export async function fetchShortsFeedPage(query = {}) {
  const data = await httpRequest("/videos/shorts/feed", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /user/history/short 短视频历史分页（仅当前登录用户）
 */
export async function fetchShortHistoryPage(query = {}) {
  const data = await httpRequest("/user/history/short", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /user/history/long 长视频历史分页（仅当前登录用户）
 */
export async function fetchLongHistoryPage(query = {}) {
  const data = await httpRequest("/user/history/long", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * GET /videos/follow/list 关注视频流（分页，滚动加载）
 */
export async function fetchFollowVideoFeedPage(query = {}) {
  const data = await httpRequest("/videos/follow/list", { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * PUT /videos/shorts/play 批量上报短视频观看记录
 * 后端 DTO: UserShortsWatchRecords { userId, Set<Long> shortIds }
 */
export async function reportShortHistoryBatch(body) {
  const rawIds = Array.isArray(body?.shortIds) ? body.shortIds : body?.videoIds || [];
  const shortIds = rawIds
    .map((id) => safeSnowflakeId(id))
    .filter((id) => id !== "" && id != null);
  const payload = {
    userId: body?.userId ?? undefined,
    shortIds,
  };
  if (payload.userId != null && payload.userId !== "") {
    payload.userId = safeSnowflakeId(payload.userId);
  }
  return httpRequest("/videos/shorts/play", { method: "PUT", body: payload });
}
