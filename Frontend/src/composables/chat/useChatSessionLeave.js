import { ref, computed } from "vue";
import { removeConversationLocal } from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";

/** 退出群聊 / 解散群聊 / 删除私聊：按身份给不同文案，成功后回调关闭面板 */
export function useChatSessionLeave(props, meta, { isGroupOwner, onClose }) {
  const leaveConfirmOpen = ref(false);
  const leaveBusy = ref(false);

  const leaveConfirmTitle = computed(() => {
    if (meta.isSingle.value) return "删除私聊会话";
    if (isGroupOwner.value) return "解散群聊";
    return "退出群聊";
  });

  const leaveConfirmMessage = computed(() => {
    if (meta.isSingle.value) {
      return "确定删除该私聊会话？双方会话列表都会移除。";
    }
    if (isGroupOwner.value) {
      return "确定解散该群？所有成员将移除此会话。";
    }
    return "确定退出该群？";
  });

  function openLeaveConfirm() {
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    leaveConfirmOpen.value = true;
  }

  function closeLeaveConfirm() {
    if (leaveBusy.value) return;
    leaveConfirmOpen.value = false;
  }

  async function submitLeaveConfirm() {
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    const owner = meta.isGroup.value && isGroupOwner.value;
    const single = meta.isSingle.value;
    leaveBusy.value = true;
    try {
      await removeConversationLocal(props.row);
      toast(single ? "已删除会话" : owner ? "已解散群聊" : "已退出群聊", "success");
      leaveConfirmOpen.value = false;
      onClose();
    } catch (e) {
      toast(e?.message || "操作失败", "error");
    } finally {
      leaveBusy.value = false;
    }
  }

  return {
    leaveConfirmOpen,
    leaveBusy,
    leaveConfirmTitle,
    leaveConfirmMessage,
    openLeaveConfirm,
    closeLeaveConfirm,
    submitLeaveConfirm,
  };
}
