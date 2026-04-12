import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/**
 * POST /follow/{authorId} 关注（幂等）
 */
export async function followAuthor(authorId) {
  const id = safeSnowflakeId(authorId);
  if (!id) {
    throw new Error("authorId 不能为空");
  }
  return httpRequest(`/follow/${encodeURIComponent(id)}`, { method: "POST" });
}

/**
 * DELETE /follow/{authorId} 取关（幂等）
 */
export async function unfollowAuthor(authorId) {
  const id = safeSnowflakeId(authorId);
  if (!id) {
    throw new Error("authorId 不能为空");
  }
  return httpRequest(`/follow/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/**
 * GET /follow/isFollow/{authorId} 是否已关注
 * @returns {Promise<boolean>}
 */
export async function isFollowed(authorId) {
  const id = safeSnowflakeId(authorId);
  if (!id) {
    throw new Error("authorId 不能为空");
  }
  const data = await httpRequest(`/follow/isFollow/${encodeURIComponent(id)}`, { method: "GET" });
  return Boolean(data);
}

/**
 * GET /follow/list 我的关注列表（分页）
 */
export async function fetchMyFollowingPage(query = {}) {
  return httpRequest("/follow/list", { method: "GET", query });
}

/**
 * GET /follow/fans 我的粉丝列表（分页）
 */
export async function fetchMyFansPage(query = {}) {
  return httpRequest("/follow/fans", { method: "GET", query });
}

/**
 * GET /follow/interrelated 互关用户列表（分页，pageNo 从 1 起）
 * @param {{ pageNo?: number, pageSize?: number }} [query]
 */
export async function fetchInterrelatedPage(query = {}) {
  return httpRequest("/follow/interrelated", { method: "GET", query });
}

/**
 * POST /follow/remove/{followerId} 移除粉丝（当前用户为被关注方）
 */
export async function removeFan(followerId) {
  const id = safeSnowflakeId(followerId);
  if (!id) {
    throw new Error("followerId 不能为空");
  }
  return httpRequest(`/follow/remove/${encodeURIComponent(id)}`, { method: "POST" });
}

