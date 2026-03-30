import { httpRequest } from "./http";
import { safeSnowflakeId } from "../utils/snowflakeId.js";

/**
 * 上报用户视频行为（静默调用，失败由调用方吞掉）
 * POST /behavior/video — videoId 以字符串传递，避免 JSON 数字精度丢失；后端 Jackson 已支持字符串反序列化为 Long
 */
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
