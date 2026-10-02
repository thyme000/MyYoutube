// @ts-check
import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";
import { normalizePageResult } from "./videoApi.js";

/** @typedef {import("./types").PageResult} PageResult */

/**
 * POST /like/comment/{commentId} 给评论点赞（后端若未做幂等，前端在已赞状态下不再请求）
 */
/** @param {any} commentId @returns {Promise<any>} */
export async function likeComment(commentId) {
  const id = safeSnowflakeId(commentId);
  if (!id) {
    throw new Error("commentId 不能为空");
  }
  return httpRequest(`/like/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

/**
 * DELETE /like/comment/{commentId} 取消评论点赞
 */
/** @param {any} commentId @returns {Promise<any>} */
export async function cancelLikeComment(commentId) {
  const id = safeSnowflakeId(commentId);
  if (!id) {
    throw new Error("commentId 不能为空");
  }
  return httpRequest(`/like/comment/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/**
 * PUT /like/{videoId} 点赞（幂等）
 */
/** @param {any} videoId @returns {Promise<any>} */
export async function likeVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/like/${encodeURIComponent(id)}`, { method: "PUT" });
}

/**
 * DELETE /like/{videoId} 取消点赞（幂等）
 */
/** @param {any} videoId @returns {Promise<any>} */
export async function cancelLikeVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/like/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/**
 * POST /like/dislike/{videoId} 不感兴趣（Redis）
 */
/** @param {any} videoId @returns {Promise<any>} */
export async function dislikeVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/like/dislike/${encodeURIComponent(id)}`, { method: "POST" });
}

/**
 * GET /like/{userId}/list 点赞过的视频分页（视频列表）
 */
/** @param {any} userId @param {Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchLikedVideosPage(userId, query = {}) {
  const id = safeSnowflakeId(userId);
  if (!id) {
    throw new Error("userId 不能为空");
  }
  const data = await httpRequest(`/like/${encodeURIComponent(id)}/list`, { method: "GET", query });
  return normalizePageResult(data);
}

/**
 * 当前用户是否已点赞该视频（优先 GET /like/{videoId}/status，无则分页扫点赞列表兜底）
 * @returns {Promise<boolean>}
 */
/** @param {any} videoId @param {any} currentUserId @returns {Promise<boolean>} */
export async function fetchVideoLikedStatus(videoId, currentUserId) {
  const vid = safeSnowflakeId(videoId);
  if (!vid) {
    return false;
  }
  try {
    const data = await httpRequest(`/like/${encodeURIComponent(vid)}/status`, { method: "GET" });
    if (typeof data === "boolean") {
      return data;
    }
    if (data != null && typeof data === "object") {
      if ("liked" in data) {
        return Boolean(data.liked);
      }
      if ("isLike" in data) {
        return Boolean(data.isLike);
      }
    }
    return Boolean(data);
  } catch {
    /* 后端未实现 /status 时走兜底 */
  }
  const uid = safeSnowflakeId(currentUserId);
  if (!uid) {
    return false;
  }
  let pageNo = 1;
  const pageSize = 40;
  const maxPages = 15;
  while (pageNo <= maxPages) {
    const res = await fetchLikedVideosPage(uid, { pageNo, pageSize });
    const list = Array.isArray(res.list) ? res.list : [];
    if (list.some((row) => row && String(row.id) === String(vid))) {
      return true;
    }
    const pages = Number(res.pages || 0);
    if (pages <= 0 || pageNo >= pages || list.length < pageSize) {
      break;
    }
    pageNo += 1;
  }
  return false;
}
