// @ts-check
import { httpRequest } from "./http";

/** @typedef {import("./types").NotificationItem} NotificationItem */

/** 与后端 NotificationTypeEnum 的 code / JSON 枚举名一致 */
export const NOTIFICATION_TYPE_LABELS = {
  COMMENT_LIKE: "赞了你的评论",
  COMMENT_REPLY: "回复了你的评论",
  VIDEO_COMMENT: "评论了你的视频",
  VIDEO_LIKE: "赞了你的视频",
  NEW_FOLLOW: "关注了你",
  SYSTEM_NOTICE: "系统通知",
  /** 与后端 NotificationTypeEnum.CHAT_NOTICE 一致（如群主解散群） */
  CHAT_NOTICE: "群聊通知",
};

/** @param {any} raw @returns {string} */
function pickTypeCode(raw) {
  if (raw == null) return "";
  if (typeof raw === "string") return raw;
  if (typeof raw === "object") {
    return raw.code || raw.name || String(raw);
  }
  return String(raw);
}

/** @param {any} raw @returns {NotificationItem|null} */
export function normalizeNotificationItem(raw) {
  if (!raw || typeof raw !== "object") return null;
  const type = pickTypeCode(raw.type);
  const ir = raw.isRead;
  const isRead = ir === true || ir === 1 || ir === "1";
  const ex =
    typeof raw.extraJson === "string"
      ? raw.extraJson.trim()
      : raw.extraJson != null
        ? String(raw.extraJson).trim()
        : "";
  const conversationId =
    (raw.conversationId != null && String(raw.conversationId).trim()) ||
    (raw.conversation_id != null && String(raw.conversation_id).trim()) ||
    ex ||
    "";
  const conversationAvatarUrl =
    typeof raw.conversationAvatarUrl === "string"
      ? raw.conversationAvatarUrl.trim()
      : typeof raw.conversation_avatar_url === "string"
        ? raw.conversation_avatar_url.trim()
        : "";
  const conversationName =
    typeof raw.conversationName === "string"
      ? raw.conversationName.trim()
      : typeof raw.conversation_name === "string"
        ? raw.conversation_name.trim()
        : "";
  const listAvatar =
    (typeof raw.senderAvatarUrl === "string" && raw.senderAvatarUrl.trim()) ||
    conversationAvatarUrl ||
    "";
  const listNick =
    (typeof raw.senderNickname === "string" && raw.senderNickname.trim()) ||
    conversationName ||
    (String(type).toUpperCase() === "CHAT_NOTICE" ? "群聊" : "用户");
  return {
    id: raw.id,
    senderId: raw.senderId,
    senderNickname: listNick,
    senderAvatarUrl: listAvatar,
    type,
    typeLabel: NOTIFICATION_TYPE_LABELS[type] || type || "通知",
    content: raw.content || "",
    refId: raw.refId,
    videoId: raw.videoId,
    createTime: raw.createTime || "",
    isRead,
    conversationId,
    conversationAvatarUrl,
    conversationName,
    extraJson: ex,
  };
}

/**
 * PUT /notification/list，body: { pageNo, pageSize }
 * 返回 { total, pages, list }
 */
/** @param {{ pageNo?: number, pageSize?: number }} [payload] @returns {Promise<{ total: number, pages: number, list: NotificationItem[] }>} */
export async function fetchNotificationPage({ pageNo = 1, pageSize = 15 } = {}) {
  const data = await httpRequest("/notification/list", {
    method: "PUT",
    body: { pageNo, pageSize },
  });
  if (!data || typeof data !== "object") {
    return { total: 0, pages: 0, list: [] };
  }
  const list = Array.isArray(data.list) ? data.list.map(normalizeNotificationItem).filter(Boolean) : [];
  return {
    total: Number(data.total) || 0,
    pages: Number(data.pages) || 0,
    list,
  };
}

/** POST /notification/{id} 标记已读 */
/** @param {any} notificationId @returns {Promise<any>} */
export function markNotificationRead(notificationId) {
  const id = notificationId != null ? String(notificationId).trim() : "";
  if (!id) return Promise.resolve();
  return httpRequest(`/notification/${id}`, { method: "POST" });
}

/** DELETE /notification/{id} 删除通知（仅接收者本人） */
/** @param {any} notificationId @returns {Promise<any>} */
export function deleteNotification(notificationId) {
  const id = notificationId != null ? String(notificationId).trim() : "";
  if (!id) return Promise.resolve();
  return httpRequest(`/notification/${id}`, { method: "DELETE" });
}

/** GET /notification/unread-count */
/** @returns {Promise<number>} */
export async function fetchUnreadNotificationCount() {
  const n = await httpRequest("/notification/unread-count", { method: "GET" });
  const c = Number(n);
  return Number.isFinite(c) && c >= 0 ? c : 0;
}
