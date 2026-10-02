// @ts-check
/** 统一内容池消费端 API。后端路由 /contents/**，视频与动态同池混排。 */
import { httpRequest } from "./http";

/**
 * GET /contents/list 首页混排网格
 * @param {{ pageNo?: number, pageSize?: number, sort?: string, category?: string }} [query]
 * @returns {Promise<{ total: number, pages: number, list: Array<object> }>}
 */
export async function fetchContentList(query = {}) {
  const data = await httpRequest("/contents/list", { method: "GET", query });
  return normalizeContentPage(data);
}

/**
 * GET /contents/mine 我的内容(创作中心/我的作品): 视频与动态同列, 含审核状态与可见性
 * @param {{ pageNo?: number, pageSize?: number }} [query]
 * @returns {Promise<{ total: number, pages: number, list: Array<object> }>}
 */
export async function fetchMyContents(query = {}) {
  const data = await httpRequest("/contents/mine", { method: "GET", query });
  return normalizeContentPage(data);
}

/**
 * GET /contents/feed 统一消费流(连播): 推荐召回全量混排
 * @param {{ pageNo?: number, pageSize?: number }} [query]
 * @returns {Promise<{ total: number, pages: number, list: Array<object> }>}
 */
export async function fetchContentFeed(query = {}) {
  const data = await httpRequest("/contents/feed", { method: "GET", query });
  return normalizeContentPage(data);
}

/** @param {any} data @returns {{ total: number, pages: number, list: Array<object> }} */
function normalizeContentPage(data) {
  const raw = data && typeof data === "object" ? data : {};
  const rawList = Array.isArray(raw.list) ? raw.list : Array.isArray(raw.records) ? raw.records : [];
  return {
    total: Number(raw.total) || 0,
    pages: Number(raw.pages) || 0,
    list: rawList,
  };
}
