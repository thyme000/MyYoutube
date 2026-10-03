// @ts-check
import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";
import { normalizePageResult } from "./videoApi.js";

/**
 * 内容收藏(视频/动态同池)。
 */

/** @param {string|number} contentId @returns {Promise<any>} */
export async function collectContent(contentId) {
  const id = safeSnowflakeId(contentId);
  if (!id) throw new Error("contentId 不能为空");
  return httpRequest(`/collect/${encodeURIComponent(id)}`, { method: "PUT" });
}

/** @param {string|number} contentId @returns {Promise<any>} */
export async function cancelCollectContent(contentId) {
  const id = safeSnowflakeId(contentId);
  if (!id) throw new Error("contentId 不能为空");
  return httpRequest(`/collect/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** @param {{ pageNo?: number, pageSize?: number }} query @returns {Promise<any>} */
export async function fetchCollectPage(query = {}) {
  const data = await httpRequest("/collect/page", { method: "GET", query });
  return normalizePageResult(data);
}

/** @param {string|number} contentId @returns {Promise<any>} */
export async function fetchCollectStatus(contentId) {
  const id = safeSnowflakeId(contentId);
  if (!id) throw new Error("contentId 不能为空");
  return httpRequest(`/collect/${encodeURIComponent(id)}/status`, { method: "GET" });
}
