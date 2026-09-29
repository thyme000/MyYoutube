// @ts-check
/**
 * 通知 WebSocket 报文的字段提取。全部为纯函数，供 notificationStore 使用。
 * 雪花 id 必须从原始 JSON 文本按数字串提取，避免 JSON.parse 丢精度。
 */

/**
 * 从 WS 原始 JSON 文本中提取长整型字段，避免 JSON.parse 把雪花 id 变成 Number 丢精度。
 * @param {unknown} rawText
 * @param {string} field
 * @returns {string | null}
 */
export function extractWsJsonDigitString(rawText, field) {
  if (typeof rawText !== "string" || !field) return null;
  const esc = String(field).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  let m = rawText.match(new RegExp(`"${esc}"\\s*:\\s*(\\d+)`));
  if (m && m[1]) return m[1];
  m = rawText.match(new RegExp(`"${esc}"\\s*:\\s*"(\\d+)"`));
  return m && m[1] ? m[1] : null;
}

/**
 * 从原文或解析对象里挑出一个合法的纯数字 id 字符串。
 * @param {unknown} fromText
 * @param {Array<unknown>} candidates
 * @returns {string | null}
 */
function pickDigitId(fromText, candidates) {
  if (typeof fromText === "string" && fromText && /^\d+$/.test(fromText)) return fromText;
  const o = candidates;
  for (const c of o) {
    if (c == null || c === "") continue;
    if (typeof c === "string") {
      const t = c.trim();
      if (/^\d+$/.test(t)) return t;
    }
    if (typeof c === "number" && Number.isFinite(c)) {
      const n = Math.trunc(c);
      if (Number.isSafeInteger(n)) return String(n);
    }
  }
  return null;
}

/** 通知主键 id（与后端 NotificationForJson.id 一致），从原文提取避免大整数精度问题 */
export function pickNotifyWsNotificationId(rawText, parsed) {
  const fromText =
    extractWsJsonDigitString(rawText, "notificationId") ||
    extractWsJsonDigitString(rawText, "notification_id") ||
    extractWsJsonDigitString(rawText, "id");
  const o = parsed && typeof parsed === "object" ? parsed : {};
  return pickDigitId(fromText, [o.notificationId, o.notification_id, o.id]);
}

export function pickNotifyWsVideoId(rawText, parsed) {
  const fromText =
    extractWsJsonDigitString(rawText, "videoId") ||
    extractWsJsonDigitString(rawText, "video_id");
  const o = parsed && typeof parsed === "object" ? parsed : {};
  return pickDigitId(fromText, [o.videoId, o.video_id]);
}

/** 聊天类 WS 通知：会话 id 多为 Mongo 字符串，不能用仅数字的 extractWsJsonDigitString */
export function pickNotifyWsConversationId(rawText, parsed) {
  if (typeof rawText === "string") {
    const m = rawText.match(/"conversationId"\s*:\s*"([^"]*)"/);
    if (m && m[1] != null && String(m[1]).trim()) return String(m[1]).trim();
  }
  const o = parsed && typeof parsed === "object" ? parsed : {};
  const v = o.conversationId ?? o.conversation_id;
  if (v == null || v === "") return null;
  const s = String(v).trim();
  return s || null;
}

function pickNotifyWsContentFromObject(o) {
  if (!o || typeof o !== "object" || Array.isArray(o)) return "";
  const keys = ["content", "text", "message", "body", "commentContent", "comment_content", "preview", "snippet"];
  for (const k of keys) {
    if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
    const v = o[k];
    if (v == null) continue;
    if (typeof v === "string") {
      const t = v.trim();
      if (t) return t;
    }
    if (typeof v === "number" && Number.isFinite(v)) {
      const t = String(v).trim();
      if (t) return t;
    }
    if (typeof v === "boolean") {
      return v ? "true" : "";
    }
  }
  return "";
}

export function pickNotifyWsContent(parsed) {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return "";
  let s = pickNotifyWsContentFromObject(parsed);
  if (s) return s;
  const nestKeys = ["data", "payload", "notification", "msg"];
  for (const nk of nestKeys) {
    const inner = parsed[nk];
    if (inner && typeof inner === "object" && !Array.isArray(inner)) {
      s = pickNotifyWsContentFromObject(inner);
      if (s) return s;
    }
  }
  return "";
}

export function pickNotifyWsAvatarUrl(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const keys = [
    "senderAvatarUrl",
    "sender_avatar_url",
    "senderAvatar",
    "avatarUrl",
    "conversationAvatarUrl",
    "conversation_avatar_url",
  ];
  for (const k of keys) {
    const v = parsed[k];
    if (typeof v === "string" && v.trim()) return v.trim();
  }
  return "";
}

export function pickNotifyWsNickname(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const keys = [
    "senderNickname",
    "sender_nickname",
    "nickname",
    "senderName",
    "conversationName",
    "conversation_name",
  ];
  for (const k of keys) {
    const v = parsed[k];
    if (typeof v === "string" && v.trim()) return v.trim();
  }
  return "";
}

export function pickNotifyWsTypeCode(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const v = parsed.type;
  if (typeof v === "string" && v.trim()) return v.trim();
  if (v && typeof v === "object" && typeof v.code === "string") return v.code.trim();
  if (v && typeof v === "object" && typeof v.name === "string") return v.name.trim();
  return "";
}

/** 与 chatStore previewFromSendWs 对齐：WebSocket 下发的 contentType 可能是枚举名或对象 */
export function previewChatIncomingWsBody(o) {
  if (!o || typeof o !== "object") return "[消息]";
  let ct = o.contentType;
  if (ct && typeof ct === "object") {
    ct = ct.name ?? ct.code ?? ct;
  }
  ct = String(ct || "").toUpperCase();
  if (ct === "TEXT") return String(o.content || "").slice(0, 80);
  if (ct === "IMAGE") return "[图片]";
  if (ct === "FILE") return "[文件]";
  if (ct === "SYSTEM") return "[系统消息]";
  return "[消息]";
}
