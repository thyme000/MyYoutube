// @ts-check
/**
 * 读取音频文件时长(毫秒), 读不到返回 0。
 */
export function readAudioDurationMs(file) {
  return new Promise((resolve) => {
    if (!file) {
      resolve(0);
      return;
    }
    const url = URL.createObjectURL(file);
    const probe = new Audio();
    probe.preload = "metadata";
    probe.onloadedmetadata = () => {
      const ms = Math.round((Number(probe.duration) || 0) * 1000);
      URL.revokeObjectURL(url);
      resolve(ms);
    };
    probe.onerror = () => {
      URL.revokeObjectURL(url);
      resolve(0);
    };
    probe.src = url;
  });
}

/**
 * 本地音频/曲库导出段 → 上传直传 → 自动入库(公开) → 返回库条目。
 * 动态与幻灯片发布共用: 音频一律先入库再引用。
 */
import { publishAudio } from "../api/audioApi.js";
import { uploadFileWithPresign } from "./presignUpload.js";
import { STORAGE_BIZ_WORK } from "../api/otherApi.js";

export async function uploadAudioToLibrary(file, onProgress) {
  const durationMs = await readAudioDurationMs(file);
  if (durationMs <= 0) {
    throw new Error("无法读取音频时长，请换一个文件");
  }
  const { objectKey } = await uploadFileWithPresign(file, STORAGE_BIZ_WORK, onProgress);
  const title = String(file.name || "未命名音频").replace(/\.[^.]+$/, "").slice(0, 100) || "未命名音频";
  const audioId = await publishAudio({ title, objectKey, durationMs, visibility: "public" });
  return { audioId, title, durationMs };
}
