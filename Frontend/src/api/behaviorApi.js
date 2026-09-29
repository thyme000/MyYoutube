// @ts-check
import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/** @typedef {{ videoId: any, event: string, watchDurationMs?: number } & Record<string, any>} BehaviorPayload */

/**
 * 上报用户视频行为（静默调用，失败由调用方吞掉）
 * POST /behavior/video — videoId 以字符串传递，避免 JSON 数字精度丢失；后端 Jackson 已支持字符串反序列化为 Long
 */
/** @param {BehaviorPayload} payload @returns {Promise<any>} */
export async function reportVideoBehavior(payload) {
  const body = { ...payload };
  if (body.videoId != null && body.videoId !== "") {
    body.videoId = safeSnowflakeId(body.videoId);
  }
  return httpRequest("/behavior/video", {
    method: "POST",
    body,
  });
}
