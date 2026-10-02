// @ts-check
/** 音频库域 API。路由 /audios/**，长整型 id 以字符串交互。 */
import { httpRequest } from "./http";

/** @typedef {{ id: string, title: string, durationMs: number, visibility: string, previewUrl: string|null, uploaderId: string, createTime: string|null }} AudioVO */

/**
 * 音频入库(上传直传后调用, 默认公开)
 * @param {{ title: string, objectKey: string, durationMs: number, visibility?: string }} body
 * @returns {Promise<string>} 音频 id
 */
export async function publishAudio(body) {
  return httpRequest("/audios/publish", { method: "POST", body });
}

/** @param {{ pageNo?: number, pageSize?: number }} [query] @returns {Promise<{ total: number, pages: number, list: AudioVO[] }>} 我的音频(含私密) */
export async function fetchMyAudios(query = {}) {
  return httpRequest("/audios/my", {
    method: "GET",
    query: { pageNo: query.pageNo ?? 1, pageSize: query.pageSize ?? 20 },
  });
}

/** @param {{ keyword?: string, pageNo?: number, pageSize?: number }} [query] @returns {Promise<{ total: number, pages: number, list: AudioVO[] }>} 公开库 */
export async function fetchAudioLibrary(query = {}) {
  return httpRequest("/audios/library", {
    method: "GET",
    query: {
      ...(query.keyword ? { keyword: query.keyword } : {}),
      pageNo: query.pageNo ?? 1,
      pageSize: query.pageSize ?? 20,
    },
  });
}

/** @param {string} id @param {"public"|"private"} visibility @returns {Promise<void>} */
export async function setAudioVisibility(id, visibility) {
  return httpRequest(`/audios/${encodeURIComponent(id)}/visibility`, {
    method: "POST",
    query: { visibility },
  });
}

/** @param {string} id @returns {Promise<void>} */
export async function deleteAudio(id) {
  return httpRequest(`/audios/${encodeURIComponent(id)}`, { method: "DELETE" });
}
