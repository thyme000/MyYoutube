import { httpRequest } from "./http";

/** 与后端 NotificationTypeEnum 的 code / JSON 枚举名一致 */
export const NOTIFICATION_TYPE_LABELS = {
  COMMENT_LIKE: "赞了你的评论",
  COMMENT_REPLY: "回复了你的评论",
  VIDEO_COMMENT: "评论了你的视频",
  VIDEO_LIKE: "赞了你的视频",
  VIDEO_COLLECT: "收藏了你的视频",
  NEW_FOLLOW: "关注了你",
  SYSTEM_NOTICE: "系统通知",
};

function pickTypeCode(raw) {
  if (raw == null) return "";
  if (typeof raw === "string") return raw;
  if (typeof raw === "object") {
    return raw.code || raw.name || String(raw);
  }
  return String(raw);
}

export function normalizeNotificationItem(raw) {
  if (!raw || typeof raw !== "object") return null;
  const type = pickTypeCode(raw.type);
  const ir = raw.isRead;
  const isRead = ir === true || ir === 1 || ir === "1";
  return {
    id: raw.id,
    senderId: raw.senderId,
    senderNickname: raw.senderNickname || "用户",
    senderAvatarUrl: raw.senderAvatarUrl || "",
    type,
    typeLabel: NOTIFICATION_TYPE_LABELS[type] || type || "通知",
    content: raw.content || "",
    refId: raw.refId,
    videoId: raw.videoId,
    createTime: raw.createTime || "",
    isRead,
  };
}

/**
 * PUT /notification/list，body: { pageNo, pageSize }
 * 返回 { total, pages, list }
 */
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
export function markNotificationRead(notificationId) {
  const id = notificationId != null ? String(notificationId).trim() : "";
  if (!id) return Promise.resolve();
  return httpRequest(`/notification/${id}`, { method: "POST" });
}

/** DELETE /notification/{id} 删除通知（仅接收者本人） */
export function deleteNotification(notificationId) {
  const id = notificationId != null ? String(notificationId).trim() : "";
  if (!id) return Promise.resolve();
  return httpRequest(`/notification/${id}`, { method: "DELETE" });
}

/** GET /notification/unread-count */
export async function fetchUnreadNotificationCount() {
  const n = await httpRequest("/notification/unread-count", { method: "GET" });
  const c = Number(n);
  return Number.isFinite(c) && c >= 0 ? c : 0;
}
