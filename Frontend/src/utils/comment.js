import { nextTick } from "vue";

/** 本人评论显示「我」，需显式传入当前登录用户 id 做比较 */
export function commentDisplayNickname(userId, nickname, currentUserId) {
  const nick = nickname ?? "用户";
  if (currentUserId == null || userId == null || userId === "") {
    return nick;
  }
  return String(userId) === String(currentUserId) ? "我" : nick;
}

export function formatCommentTime(isoLike) {
  if (isoLike == null || isoLike === "") {
    return "";
  }
  try {
    const s = String(isoLike);
    const d = new Date(s.includes("T") ? s : s.replace(" ", "T"));
    if (Number.isNaN(d.getTime())) {
      return s;
    }
    const sec = Math.floor((Date.now() - d.getTime()) / 1000);
    if (sec < 60) {
      return "刚刚";
    }
    if (sec < 3600) {
      return `${Math.floor(sec / 60)} 分钟前`;
    }
    if (sec < 86400) {
      return `${Math.floor(sec / 3600)} 小时前`;
    }
    if (sec < 86400 * 7) {
      return `${Math.floor(sec / 86400)} 天前`;
    }
    return d.toLocaleDateString("zh-CN");
  } catch {
    return String(isoLike);
  }
}

/** 后端屏蔽态：status 为 BLOCKED/BANNED 或 2，或正文命中屏蔽占位文案 */
export function isBlockedComment(comment) {
  const stRaw = comment?.status ?? comment?.commentStatus ?? comment?.comment_status;
  const st = String(stRaw ?? "").trim().toUpperCase();
  if (st === "BLOCKED" || st === "BANNED") return true;
  if (stRaw === 2 || stRaw === "2") return true;
  const text = String(comment?.content || "");
  return text.includes("评论违规") || text.includes("已被处理");
}

/** 在输入框光标处插入表情；拿不到输入元素时退化为追加到末尾 */
export function insertGlyphIntoCommentDraft(draftRef, inputRef, glyph) {
  const g = String(glyph || "");
  if (!g) return;
  const el = inputRef?.value;
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    const v = String(draftRef.value ?? "");
    const start = typeof el.selectionStart === "number" ? el.selectionStart : v.length;
    const end = typeof el.selectionEnd === "number" ? el.selectionEnd : start;
    draftRef.value = `${v.slice(0, start)}${g}${v.slice(end)}`;
    nextTick(() => {
      const pos = start + g.length;
      el.selectionStart = el.selectionEnd = pos;
      el.focus();
    });
  } else {
    draftRef.value = String(draftRef.value ?? "") + g;
  }
}
