// @ts-check
/** 图集配乐动态域 API。后端路由 /dynamics/**，长整型 id 均以字符串交互。 */
import { httpRequest } from "./http";

/** @typedef {{ id: string, userId: string, title: string, canvasType: string, canvasW: number, canvasH: number, transition: string, fadeMs: number, audioUrl: string|null, audioDurationMs: number, totalDurationMs: number, items: Array<{ imageObjectKey?: string, imageUrl: string, startMs: number, durationMs: number, focusX: number, focusY: number }>, authorId: string, authorNickname: string, authorAvatarUrl: string, createTime: number|null, allowComment?: number|null, visibility?: string|null, tags?: string|null }} DynamicVO */

/**
 * 发布图集配乐动态
 * @param {object} body
 * @returns {Promise<string>} 动态 id
 */
export async function publishDynamic(body) {
  return httpRequest("/dynamics/publish", { method: "POST", body });
}

/**
 * 动态详情(附点赞/评论计数)
 * @param {string|number} dynamicId
 * @returns {Promise<DynamicVO & { likeCount: number|null, commentCount: number|null }>}
 */
export async function fetchDynamicDetail(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/detail/${encodeURIComponent(id)}`, { method: "GET" });
}

/**
 * 请求下载成片: 懒触发 ffmpeg 转码, 缓存命中直接返回下载地址
 * @param {string} dynamicId
 * @returns {Promise<{ taskId: string, status: string, downloadUrl: string|null }>}
 */
export async function requestDynamicDownload(dynamicId) {
  return httpRequest(`/dynamics/${encodeURIComponent(dynamicId)}/download`, { method: "POST" });
}

/**
 * 查询转码任务状态
 * @param {string} taskId
 * @returns {Promise<{ taskId: string, status: string, downloadUrl: string|null }>}
 */
export async function fetchDynamicDownloadStatus(taskId) {
  return httpRequest(`/dynamics/download/${encodeURIComponent(taskId)}/status`, { method: "GET" });
}

/** DELETE /dynamics/{id} 删除自己的动态 */
/** @param {string|number} dynamicId @returns {Promise<any>} */
export async function deleteMyDynamic(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** POST /dynamics/private/{id} 动态设为私密 */
/** @param {string|number} dynamicId @returns {Promise<any>} */
export async function setMyDynamicPrivate(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/private/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /dynamics/public/{id} 动态设为公开 */
/** @param {string|number} dynamicId @returns {Promise<any>} */
export async function setMyDynamicPublic(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/public/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /dynamics/open/comment/{id} 开启动态评论 */
/** @param {string|number} dynamicId @returns {Promise<any>} */
export async function openMyDynamicComment(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/open/comment/${encodeURIComponent(id)}`, { method: "POST" });
}

/** POST /dynamics/close/comment/{id} 关闭动态评论 */
/** @param {string|number} dynamicId @returns {Promise<any>} */
export async function closeMyDynamicComment(dynamicId) {
  const id = String(dynamicId ?? "").trim();
  if (!id) throw new Error("dynamicId 不能为空");
  return httpRequest(`/dynamics/close/comment/${encodeURIComponent(id)}`, { method: "POST" });
}
