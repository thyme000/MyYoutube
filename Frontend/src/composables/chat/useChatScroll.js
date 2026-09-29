import { nextTick, onUnmounted } from "vue";
import { ensureMessageSeqLoaded } from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";

/**
 * 消息列表滚动：吸底、跳转定位（含自动滚底抑制窗口）、媒体加载后的底部对齐。
 * @param props 组件 props（conversationId）
 * @param listRef 列表容器 ref
 */
export function useChatScroll(props, listRef) {
  function scrollToBottomNow() {
    const el = listRef.value;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }

  /** 搜索结果跳转定位期间，禁止自动滚底（避免 items 变长 + isNearListBottom 触发把条拉到底） */
  let jumpSuppressAutoScrollUntil = 0;

  function isJumpSuppressingAutoScroll() {
    return Date.now() < jumpSuppressAutoScrollUntil;
  }

  function activateJumpScrollAutoScrollSuppress() {
    jumpSuppressAutoScrollUntil = Date.now() + 2000;
  }

  /** 布局与图片/视频加载后多次对齐到底部，避免刷新后滚动条停在中间 */
  function scheduleScrollToBottom() {
    if (isJumpSuppressingAutoScroll()) return;
    nextTick(() => {
      scrollToBottomNow();
      requestAnimationFrame(() => {
        scrollToBottomNow();
        requestAnimationFrame(scrollToBottomNow);
      });
      window.setTimeout(scrollToBottomNow, 60);
      window.setTimeout(scrollToBottomNow, 240);
      window.setTimeout(scrollToBottomNow, 600);
    });
  }

  const STICK_BOTTOM_PX = 120;

  function isNearListBottom() {
    const el = listRef.value;
    if (!el) return true;
    const { scrollTop, scrollHeight, clientHeight } = el;
    if (scrollHeight <= clientHeight + 2) return true;
    return scrollHeight - scrollTop - clientHeight < STICK_BOTTOM_PX;
  }

  let mediaLayoutTimer = null;
  function onChatMediaLayout() {
    if (mediaLayoutTimer) window.clearTimeout(mediaLayoutTimer);
    mediaLayoutTimer = window.setTimeout(() => {
      mediaLayoutTimer = null;
      if (isJumpSuppressingAutoScroll()) return;
      if (isNearListBottom()) {
        scheduleScrollToBottom();
      }
    }, 48);
  }

  function scrollJumpTargetIntoView(seq, behavior, withFlash = true) {
    const wrap = listRef.value;
    if (!wrap) return null;
    const el = wrap.querySelector(`[data-chat-seq="${seq}"]`);
    if (!(el instanceof HTMLElement)) return null;
    const beh = behavior === "smooth" ? "smooth" : "auto";
    el.scrollIntoView({ block: "center", behavior: beh });
    if (withFlash) {
      el.classList.add("chat-msg-jump-flash");
      window.setTimeout(() => el.classList.remove("chat-msg-jump-flash"), 1400);
    }
    return el;
  }

  async function runJumpToSeq(seq) {
    const id = String(props.conversationId || "");
    if (!id) return;
    const t = Number(seq);
    if (!Number.isFinite(t) || t <= 0) return;
    activateJumpScrollAutoScrollSuppress();
    try {
      await ensureMessageSeqLoaded(id, t);
      await nextTick();
      await nextTick();
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      const first = scrollJumpTargetIntoView(t, "smooth");
      if (!first) {
        toast("未在已加载消息中找到该条，请稍后再试", "info");
        return;
      }
      // 滚底定时器（最长约 600ms）可能在首次定位之后执行，再在窗口外补一次瞬时对齐
      window.setTimeout(() => {
        if (!isJumpSuppressingAutoScroll()) return;
        scrollJumpTargetIntoView(t, "auto", false);
      }, 680);
    } catch (e) {
      toast(e?.message || "跳转失败", "error");
    }
  }

  onUnmounted(() => {
    if (mediaLayoutTimer != null) {
      window.clearTimeout(mediaLayoutTimer);
      mediaLayoutTimer = null;
    }
  });

  return {
    isJumpSuppressingAutoScroll,
    scheduleScrollToBottom,
    isNearListBottom,
    onChatMediaLayout,
    runJumpToSeq,
  };
}
