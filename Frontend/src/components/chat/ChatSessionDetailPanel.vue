<script setup>
import { ref, computed, watch, nextTick } from "vue";
import {
  sanitizeImageUrl,
  DEFAULT_PROFILE_AVATAR_URL,
} from "../../utils/userProfile.js";
import { fetchInterrelatedPage } from "../../api/followApi.js";
import * as chatApi from "../../api/chatApi.js";
import {
  toggleMuteRow,
  togglePinRow,
  toggleConversationBanned,
  removeConversationLocal,
  refreshConversationList,
  upsertConversationRow,
  isGroupAnnouncementUnread,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import ChatFindRecordModal from "./ChatFindRecordModal.vue";

const props = defineProps({
  row: { type: Object, required: true },
  selfUserId: { type: [String, Number], default: null },
});

const emit = defineEmits(["close", "jump-to-seq"]);

const members = ref(/** @type {any[]} */ ([]));
const membersLoading = ref(false);
const membersError = ref("");

const inviteOpen = ref(false);
const inviteLoading = ref(false);
const inviteSubmitting = ref(false);
const mutualUsers = ref([]);
const invitePickId = ref(/** @type {string|null} */ (null));

const removeOpen = ref(false);
const removePickIds = ref(/** @type {string[]} */ ([]));
const removeSubmitting = ref(false);
const removeBulkConfirmOpen = ref(false);

const leaveConfirmOpen = ref(false);
const leaveBusy = ref(false);

const findRecordOpen = ref(false);

const renameBusy = ref(false);
const renameOpen = ref(false);
const renameDraft = ref("");
const renameInputRef = ref(null);
const avatarBusy = ref(false);
const avatarInputRef = ref(null);
const banBusy = ref(false);

const annLoading = ref(false);
const annText = ref("");
const annVersion = ref(0);
const annViewOpen = ref(false);
const annEditOpen = ref(false);
const annDraft = ref("");
const annSaving = ref(false);

const isGroup = computed(
  () => String(props.row?.conversationType || "").toUpperCase() === "GROUP"
);

const isSingle = computed(() => !isGroup.value);

const conversationBanned = computed(
  () => Boolean(props.row?.conversationBanned ?? props.row?.conversatinoBanned)
);

const selfStr = computed(() =>
  props.selfUserId != null ? String(props.selfUserId) : ""
);

const isGroupOwner = computed(() => {
  if (!isGroup.value || !selfStr.value) return false;
  const hit = members.value.find((m) => String(m.userId) === selfStr.value);
  return Boolean(hit?.isGroupOwner);
});

const convName = computed(() => String(props.row?.conversationName || "").trim() || "未命名会话");

/** 可移除：非群主（后端 isGroupOwner） */
const membersRemovable = computed(() => members.value.filter((m) => !Boolean(m?.isGroupOwner)));

function displayNick(m) {
  const n = String(m?.userName || m?.user_name || "").trim();
  if (!n) return `用户 ${m?.userId ?? ""}`;
  return n.length > 6 ? `${n.slice(0, 6)}…` : n;
}

async function loadMembers() {
  if (!isGroup.value) {
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

async function loadAnnouncement() {
  if (!isGroup.value) {
    annText.value = "";
    annVersion.value = 0;
    return;
  }
  const id = String(props.row?.conversationId || "");
  if (!id) {
    annText.value = "";
    annVersion.value = 0;
    return;
  }
  annLoading.value = true;
  try {
    const data = await chatApi.getAnnouncement(id);
    annText.value = data?.text != null ? String(data.text) : "";
    annVersion.value = Number(data?.version) || 0;
    if (annVersion.value > 0) {
      upsertConversationRow({
        conversationId: id,
        lastAnnouncementVersion: annVersion.value,
      });
    }
  } catch {
    annText.value = "";
    annVersion.value = 0;
  } finally {
    annLoading.value = false;
  }
}

const annRowSummary = computed(() => {
  if (annLoading.value) return "加载中…";
  const t = String(annText.value || "").trim();
  if (!t) return "暂无公告";
  return t.length > 28 ? `${t.slice(0, 28)}…` : t;
});

const showAnnUnread = computed(() => isGroupAnnouncementUnread(props.row));

watch(
  () => props.row?.conversationId,
  () => {
    annViewOpen.value = false;
    annEditOpen.value = false;
    void loadMembers();
    void loadAnnouncement();
  },
  { immediate: true }
);

function onBack() {
  emit("close");
}

async function onMuteToggle() {
  const id = String(props.row?.conversationId || "");
  if (!id) return;
  const next = !props.row?.mute;
  try {
    await toggleMuteRow(props.row, next);
  } catch (e) {
    toast(e?.message || "操作失败", "error");
  }
}

async function onPinToggle() {
  const id = String(props.row?.conversationId || "");
  if (!id) return;
  const next = !props.row?.pin;
  try {
    await togglePinRow(props.row, next);
  } catch (e) {
    toast(e?.message || "操作失败", "error");
  }
}

async function onBanToggle() {
  if (!isGroup.value || !isGroupOwner.value) return;
  const id = String(props.row?.conversationId || "");
  if (!id || banBusy.value) return;
  const next = !conversationBanned.value;
  banBusy.value = true;
  try {
    await toggleConversationBanned(props.row, next);
    toast(next ? "已开启全员禁言" : "已解除全员禁言", "info");
  } catch (e) {
    toast(e?.message || "操作失败", "error");
  } finally {
    banBusy.value = false;
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
  if (conversationBanned.value) return false;
  const uid = m?.userId != null ? String(m.userId) : "";
  if (!uid) return false;
  if (selfStr.value && uid === selfStr.value) return false;
  return !Boolean(m?.banned);
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

function onSearchChatClick() {
  findRecordOpen.value = true;
}

function onFindRecordPick({ seq }) {
  findRecordOpen.value = false;
  const s = Number(seq);
  if (Number.isFinite(s) && s > 0) {
    emit("jump-to-seq", { seq: s });
  }
}

async function openInvite() {
  if (!isGroup.value) return;
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

function openRenameModal() {
  if (!isGroup.value || !isGroupOwner.value) return;
  const id = String(props.row?.conversationId || "");
  if (!id) return;
  renameDraft.value = convName.value;
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
  if (name === convName.value) {
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

function triggerAvatarPick() {
  if (!isGroup.value || !isGroupOwner.value || avatarBusy.value) return;
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

function onMemberAvatarError(ev) {
  const t = ev?.target;
  if (t instanceof HTMLImageElement) {
    t.onerror = null;
    t.src = DEFAULT_PROFILE_AVATAR_URL;
  }
}

function openAnnView() {
  if (!isGroup.value) return;
  annViewOpen.value = true;
}

async function closeAnnView() {
  const cid = String(props.row?.conversationId || "");
  const ver = annVersion.value;
  const hasBody = ver > 0 && String(annText.value || "").trim() !== "";
  annViewOpen.value = false;
  if (!cid || !isGroup.value || !hasBody) return;
  try {
    await chatApi.markAnnouncementRead(cid);
    upsertConversationRow({
      conversationId: cid,
      lastSeenAnnouncementVersion: ver,
    });
  } catch {
    /* 忽略已读同步失败 */
  }
}

function openAnnEditFromView() {
  if (!isGroupOwner.value) return;
  annDraft.value = String(annText.value || "");
  annEditOpen.value = true;
}

function closeAnnEdit() {
  annEditOpen.value = false;
}

async function confirmAnnPublish() {
  const cid = String(props.row?.conversationId || "");
  if (!cid || !isGroupOwner.value) return;
  const raw = String(annDraft.value || "");
  const text = raw.trim();
  if (!text) {
    toast("公告内容不能为空", "info");
    return;
  }
  annSaving.value = true;
  try {
    const res = await chatApi.publishAnnouncement(cid, text);
    const v = Number(res?.version) || 0;
    annText.value = res?.text != null ? String(res.text) : text;
    annVersion.value = v || annVersion.value;
    annEditOpen.value = false;
    toast("已发布公告", "success");
    upsertConversationRow({
      conversationId: cid,
      lastAnnouncementVersion: v,
      lastSeenAnnouncementVersion: v,
    });
    await refreshConversationList(true);
  } catch (e) {
    toast(e?.message || "发布失败", "error");
  } finally {
    annSaving.value = false;
  }
}

const leaveConfirmTitle = computed(() => {
  if (isSingle.value) return "删除私聊会话";
  if (isGroupOwner.value) return "解散群聊";
  return "退出群聊";
});

const leaveConfirmMessage = computed(() => {
  if (isSingle.value) {
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
  const owner = isGroup.value && isGroupOwner.value;
  const single = isSingle.value;
  leaveBusy.value = true;
  try {
    await removeConversationLocal(props.row);
    toast(single ? "已删除会话" : owner ? "已解散群聊" : "已退出群聊", "success");
    leaveConfirmOpen.value = false;
    emit("close");
  } catch (e) {
    toast(e?.message || "操作失败", "error");
  } finally {
    leaveBusy.value = false;
  }
}
</script>

<template>
  <div class="chat-session-detail">
    <header class="chat-session-detail__head">
      <button type="button" class="chat-session-detail__back" aria-label="返回会话列表" @click="onBack">
        <i class="ri-arrow-left-s-line" aria-hidden="true"></i>
      </button>
      <span class="chat-session-detail__head-title">{{ isGroup ? "群聊信息" : "私聊信息" }}</span>
    </header>

    <div class="chat-session-detail__scroll">
      <section v-if="isGroup" class="chat-session-detail__card">
        <p v-if="membersLoading" class="chat-session-detail__hint">加载成员…</p>
        <p v-else-if="membersError" class="chat-session-detail__err">{{ membersError }}</p>
        <div v-else class="chat-session-detail__members">
          <div
            v-for="m in members"
            :key="String(m.userId)"
            class="chat-session-detail__member"
            @contextmenu="onMemberContextMenu(m, $event)"
          >
            <img
              class="chat-session-detail__member-av"
              :src="sanitizeImageUrl(m.avatar) || DEFAULT_PROFILE_AVATAR_URL"
              alt=""
              @error="onMemberAvatarError"
            />
            <span class="chat-session-detail__member-name" :title="String(m.userName || '')">{{
              displayNick(m)
            }}</span>
            <span v-if="m.banned" class="chat-session-detail__owner-tag chat-session-detail__owner-tag--muted"
              >禁言</span
            >
            <span v-if="m.isGroupOwner" class="chat-session-detail__owner-tag">群主</span>
          </div>
          <div v-if="isGroup" class="chat-session-detail__member-actions">
            <button type="button" class="chat-session-detail__member chat-session-detail__member--add" @click="openInvite">
              <span class="chat-session-detail__add-ico" aria-hidden="true">+</span>
              <span class="chat-session-detail__member-name">添加</span>
            </button>
            <button
              v-if="isGroupOwner"
              type="button"
              class="chat-session-detail__member chat-session-detail__member--remove"
              @click="openRemove"
            >
              <span class="chat-session-detail__remove-ico" aria-hidden="true">−</span>
              <span class="chat-session-detail__member-name">移除</span>
            </button>
          </div>
        </div>
      </section>

      <section class="chat-session-detail__card chat-session-detail__rows">
        <button
          v-if="isGroup && isGroupOwner"
          type="button"
          class="chat-session-detail__row chat-session-detail__row--btn"
          :disabled="renameBusy"
          @click="openRenameModal"
        >
          <span class="chat-session-detail__row-k">群聊名称</span>
          <span class="chat-session-detail__row-v">{{ convName }}</span>
          <i class="chat-session-detail__row-chev ri-arrow-right-s-line" aria-hidden="true"></i>
        </button>
        <div v-else-if="isGroup" class="chat-session-detail__row chat-session-detail__row--static">
          <span class="chat-session-detail__row-k">群聊名称</span>
          <span class="chat-session-detail__row-v">{{ convName }}</span>
        </div>
        <button
          v-if="isGroup"
          type="button"
          class="chat-session-detail__row chat-session-detail__row--btn"
          :disabled="annLoading"
          @click="openAnnView"
        >
          <span class="chat-session-detail__row-k">群公告</span>
          <span
            class="chat-session-detail__row-v"
            :class="{ 'chat-session-detail__row-v--muted': !annText.trim() && !annLoading }"
          >
            <span v-if="showAnnUnread" class="chat-session-detail__ann-dot" aria-hidden="true" />
            {{ annRowSummary }}
          </span>
          <i class="chat-session-detail__row-chev ri-arrow-right-s-line" aria-hidden="true"></i>
        </button>
        <div v-if="isGroup && isGroupOwner" class="chat-session-detail__row chat-session-detail__row--static">
          <span class="chat-session-detail__row-k">群头像</span>
          <button
            type="button"
            class="chat-session-detail__ghost-btn chat-session-detail__ghost-btn--inline"
            :disabled="avatarBusy"
            @click="triggerAvatarPick"
          >
            {{ avatarBusy ? "上传中…" : "更换群头像" }}
          </button>
        </div>
        <div v-else-if="isGroup" class="chat-session-detail__row chat-session-detail__row--static">
          <span class="chat-session-detail__row-k">群头像</span>
          <span class="chat-session-detail__row-v chat-session-detail__row-v--muted">仅群主可修改</span>
        </div>
        <input
          ref="avatarInputRef"
          type="file"
          class="chat-session-detail__hidden-file"
          accept="image/*"
          @change="onAvatarFile"
        />

        <div v-if="isSingle" class="chat-session-detail__row chat-session-detail__row--static">
          <span class="chat-session-detail__row-k">会话名称</span>
          <span class="chat-session-detail__row-v">{{ convName }}</span>
        </div>
        <div v-if="isSingle" class="chat-session-detail__row chat-session-detail__row--static">
          <span class="chat-session-detail__row-k">类型</span>
          <span class="chat-session-detail__row-v chat-session-detail__row-v--muted">私聊（双方互相关注）</span>
        </div>
      </section>

      <button type="button" class="chat-session-detail__search-row" @click="onSearchChatClick">
        <span>查找聊天内容</span>
        <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
      </button>

      <section class="chat-session-detail__card chat-session-detail__toggles">
        <label v-if="isGroup && isGroupOwner" class="chat-session-detail__toggle">
          <span>全员禁言</span>
          <input type="checkbox" :checked="conversationBanned" :disabled="banBusy" @change="onBanToggle" />
        </label>
        <label class="chat-session-detail__toggle">
          <span>消息免打扰</span>
          <input type="checkbox" :checked="!!row.mute" @change="onMuteToggle" />
        </label>
        <label class="chat-session-detail__toggle">
          <span>置顶聊天</span>
          <input type="checkbox" :checked="!!row.pin" @change="onPinToggle" />
        </label>
      </section>

      <section class="chat-session-detail__danger">
        <button type="button" class="chat-session-detail__danger-btn" @click="openLeaveConfirm">
          {{ isSingle ? "删除会话" : isGroupOwner ? "解散群聊" : "退出群聊" }}
        </button>
      </section>
    </div>

    <Teleport to="body">
      <div
        v-if="memberMenuOpen"
        class="chat-member-menu"
        role="menu"
        :style="{ left: `${memberMenuX}px`, top: `${memberMenuY}px` }"
      >
        <button
          type="button"
          class="chat-member-menu__btn"
          :disabled="memberMenuBusy || conversationBanned"
          @click="onMemberBanClick"
        >
          {{ memberMenuBusy ? "禁言中…" : "禁言" }}
        </button>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="inviteOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeInvite"
      >
        <div class="modal-card chat-session-invite" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-session-invite__title">邀请互关好友入群</h3>
          <p v-if="inviteLoading" class="chat-session-invite__hint chat-session-invite__hint--block">加载中…</p>
          <div v-else class="chat-session-invite__list">
            <p v-if="!mutualUsers.length" class="chat-session-invite__hint chat-session-invite__hint--empty">
              没有可邀请的互关成员（或已全部在群内）
            </p>
            <button
              v-for="u in mutualUsers"
              :key="String(u.userId ?? u.id)"
              type="button"
              class="chat-session-invite__row"
              :class="{ 'chat-session-invite__row--on': invitePickId === String(u.userId ?? u.id) }"
              @click="pickInviteUser(u)"
            >
              <img class="chat-session-invite__av" :src="userPickAvatar(u)" alt="" />
              <span class="chat-session-invite__nick">{{ userPickLabel(u) }}</span>
            </button>
          </div>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" @click="closeInvite">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--primary"
              :disabled="inviteSubmitting || !invitePickId"
              @click="confirmInvite"
            >
              {{ inviteSubmitting ? "邀请中…" : "邀请" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="removeOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeRemove"
      >
        <div class="modal-card chat-session-invite" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-session-invite__title">移除群成员</h3>
          <p class="chat-session-invite__hint">仅群主可操作；以下不包含群主本人，可多选后批量移除。</p>
          <div class="chat-session-invite__list">
            <p v-if="!membersRemovable.length" class="chat-session-invite__hint chat-session-invite__hint--empty">
              暂无可移除成员
            </p>
            <button
              v-for="m in membersRemovable"
              :key="String(m.userId)"
              type="button"
              class="chat-session-invite__row"
              :class="{ 'chat-session-invite__row--on': isRemovePicked(m.userId) }"
              @click="toggleRemovePick(m.userId)"
            >
              <span class="chat-session-invite__chk" aria-hidden="true">
                <i
                  :class="isRemovePicked(m.userId) ? 'ri-checkbox-fill' : 'ri-checkbox-blank-line'"
                />
              </span>
              <img
                class="chat-session-invite__av"
                :src="sanitizeImageUrl(m.avatar) || DEFAULT_PROFILE_AVATAR_URL"
                alt=""
                @error="onMemberAvatarError"
              />
              <span class="chat-session-invite__nick">{{ displayNick(m) }}</span>
            </button>
          </div>
          <p v-if="removePickIds.length" class="chat-session-remove__picked">已选 {{ removePickIds.length }} 人</p>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" @click="closeRemove">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--danger"
              :disabled="removeSubmitting || !removePickIds.length"
              @click="openRemoveBulkConfirm"
            >
              {{ removeSubmitting ? "移除中…" : "移除" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="removeBulkConfirmOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeRemoveBulkConfirm"
      >
        <div class="modal-card chat-session-invite" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-session-invite__title">移出成员</h3>
          <p class="chat-session-invite__hint">
            确定将已选的 {{ removePickIds.length }} 位成员移出群聊？移出后对方会话列表中将不再显示本群。
          </p>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" @click="closeRemoveBulkConfirm">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--danger"
              :disabled="removeSubmitting"
              @click="executeRemoveMembers"
            >
              {{ removeSubmitting ? "移除中…" : "确定移除" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="leaveConfirmOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeLeaveConfirm"
      >
        <div class="modal-card chat-session-invite" role="dialog" aria-modal="true" aria-labelledby="chat-leave-confirm-title" @click.stop>
          <h3 id="chat-leave-confirm-title" class="chat-session-invite__title">{{ leaveConfirmTitle }}</h3>
          <p class="chat-session-invite__hint">{{ leaveConfirmMessage }}</p>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" :disabled="leaveBusy" @click="closeLeaveConfirm">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--danger"
              :disabled="leaveBusy"
              @click="submitLeaveConfirm"
            >
              {{ leaveBusy ? "处理中…" : "确定" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="renameOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeRenameModal"
      >
        <div class="modal-card chat-session-invite" role="dialog" aria-modal="true" aria-labelledby="chat-rename-title" @click.stop>
          <h3 id="chat-rename-title" class="chat-session-invite__title">修改群名称</h3>
          <p class="chat-session-invite__hint">请输入新的群聊名称</p>
          <div class="chat-session-rename__field">
            <input
              ref="renameInputRef"
              v-model="renameDraft"
              type="text"
              class="chat-session-rename__input"
              maxlength="64"
              placeholder="群聊名称"
              autocomplete="off"
              @keydown.enter.prevent="confirmRename"
            />
          </div>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" @click="closeRenameModal">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--primary"
              :disabled="renameBusy"
              @click="confirmRename"
            >
              {{ renameBusy ? "保存中…" : "确定" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="annViewOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeAnnView"
      >
        <div class="modal-card chat-session-invite chat-session-ann-card" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-session-invite__title">群公告</h3>
          <p v-if="annLoading" class="chat-session-invite__hint chat-session-invite__hint--block">加载中…</p>
          <div v-else class="chat-session-ann__body">
            <p v-if="!String(annText || '').trim()" class="chat-session-invite__hint">暂无公告</p>
            <p v-else class="chat-session-ann__text">{{ annText }}</p>
          </div>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" @click="closeAnnView">
              关闭
            </button>
            <button
              v-if="isGroupOwner"
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--primary"
              @click="openAnnEditFromView"
            >
              {{ String(annText || '').trim() ? "编辑公告" : "发布公告" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="annEditOpen"
        class="modal-overlay modal-overlay--light chat-session-invite-overlay"
        role="presentation"
        @click.self="closeAnnEdit"
      >
        <div class="modal-card chat-session-invite chat-session-ann-card" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-session-invite__title">编辑群公告</h3>
          <p class="chat-session-invite__hint">仅群主可见；保存后群内成员将收到更新提示。</p>
          <div class="chat-session-ann__field">
            <textarea
              v-model="annDraft"
              class="chat-session-ann__textarea"
              rows="8"
              maxlength="4000"
              placeholder="输入公告正文…"
              autocomplete="off"
            />
          </div>
          <div class="chat-session-invite__actions">
            <button type="button" class="chat-session-invite__btn chat-session-invite__btn--ghost" :disabled="annSaving" @click="closeAnnEdit">
              取消
            </button>
            <button
              type="button"
              class="chat-session-invite__btn chat-session-invite__btn--primary"
              :disabled="annSaving"
              @click="confirmAnnPublish"
            >
              {{ annSaving ? "发布中…" : "发布" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <ChatFindRecordModal
      v-model="findRecordOpen"
      :conversation-id="String(row?.conversationId || '')"
      :display-name="convName"
      @pick="onFindRecordPick"
    />
  </div>
</template>
