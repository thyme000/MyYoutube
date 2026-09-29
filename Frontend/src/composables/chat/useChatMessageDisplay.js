import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { scheduleAfterChatMediaLoadError } from "../../stores/chatStore.js";

/**
 * 消息展示辅助：状态判定 / 媒体类型 / 附件信息 / 气泡与撤回文案 / 头像。
 * @param props 组件 props（selfUserId / conversationId）
 */
export function useChatMessageDisplay(props) {
  function isSelf(m) {
    return String(m.senderId) === String(props.selfUserId);
  }

  function isSendFailed(m) {
    return String(m?.status || "").toUpperCase() === "FAILED";
  }

  function isSending(m) {
    return String(m?.status || "").toUpperCase() === "SENDING";
  }

  function isWithdrawn(m) {
    const st = String(m?.status || "").toUpperCase();
    return st === "WITHDRAWN" || st === "WITHDRAW";
  }

  /** 后端 RecallTypeEnum：JSON 可能为 "ADMIN" / { name: "ADMIN" } */
  function normalizeRecallType(v) {
    if (v == null || v === "") return "";
    if (typeof v === "string") return String(v).toUpperCase();
    if (typeof v === "object") {
      const n = v?.name ?? v?.code;
      if (n != null) return String(n).toUpperCase();
    }
    return String(v).toUpperCase();
  }

  /** FILE 中的视频：后端暂无 VIDEO 枚举，上传走 FILE + video mime */
  function messageMediaKind(m) {
    const ct = String(m?.contentType || "").toUpperCase();
    const c = m?.content || {};
    const key = String(c.objectKey || "").trim();
    if (!key) return null;
    if (ct === "IMAGE") return "image";
    if (ct === "FILE" || ct === "VIDEO") {
      const mime = String(c.mimeType || "").toLowerCase();
      if (mime.startsWith("video/")) return "video";
      const name = String(c.fileName || c.objectKey || "").toLowerCase();
      if (/\.(mp4|webm|ogg|ogv|mov|m4v|mkv|3gp)(\?|#|$)/i.test(name)) return "video";
      if (ct === "VIDEO") return "video";
    }
    return null;
  }

  /** 非视频的 FILE（走附件卡片 + 下载确认） */
  function isChatGenericFile(m) {
    if (String(m?.contentType || "").toUpperCase() !== "FILE") return false;
    if (!String(m?.content?.objectKey || "").trim()) return false;
    return messageMediaKind(m) !== "video";
  }

  function fileAttachmentDisplayName(m) {
    const c = m?.content || {};
    const fn = String(c.fileName || "").trim();
    if (fn) return fn;
    const key = String(c.objectKey || "");
    const base = key.split("?")[0].split("#")[0].split(/[/\\]/).pop() || "";
    return base || "[文件]";
  }

  function fileAttachmentSizeLabel(m) {
    const n = Number(m?.content?.size);
    if (!Number.isFinite(n) || n <= 0) return "";
    const units = ["B", "K", "M", "G"];
    let v = n;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    if (i === 0) return `${Math.round(v)}${units[i]}`;
    const decimals = v >= 10 ? 0 : 1;
    const rounded = decimals === 0 ? Math.round(v) : Math.round(v * 10) / 10;
    let s = String(rounded);
    if (s.endsWith(".0")) s = s.slice(0, -2);
    return `${s}${units[i]}`;
  }

  /** 居中系统提示：本人撤回 vs 群主撤回成员消息 */
  function withdrawNoticeText(m) {
    const rt = normalizeRecallType(m?.recallType);
    const nick = (isSelf(m) ? "我" : String(m?.senderNickName || "").trim() || "对方").trim();
    // 群主撤自己的消息应为 SENDER；若历史数据误为 ADMIN 且展示为「我」，仍按本人撤回展示
    if (rt === "ADMIN" && !isSelf(m)) {
      return `群主撤回了「${nick}」的一条消息`;
    }
    return `\u201c${nick}\u201d 撤回了一条消息`;
  }

  function bubbleText(m) {
    const st = String(m.status || "").toUpperCase();
    if (st === "WITHDRAWN" || st === "WITHDRAW") return "已撤回";
    const c = m.content || {};
    const ct = String(m.contentType || "").toUpperCase();
    if (ct === "TEXT") return c.text || "";
    if (ct === "SYSTEM") return c.systemText || c.text || "[系统]";
    if (ct === "FORWARD_VIDEO") return "[转发视频]";
    if (ct === "IMAGE") return "";
    if (messageMediaKind(m) === "video") return "";
    if (isChatGenericFile(m)) return "";
    if (ct === "FILE") return c.fileName || c.objectKey || "[文件]";
    return c.text || "[消息]";
  }

  function avatarOf(m) {
    const s = sanitizeImageUrl(m.senderAvatarUrl);
    return s || DEFAULT_PROFILE_AVATAR_URL;
  }

  function headAvatarSrc() {
    const u = sanitizeImageUrl(props.conversationAvatar);
    return u || DEFAULT_PROFILE_AVATAR_URL;
  }

  function onHeadAvatarError(ev) {
    const t = ev?.target;
    if (t instanceof HTMLImageElement) {
      t.onerror = null;
      t.src = DEFAULT_PROFILE_AVATAR_URL;
    }
  }

  function onMsgRowAvatarError(ev) {
    const el = ev?.target;
    if (el instanceof HTMLImageElement) {
      el.onerror = null;
      el.src = DEFAULT_PROFILE_AVATAR_URL;
      el.style.opacity = "1";
    }
    scheduleAfterChatMediaLoadError(props.conversationId);
  }

  function seqAttr(m) {
    const s = Number(m?.seq);
    return Number.isFinite(s) && s > 0 ? String(s) : undefined;
  }

  return {
    isSelf,
    isSendFailed,
    isSending,
    isWithdrawn,
    normalizeRecallType,
    messageMediaKind,
    isChatGenericFile,
    fileAttachmentDisplayName,
    fileAttachmentSizeLabel,
    withdrawNoticeText,
    bubbleText,
    avatarOf,
    headAvatarSrc,
    onHeadAvatarError,
    onMsgRowAvatarError,
    seqAttr,
  };
}
