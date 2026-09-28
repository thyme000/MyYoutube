// @ts-check
import { getToken } from "../api/http";

/** 长视频续播上报（详情页与搜索全屏层共用），静默失败不影响播放体验 */
export async function sendPlaybackResumePut(payload, { keepalive = false } = {}) {
  if (!payload) return;
  try {
    const headers = {
      "Content-Type": "application/json",
    };
    const authTok = getToken();
    if (authTok) {
      headers["Authorization"] = authTok;
    }
    await fetch("/videos/playback/resume", {
      method: "PUT",
      headers,
      body: JSON.stringify(payload),
      keepalive,
    });
  } catch {
    // 静默失败：续播记录不影响播放体验
  }
}
