import { ref, onMounted, onUnmounted } from "vue";

/**
 * 群主右击气泡代撤回：菜单键为 `senderId::clientMsgId`，点击菜单外或滚动时收起。
 * @param props 组件 props（isGroupOwner）
 * @param ctx { isGroupChat, isWithdrawn, isSending, isSendFailed } 消息判定
 * @param onWithdraw 确认撤回回调
 */
export function useChatOwnerRecall(props, ctx, { onWithdraw }) {
  /** 群主右击气泡后展示的「撤回」：`senderId::clientMsgId` */
  const ownerRecallMenuKey = ref(/** @type {string|null} */ (null));

  function msgRecallKey(m) {
    return `${String(m?.senderId ?? "")}::${String(m?.clientMsgId ?? "")}`;
  }

  function canOwnerRecallMessage(m) {
    if (!ctx.isGroupChat.value || !props.isGroupOwner) return false;
    if (!m?.clientMsgId || m._local) return false;
    if (ctx.isWithdrawn(m)) return false;
    const ct = String(m.contentType || "").toUpperCase();
    if (ct === "SYSTEM") return false;
    if (ctx.isSending(m) || ctx.isSendFailed(m)) return false;
    return true;
  }

  function showOwnerRecallAction(m) {
    const k = msgRecallKey(m);
    return Boolean(k && ownerRecallMenuKey.value === k);
  }

  function onBubbleContextMenu(m, ev) {
    if (!canOwnerRecallMessage(m)) {
      return;
    }
    ev.preventDefault();
    ownerRecallMenuKey.value = msgRecallKey(m);
  }

  function onDocumentMousedownOwnerRecall(ev) {
    if (!ownerRecallMenuKey.value) return;
    const t = ev.target;
    if (t instanceof Element && t.closest(".chat-msg-owner-recall-wrap")) return;
    ownerRecallMenuKey.value = null;
  }

  function onOwnerWithdrawClick(m) {
    if (!showOwnerRecallAction(m) || !m.clientMsgId) return;
    ownerRecallMenuKey.value = null;
    onWithdraw(m);
  }

  onMounted(() => {
    document.addEventListener("mousedown", onDocumentMousedownOwnerRecall, true);
  });

  onUnmounted(() => {
    document.removeEventListener("mousedown", onDocumentMousedownOwnerRecall, true);
  });

  return {
    ownerRecallMenuKey,
    showOwnerRecallAction,
    onBubbleContextMenu,
    onOwnerWithdrawClick,
  };
}
