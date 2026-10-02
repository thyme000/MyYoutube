// @ts-check
import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";
import { normalizePageResult } from "./videoApi.js";

/** @typedef {import("./types").PageResult} PageResult */
/** @typedef {import("./types").UserSearchRow} UserSearchRow */

/** 开发联调：在 .env.development 设 VITE_SEARCH_MOCK=true 使用本地 mock，不请求后端 */
const USE_SEARCH_MOCK = import.meta.env.VITE_SEARCH_MOCK === "true";

/**
 * GET /search/prompt?keyword=
 * @returns {Promise<string[]>}
 */
export async function fetchSearchPrompt(keyword) {
  const k = String(keyword ?? "").trim();
  if (USE_SEARCH_MOCK) {
    const { mockSearchPrompt } = await import("./searchMock.js");
    return mockSearchPrompt(k);
  }
  const data = await httpRequest("/search/prompt", { method: "GET", query: { keyword: k } });
  if (Array.isArray(data)) return data.map((x) => String(x));
  if (data && Array.isArray(data.list)) return data.list.map((x) => String(x));
  return [];
}

/**
 * GET /search/hot
 * @returns {Promise<string[]>}
 */
export async function fetchSearchHot() {
  if (USE_SEARCH_MOCK) {
    const { mockSearchHot } = await import("./searchMock.js");
    return mockSearchHot();
  }
  const data = await httpRequest("/search/hot", { method: "GET" });
  if (Array.isArray(data)) return data.map((x) => String(x));
  if (data && Array.isArray(data.list)) return data.list.map((x) => String(x));
  return [];
}

/**
 * GET /search/video?keyword=&sortByLatest=&pageNo=&pageSize=
 * 后端 SearchVideoPageQuery：sortByLatest=true 按最新；false 为相关度/综合（ES function_score）
 */
/** @param {{ keyword?: string, sort?: string, pageNo?: number, pageSize?: number, sortByLatest?: boolean } & Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchSearchVideo(query = {}) {
  const q = { ...query };
  if (q.keyword != null) q.keyword = String(q.keyword).trim();
  const sort = q.sort;
  delete q.sort;
  if (sort === "latest") {
    q.sortByLatest = true;
  } else {
    q.sortByLatest = false;
  }
  if (USE_SEARCH_MOCK) {
    const { mockSearchVideoPage } = await import("./searchMock.js");
    return mockSearchVideoPage(q);
  }
  const data = await httpRequest("/search/video", { method: "GET", query: q });
  return normalizePageResult(data);
}

/**
 * GET /search/user?keyword=&pageNo=&pageSize=
 */
/** @param {{ keyword?: string, pageNo?: number, pageSize?: number } & Record<string, any>} [query] @returns {Promise<{ total: number, pages: number, list: UserSearchRow[] }>} */
export async function fetchSearchUser(query = {}) {
  const q = { ...query };
  if (q.keyword != null) q.keyword = String(q.keyword).trim();
  if (USE_SEARCH_MOCK) {
    const { mockSearchUserPage } = await import("./searchMock.js");
    return mockSearchUserPage(q);
  }
  const data = await httpRequest("/search/user", { method: "GET", query: q });
  return normalizeUserSearchPage(data);
}

/**
 * GET /search/tag?tagName=&pageNo=&pageSize=
 * 后端 SearchVideoByTagPageQuery 字段为 tagName（与 keyword 对齐：由前端从搜索词映射）
 */
/** @param {{ keyword?: string, pageNo?: number, pageSize?: number, tagName?: string } & Record<string, any>} [query] @returns {Promise<PageResult>} */
export async function fetchSearchTag(query = {}) {
  const q = { ...query };
  const kw = q.keyword != null ? String(q.keyword).trim() : "";
  delete q.keyword;
  q.tagName = kw;
  if (USE_SEARCH_MOCK) {
    const { mockSearchTagPage } = await import("./searchMock.js");
    return mockSearchTagPage(q);
  }
  const data = await httpRequest("/search/tag", { method: "GET", query: q });
  return normalizePageResult(data);
}

/** @param {any} data @returns {{ total: number, pages: number, list: UserSearchRow[] }} */
function normalizeUserSearchPage(data) {
  if (!data || typeof data !== "object") {
    return { total: 0, pages: 0, list: [] };
  }
  const rawList = Array.isArray(data.list)
    ? data.list
    : Array.isArray(data.records)
      ? data.records
      : [];
  return {
    total: Number(data.total) || 0,
    pages: Number(data.pages ?? data.pageCount) || 0,
    list: rawList.map(normalizeUserSearchRow).filter(Boolean),
  };
}

/** @param {any} raw @param {number} index @returns {UserSearchRow|null} */
function normalizeUserSearchRow(raw, index) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.userId ?? raw.uid;
  const isFollowRaw = raw.isFollow;
  let isFollow = false;
  if (isFollowRaw === true || isFollowRaw === 1 || isFollowRaw === "1" || isFollowRaw === "true") {
    isFollow = true;
  } else if (isFollowRaw === false || isFollowRaw === 0 || isFollowRaw === "0" || isFollowRaw === "false") {
    isFollow = false;
  }
  return {
    id: id != null ? safeSnowflakeId(id, `u-${index}`) : `u-${index}`,
    nickname: raw.nickname ?? raw.nickName ?? "用户",
    username: raw.username ?? "",
    avatarUrl: raw.avatarUrl ?? raw.avatar ?? "",
    introduction: raw.introduction ?? raw.bio ?? "",
    fans: Number(raw.fans ?? raw.fanCount) || 0,
    likeCount: Number(raw.likeCount ?? raw.likes ?? raw.likeNum) || 0,
    isFollow,
  };
}
