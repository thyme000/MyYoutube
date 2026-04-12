/**
 * MyYoutube 聊天模块 HTTP（与后端 Controller 路径一致）。
 * 统一 Result：由 httpRequest 校验 code===200 后返回 data。
 */
import { httpRequest, uploadMultipart } from "./http.js";

/** @param {{ type: string, memberIds: string[], name: string, avatar: string }} body — memberIds 用数字字符串避免 JS Number 精度丢失 */
export function createConversation(body) {
  return httpRequest("/chat/conversation/create", { method: "POST", body });
}

export function updateGroupAvatar(conversationId, file) {
  const fd = new FormData();
  fd.append("avatar", file);
  return uploadMultipart(`/chat/conversation/avatar/${encodeURIComponent(conversationId)}`, fd, {
    method: "PUT",
  });
}

export function updateGroupName(conversationId, name) {
  return httpRequest(`/chat/conversation/name/${encodeURIComponent(conversationId)}`, {
    method: "PUT",
    query: { name },
  });
}

export function deleteConversation(conversationId) {
  return httpRequest(`/chat/conversation/delete/${encodeURIComponent(conversationId)}`, {
    method: "DELETE",
  });
}

export function removeGroupMember(conversationId, userId) {
  return httpRequest(
    `/chat/conversation/remove/${encodeURIComponent(conversationId)}/${encodeURIComponent(String(userId))}`,
    { method: "DELETE" }
  );
}

export function inviteGroupMember(conversationId, userId) {
  return httpRequest(
    `/chat/conversation/invite/${encodeURIComponent(conversationId)}/${encodeURIComponent(String(userId))}`,
    { method: "PUT" }
  );
}

export function getGroupMembers(conversationId) {
  return httpRequest(`/chat/conversation/member/list/${encodeURIComponent(conversationId)}`, {
    method: "GET",
  });
}

export function banConversation(conversationId, banned) {
  return httpRequest(`/chat/conversation/ban/${encodeURIComponent(conversationId)}`, {
    method: "PUT",
    query: { banned },
  });
}

/** @returns {Promise<{ conversationId?: string, version?: number, text?: string|null }>} */
export function getAnnouncement(conversationId) {
  return httpRequest(`/chat/conversation/announcement/${encodeURIComponent(conversationId)}`, {
    method: "GET",
  });
}

export function publishAnnouncement(conversationId, text) {
  return httpRequest(`/chat/conversation/announcement/${encodeURIComponent(conversationId)}`, {
    method: "PUT",
    body: { text },
  });
}

export function markAnnouncementRead(conversationId) {
  return httpRequest(`/chat/user/conversation/${encodeURIComponent(conversationId)}/read/announcement`, {
    method: "PUT",
  });
}

/** @param {Record<string, unknown>} body ChatMessageDTO */
export function sendMessage(body) {
  return httpRequest("/chat/message/send", { method: "POST", body });
}

/** @param {Record<string, unknown>} body ChatMessageWithdrawDTO */
export function withdrawMessage(body) {
  return httpRequest("/chat/message/withdraw", { method: "POST", body });
}

/**
 * 历史分页：seq 为上一页最小 seq（更小更旧），不传则从最新开始。
 * @param {string} conversationId
 * @param {{ seq?: number|null, size: number }} query
 */
export function listMessages(conversationId, query) {
  return httpRequest(`/chat/message/list/${encodeURIComponent(conversationId)}`, {
    method: "GET",
    query,
  });
}

/**
 * 增量：seq 为当前本地最大 seq，拉更大 seq 的新消息。
 */
export function syncMessages(conversationId, query) {
  return httpRequest(`/chat/message/sync/${encodeURIComponent(conversationId)}`, {
    method: "GET",
    query,
  });
}

export function readConversation(conversationId, lastReadSeq) {
  return httpRequest(`/chat/message/read/${encodeURIComponent(conversationId)}`, {
    method: "POST",
    query: { lastReadSeq },
  });
}

/**
 * 会话内搜索聊天记录（分页）。
 * @param {string} conversationId
 * @param {{ keyword: string, pageNum: number, pageSize: number }} query
 * @returns {Promise<{ total?: number, pages?: number, list?: any[] }>}
 */
export function findChatRecords(conversationId, query) {
  return httpRequest(`/chat/message/find/record/${encodeURIComponent(conversationId)}`, {
    method: "GET",
    query,
  });
}

export function listUserConversations(page, pageSize) {
  return httpRequest("/chat/user/conversation/list", {
    method: "GET",
    query: { page, pageSize },
  });
}

export function getTotalUnread() {
  return httpRequest("/chat/user/conversation/unread", { method: "GET" });
}

export function setConversationMute(conversationId, mute) {
  return httpRequest(`/chat/user/conversation/${encodeURIComponent(conversationId)}/mute`, {
    method: "PUT",
    query: { mute },
  });
}

export function setConversationPin(conversationId, pin) {
  return httpRequest(`/chat/user/conversation/${encodeURIComponent(conversationId)}/pin`, {
    method: "PUT",
    query: { pin },
  });
}
