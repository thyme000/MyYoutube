import { ref, watch, nextTick } from "vue";
import { fetchInterrelatedPage } from "../../api/followApi.js";
import * as chatApi from "../../api/chatApi.js";
import { refreshConversationList } from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";

/**
 * 群管理操作：邀请互关好友、批量移除成员、改群名、换群头像。
 * @param props 组件 props（row）
 * @param meta useChatSessionMeta 的返回值
 * @param ctx { members, isGroupOwner, loadMembers } 来自 useChatSessionRoster
 */
export function useChatGroupAdmin(props, meta, { members, isGroupOwner, loadMembers }) {
  const inviteOpen = ref(false);
  const inviteLoading = ref(false);
  const inviteSubmitting = ref(false);
  const mutualUsers = ref([]);
  const invitePickId = ref(/** @type {string|null} */ (null));

  const removeOpen = ref(false);
  const removePickIds = ref(/** @type {string[]} */ ([]));
  const removeSubmitting = ref(false);
  const removeBulkConfirmOpen = ref(false);

  const renameBusy = ref(false);
  const renameOpen = ref(false);
  const renameDraft = ref("");
  const renameInputRef = ref(null);

  const avatarBusy = ref(false);
  const avatarInputRef = ref(null);

  // —— 邀请互关好友 ——
  async function openInvite() {
    if (!meta.isGroup.value) return;
    inviteOpen.value = true;
    invitePickId.value = null;
    inviteLoading.value = true;
    try {
      let pageNo = 1;
      const pageSize = 50;
      const all = [];
      while (true) {
        const data = await fetchInterrelatedPage({ pageNo, pageSize });
        const list = Array.isArray(data?.list) ? data.list : [];
        all.push(...list);
        const pages = Number(data?.pages) || 1;
        if (pageNo >= pages || !list.length) break;
        pageNo++;
      }
      const existing = new Set(members.value.map((m) => String(m.userId)));
      mutualUsers.value = all.filter((u) => {
        const uid = String(u?.userId ?? u?.id ?? "");
        return uid && !existing.has(uid);
      });
    } catch (e) {
      toast(e?.message || "加载互关列表失败", "error");
      mutualUsers.value = [];
    } finally {
      inviteLoading.value = false;
    }
  }

  function closeInvite() {
    inviteOpen.value = false;
  }

  function pickInviteUser(u) {
    const id = String(u?.userId ?? u?.id ?? "");
    invitePickId.value = invitePickId.value === id ? null : id;
  }

  function userPickLabel(u) {
    return String(u?.nickname || u?.nickName || "用户").trim() || "用户";
  }

  function userPickAvatar(u) {
    return sanitizeImageUrl(u?.avatarUrl) || DEFAULT_PROFILE_AVATAR_URL;
  }

  async function confirmInvite() {
    const cid = String(props.row?.conversationId || "");
    const uid = invitePickId.value;
    if (!cid || !uid) {
      toast("请选择一位互关好友", "info");
      return;
    }
    inviteSubmitting.value = true;
    try {
      await chatApi.inviteGroupMember(cid, Number(uid));
      toast("已发送邀请", "success");
      closeInvite();
      await loadMembers();
      await refreshConversationList(true);
    } catch (e) {
      toast(e?.message || "邀请失败", "error");
    } finally {
      inviteSubmitting.value = false;
    }
  }

  // —— 批量移除成员 ——
  function openRemove() {
    if (!isGroupOwner.value) return;
    removePickIds.value = [];
    removeOpen.value = true;
  }

  function closeRemove() {
    removeOpen.value = false;
  }

  function toggleRemovePick(uid) {
    const s = String(uid ?? "");
    if (!s) return;
    const arr = removePickIds.value;
    removePickIds.value = arr.includes(s) ? arr.filter((x) => x !== s) : [...arr, s];
  }

  function isRemovePicked(uid) {
    return removePickIds.value.includes(String(uid));
  }

  function openRemoveBulkConfirm() {
    const cid = String(props.row?.conversationId || "");
    const ids = removePickIds.value;
    if (!cid || !ids.length) {
      toast("请选择要移除的成员", "info");
      return;
    }
    removeBulkConfirmOpen.value = true;
  }

  function closeRemoveBulkConfirm() {
    if (removeSubmitting.value) return;
    removeBulkConfirmOpen.value = false;
  }

  async function executeRemoveMembers() {
    const cid = String(props.row?.conversationId || "");
    const ids = [...removePickIds.value];
    if (!cid || !ids.length) {
      toast("请选择要移除的成员", "info");
      return;
    }
    removeBulkConfirmOpen.value = false;
    removeSubmitting.value = true;
    const ok = [];
    const failMsgs = [];
    try {
      for (const uid of ids) {
        try {
          await chatApi.removeGroupMember(cid, uid);
          ok.push(uid);
        } catch (e) {
          failMsgs.push(String(e?.message || "移除失败"));
        }
      }
      if (ok.length) {
        await loadMembers();
        await refreshConversationList(true);
      }
      if (!failMsgs.length) {
        toast("已移除所选成员", "success");
      } else if (ok.length) {
        toast(`已移除 ${ok.length} 人；另有 ${failMsgs.length} 人失败：${failMsgs[0]}`, "error");
      } else {
        toast(failMsgs[0] || "移除失败", "error");
      }
      closeRemove();
      removePickIds.value = [];
    } finally {
      removeSubmitting.value = false;
    }
  }

  // —— 群名称 ——
  function openRenameModal() {
    if (!meta.isGroup.value || !isGroupOwner.value) return;
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    renameDraft.value = meta.convName.value;
    renameOpen.value = true;
  }

  function closeRenameModal() {
    renameOpen.value = false;
  }

  watch(renameOpen, (open) => {
    if (open) {
      void nextTick(() => {
        const el = renameInputRef.value;
        if (el instanceof HTMLInputElement) {
          el.focus();
          el.select();
        }
      });
    }
  });

  async function confirmRename() {
    if (renameBusy.value) return;
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    const name = String(renameDraft.value || "").trim();
    if (!name) {
      toast("请输入群名称", "info");
      return;
    }
    if (name === meta.convName.value) {
      closeRenameModal();
      return;
    }
    renameBusy.value = true;
    try {
      await chatApi.updateGroupName(id, name);
      toast("已更新群名称", "success");
      closeRenameModal();
      await refreshConversationList(true);
    } catch (e) {
      toast(e?.message || "修改失败", "error");
    } finally {
      renameBusy.value = false;
    }
  }

  // —— 群头像 ——
  function triggerAvatarPick() {
    if (!meta.isGroup.value || !isGroupOwner.value || avatarBusy.value) return;
    avatarInputRef.value?.click();
  }

  async function onAvatarFile(ev) {
    const input = ev?.target;
    const file = input?.files?.[0];
    if (input) input.value = "";
    if (!file) return;
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    avatarBusy.value = true;
    try {
      await chatApi.updateGroupAvatar(id, file);
      toast("群头像已更新", "success");
      await refreshConversationList(true);
    } catch (e) {
      toast(e?.message || "上传失败", "error");
    } finally {
      avatarBusy.value = false;
    }
  }

  return {
    inviteOpen,
    inviteLoading,
    inviteSubmitting,
    mutualUsers,
    invitePickId,
    removeOpen,
    removePickIds,
    removeSubmitting,
    removeBulkConfirmOpen,
    renameBusy,
    renameOpen,
    renameDraft,
    renameInputRef,
    avatarBusy,
    avatarInputRef,
    openInvite,
    closeInvite,
    pickInviteUser,
    userPickLabel,
    userPickAvatar,
    confirmInvite,
    openRemove,
    closeRemove,
    toggleRemovePick,
    isRemovePicked,
    openRemoveBulkConfirm,
    closeRemoveBulkConfirm,
    executeRemoveMembers,
    openRenameModal,
    closeRenameModal,
    confirmRename,
    triggerAvatarPick,
    onAvatarFile,
  };
}
