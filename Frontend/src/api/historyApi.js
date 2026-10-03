// @ts-check
import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";
import { normalizePageResult } from "./videoApi.js";

/**
 * 观看历史(视频/动态同池)。历史在播放满阈值时由前端写入, 重复观看自动置顶。
 */

/** @param {string|number} contentId @returns {Promise<any>} */
export async function recordViewHistory(contentId) {
  const id = safeSnowflakeId(contentId);
  if (!id) throw new Error("contentId 不能为空");
  return httpRequest(`/history/${encodeURIComponent(id)}`, { method: "PUT" });
}

/** @param {{ pageNo?: number, pageSize?: number }} query @returns {Promise<any>} */
export async function fetchHistoryPage(query = {}) {
  const data = await httpRequest("/history/page", { method: "GET", query });
  return normalizePageResult(data);
}

/** @param {string|number} contentId @returns {Promise<any>} */
export async function deleteHistoryItem(contentId) {
  const id = safeSnowflakeId(contentId);
  if (!id) throw new Error("contentId 不能为空");
  return httpRequest(`/history/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** @returns {Promise<any>} */
export async function clearMyHistory() {
  return httpRequest("/history", { method: "DELETE" });
}
