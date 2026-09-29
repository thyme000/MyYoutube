import { ref, computed, watch, onUnmounted } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { upsertConversationRow } from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import { DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";

/**
 * 群成员名册：成员列表加载、群主判定、成员右键菜单与禁言。
 * @param props 组件 props（row / selfUserId）
 * @param meta useChatSessionMeta 的返回值
 * @param onOpenUser 点击成员头像时回调（userId）
 */
export function useChatSessionRoster(props, meta, { onOpenUser }) {
  const members = ref(/** @type {any[]} */ ([]));
  const membersLoading = ref(false);
  const membersError = ref("");

  const isGroupOwner = computed(() => {
    if (!meta.isGroup.value || !meta.selfStr.value) return false;
    const hit = members.value.find((m) => String(m.userId) === meta.selfStr.value);
    return Boolean(hit?.isGroupOwner);
  });

  /** 可移除：非群主（后端 isGroupOwner） */
  const membersRemovable = computed(() => members.value.filter((m) => !m?.isGroupOwner));

  async function loadMembers() {
    if (!meta.isGroup.value) {
      members.value = [];
      return;
    }
    const id = String(props.row?.conversationId || "");
    if (!id) return;
    membersLoading.value = true;
    membersError.value = "";
    try {
      const data = await chatApi.getGroupMembers(id);
      const list = Array.isArray(data)
        ? data
        : Array.isArray(data?.members)
          ? data.members
          : Array.isArray(data?.list)
            ? data.list
            : [];
      members.value = list;
      const convBan = data?.conversationBanned ?? data?.conversatinoBanned;
      if (convBan != null) {
        const nextBan = Boolean(convBan);
        const curBan = Boolean(props.row?.conversationBanned ?? props.row?.conversatinoBanned);
        if (nextBan !== curBan) {
          upsertConversationRow({
            conversationId: id,
            conversationBanned: nextBan,
          });
        }
      }
    } catch (e) {
      members.value = [];
      membersError.value = e?.message || "加载成员失败";
    } finally {
      membersLoading.value = false;
    }
  }

  function displayNick(m) {
    const n = String(m?.userName || m?.user_name || "").trim();
    if (!n) return `用户 ${m?.userId ?? ""}`;
    return n.length > 6 ? `${n.slice(0, 6)}…` : n;
  }

  function onMemberAvatarClick(m) {
    const uid = m?.userId != null ? String(m.userId) : "";
    if (!uid) return;
    onOpenUser(uid);
  }

  function onMemberAvatarError(ev) {
    const t = ev?.target;
    if (t instanceof HTMLImageElement) {
      t.onerror = null;
      t.src = DEFAULT_PROFILE_AVATAR_URL;
    }
  }

  const memberMenuOpen = ref(false);
  const memberMenuX = ref(0);
  const memberMenuY = ref(0);
  const memberMenuUserId = ref(/** @type {string|null} */ (null));
  const memberMenuBusy = ref(false);

  function closeMemberMenu() {
    memberMenuOpen.value = false;
    memberMenuUserId.value = null;
    memberMenuBusy.value = false;
  }

  function canShowMemberBan(m) {
    if (!isGroupOwner.value) return false;
    if (meta.conversationBanned.value) return false;
    const uid = m?.userId != null ? String(m.userId) : "";
    if (!uid) return false;
    if (meta.selfStr.value && uid === meta.selfStr.value) return false;
    return !m?.banned;
  }

  function onMemberContextMenu(m, ev) {
    ev?.preventDefault?.();
    if (!canShowMemberBan(m)) {
      closeMemberMenu();
      return;
    }
    memberMenuUserId.value = String(m.userId);
    memberMenuX.value = Math.max(8, Math.min((ev?.clientX ?? 0) + 2, window.innerWidth - 160));
    memberMenuY.value = Math.max(8, Math.min((ev?.clientY ?? 0) + 2, window.innerHeight - 80));
    memberMenuOpen.value = true;
  }

  function onGlobalPointerDownForMemberMenu(ev) {
    if (!memberMenuOpen.value) return;
    const t = ev?.target;
    if (t instanceof Element && t.closest(".chat-member-menu")) return;
    closeMemberMenu();
  }

  function onGlobalKeyDownForMemberMenu(ev) {
    if (ev?.key === "Escape") closeMemberMenu();
  }

  watch(
    () => memberMenuOpen.value,
    (on) => {
      if (on) {
        document.addEventListener("mousedown", onGlobalPointerDownForMemberMenu, true);
        window.addEventListener("keydown", onGlobalKeyDownForMemberMenu);
        window.addEventListener("scroll", closeMemberMenu, true);
      } else {
        document.removeEventListener("mousedown", onGlobalPointerDownForMemberMenu, true);
        window.removeEventListener("keydown", onGlobalKeyDownForMemberMenu);
        window.removeEventListener("scroll", closeMemberMenu, true);
      }
    }
  );

  onUnmounted(() => {
    document.removeEventListener("mousedown", onGlobalPointerDownForMemberMenu, true);
    window.removeEventListener("keydown", onGlobalKeyDownForMemberMenu);
    window.removeEventListener("scroll", closeMemberMenu, true);
  });

  async function onMemberBanClick() {
    if (!memberMenuOpen.value || !memberMenuUserId.value) return;
    const cid = String(props.row?.conversationId || "");
    if (!cid || memberMenuBusy.value) return;
    memberMenuBusy.value = true;
    try {
      await chatApi.banGroupMember(cid, memberMenuUserId.value, true);
      const uid = memberMenuUserId.value;
      members.value = (members.value || []).map((m) =>
        String(m?.userId) === String(uid) ? { ...m, banned: true } : m
      );
      toast("已禁言该成员", "info");
      closeMemberMenu();
    } catch (e) {
      toast(e?.message || "操作失败", "error");
      memberMenuBusy.value = false;
    }
  }

  return {
    members,
    membersLoading,
    membersError,
    isGroupOwner,
    membersRemovable,
    memberMenuOpen,
    memberMenuX,
    memberMenuY,
    memberMenuBusy,
    loadMembers,
    displayNick,
    onMemberAvatarClick,
    onMemberAvatarError,
    onMemberContextMenu,
    onMemberBanClick,
  };
}
