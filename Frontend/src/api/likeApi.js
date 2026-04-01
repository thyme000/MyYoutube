import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";
import { normalizePageResult } from "./videoApi.js";

/**
 * PUT /like/{videoId} 点赞（幂等）
 */
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
export async function dislikeVideo(videoId) {
  const id = safeSnowflakeId(videoId);
  if (!id) {
    throw new Error("videoId 不能为空");
  }
  return httpRequest(`/like/dislike/${encodeURIComponent(id)}`, { method: "POST" });
}

/**
 * GET /like/{userId}/list 点赞过的视频分页（ShortsVO 列表）
 */
export async function fetchLikedVideosPage(userId, query = {}) {
  const id = safeSnowflakeId(userId);
  if (!id) {
    throw new Error("userId 不能为空");
  }
  const data = await httpRequest(`/like/${encodeURIComponent(id)}/list`, { method: "GET", query });
  return normalizePageResult(data);
}
