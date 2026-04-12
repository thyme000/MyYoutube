import notificationSoundUrl from "../../static/notification.mp3?url";
import sendSoundUrl from "../../static/send.mp3?url";

/**
 * 播放短音效；失败静默（如浏览器未交互前自动播放限制）。
 * @param {string} url
 * @param {number} [volume]
 */
function playSoundUrl(url, volume = 0.88) {
  if (!url || typeof Audio === "undefined") return;
  try {
    const a = new Audio(url);
    a.volume = Math.min(1, Math.max(0, volume));
    const p = a.play();
    if (p && typeof p.catch === "function") void p.catch(() => {});
  } catch {
    /* ignore */
  }
}

/** 实时通知毛玻璃弹窗（WebSocket 等）出现时 */
export function playNotificationSound() {
  playSoundUrl(notificationSoundUrl);
}

/** 用户发送聊天消息（文本/媒体/重发进入发送流程）时 */
export function playSendSound() {
  playSoundUrl(sendSoundUrl);
}
