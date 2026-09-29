<script setup>
import { ref, computed } from "vue";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { fetchInterrelatedPage } from "../../api/followApi.js";
import {
  startSingleChat,
  startGroupChat,
  scheduleAfterChatMediaLoadError,
  isGroupAnnouncementUnread,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";

const props = defineProps({
  items: { type: Array, default: () => [] },
  activeId: { type: String, default: null },
  loading: { type: Boolean, default: false },
  /** 悬浮条里略窄 */
  compact: { type: Boolean, default: false },
});

const emit = defineEmits(["select", "mute", "pin", "load-more"]);

function onRowClick(row) {
  emit("select", String(row.conversationId || ""));
}

function rowPreview(row) {
  if (row?.isLastMessageRecall) return "撤回了一条消息";
  const nick = row?.lastMessageSenderNickname;
  const body = row?.lastMessageContent || "";
  const isGroup = String(row?.conversationType || "").toUpperCase() === "GROUP";
  if (isGroup && nick) return `${nick}: ${body}`;
  return body || " ";
}

function formatTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return "";
  const h = d.getHours();
  const m = d.getMinutes();
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function avatarSrc(row) {
  const u = sanitizeImageUrl(row?.conversationAvatar);
  return u || DEFAULT_PROFILE_AVATAR_URL;
}

function onConvAvatarError(ev) {
  const el = ev?.target;
  if (el instanceof HTMLImageElement) {
    el.onerror = null;
    el.src = DEFAULT_PROFILE_AVATAR_URL;
    el.style.visibility = "visible";
    el.style.opacity = "1";
  }
  scheduleAfterChatMediaLoadError();
}

function onPickAvatarError(ev) {
  const el = ev?.target;
  if (el instanceof HTMLImageElement) {
    el.onerror = null;
    el.src = DEFAULT_PROFILE_AVATAR_URL;
  }
}

const sortedItems = computed(() => [...(props.items || [])]);

function onMute(ev, row) {
  ev.stopPropagation();
  emit("mute", row, !row.mute);
}

function onPin(ev, row) {
  ev.stopPropagation();
  emit("pin", row, !row.pin);
}

/** --- 创建私聊 / 群聊：互关列表 --- */
const pickOpen = ref(false);
const pickMode = ref("single");
const pickLoading = ref(false);
const pickSubmitting = ref(false);
const mutualUsers = ref([]);
const pickSingleId = ref(null);
const pickGroupIds = ref(/** @type {Set<string>} */ (new Set()));
const groupNameDraft = ref("群聊");

function resetPicker() {
  pickSingleId.value = null;
  pickGroupIds.value = new Set();
  groupNameDraft.value = "群聊";
  mutualUsers.value = [];
}

function closePicker() {
  pickOpen.value = false;
  resetPicker();
}

async function fetchAllInterrelated() {
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
  return all;
}

function userRowId(u) {
  const id = u?.userId ?? u?.id;
  return id != null && id !== "" ? String(id) : "";
}

function userNick(u) {
  return String(u?.nickname || u?.nickName || "用户").trim() || "用户";
}

function userAvatar(u) {
  const s = sanitizeImageUrl(u?.avatarUrl);
  return s || DEFAULT_PROFILE_AVATAR_URL;
}

async function openPicker(mode) {
  pickMode.value = mode === "group" ? "group" : "single";
  resetPicker();
  pickOpen.value = true;
  pickLoading.value = true;
  try {
    mutualUsers.value = await fetchAllInterrelated();
  } catch (e) {
    toast(e?.message || "加载互关列表失败", "error");
    mutualUsers.value = [];
  } finally {
    pickLoading.value = false;
  }
}

function onPickRowClick(u) {
  const id = userRowId(u);
  if (!id) return;
  if (pickMode.value === "single") {
    pickSingleId.value = pickSingleId.value === id ? null : id;
    return;
  }
  const next = new Set(pickGroupIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  pickGroupIds.value = next;
}

function pickRowActive(u) {
  const id = userRowId(u);
  if (!id) return false;
  if (pickMode.value === "single") return pickSingleId.value === id;
  return pickGroupIds.value.has(id);
}

async function confirmPicker() {
  if (pickMode.value === "single") {
    const id = pickSingleId.value;
    if (!id) {
      toast("请选择一位互关好友", "info");
      return;
    }
    const u = mutualUsers.value.find((x) => userRowId(x) === id);
    pickSubmitting.value = true;
    try {
      await startSingleChat(id, {
        name: u ? userNick(u) : undefined,
        avatar: u ? userAvatar(u) || undefined : undefined,
      });
      closePicker();
    } catch (e) {
      toast(e?.message || "创建失败", "error");
    } finally {
      pickSubmitting.value = false;
    }
    return;
  }
  const ids = Array.from(pickGroupIds.value);
  if (!ids.length) {
    toast("请至少选择一名互关好友", "info");
    return;
  }
  const name = String(groupNameDraft.value || "").trim() || "群聊";
  pickSubmitting.value = true;
  try {
    await startGroupChat(ids, { name });
    closePicker();
  } catch (e) {
    toast(e?.message || "创建失败", "error");
  } finally {
    pickSubmitting.value = false;
  }
}
</script>

<template>
  <div class="chat-conv-list" :class="{ 'chat-conv-list--compact': compact }">
    <div v-if="loading && !sortedItems.length" class="chat-conv-list__hint">加载中…</div>
    <button
      v-for="row in sortedItems"
      :key="String(row.conversationId)"
      type="button"
      class="chat-conv-row"
      :class="{ 'chat-conv-row--active': activeId === String(row.conversationId) }"
      @click="onRowClick(row)"
    >
      <div class="chat-conv-row__avatar-wrap">
        <img :src="avatarSrc(row)" alt="" class="chat-conv-row__avatar" @error="onConvAvatarError" />
        <span v-if="(row.unreadCount ?? 0) > 0" class="chat-conv-row__badge">
          {{ (row.unreadCount ?? 0) > 99 ? "99+" : row.unreadCount }}
        </span>
        <span
          v-if="isGroupAnnouncementUnread(row)"
          class="chat-conv-row__ann-dot"
          title="有新的群公告"
          aria-hidden="true"
        />
      </div>
      <div class="chat-conv-row__body">
        <div class="chat-conv-row__top">
          <span class="chat-conv-row__name">{{ row.conversationName || "未命名会话" }}</span>
          <time class="chat-conv-row__time">{{ formatTime(row.lastMessageTime) }}</time>
        </div>
        <p class="chat-conv-row__preview">{{ rowPreview(row) }}</p>
      </div>
      <div v-if="!compact" class="chat-conv-row__ops">
        <button type="button" class="chat-conv-ico" :title="row.pin ? '取消置顶' : '置顶'" @click="(e) => onPin(e, row)">
          <i :class="row.pin ? 'ri-pushpin-fill' : 'ri-pushpin-line'"></i>
        </button>
        <button type="button" class="chat-conv-ico" :title="row.mute ? '关闭免打扰' : '免打扰'" @click="(e) => onMute(e, row)">
          <i :class="row.mute ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
        </button>
      </div>
    </button>
    <div v-if="sortedItems.length && !loading" class="chat-conv-list__footer">
      <button type="button" class="chat-conv-more" @click="emit('load-more')">加载更多会话</button>
    </div>
    <div v-if="!loading" class="chat-conv-list__footer chat-conv-list__footer--stack">
      <button type="button" class="chat-conv-more" @click="openPicker('single')">创建私聊</button>
      <button type="button" class="chat-conv-more" @click="openPicker('group')">创建群聊</button>
    </div>
    <p v-if="!sortedItems.length && !loading" class="chat-conv-list__empty">暂无会话</p>

    <Teleport to="body">
      <div
        v-if="pickOpen"
        class="modal-overlay modal-overlay--light chat-mutual-pick-overlay"
        role="presentation"
        @click.self="closePicker"
      >
        <div class="modal-card chat-mutual-pick" role="dialog" aria-modal="true" @click.stop>
          <h3 class="chat-mutual-pick__title">{{ pickMode === "single" ? "创建私聊" : "创建群聊" }}</h3>
          <p class="chat-mutual-pick__hint">
            {{ pickMode === "single" ? "从互关好友中选 1 人" : "从互关好友中选择多人（可多选）" }}
          </p>
          <div v-if="pickMode === 'group'" class="chat-mutual-pick__field">
            <label class="chat-mutual-pick__label" for="chat-mutual-group-name">群名称</label>
            <input
              id="chat-mutual-group-name"
              v-model="groupNameDraft"
              type="text"
              class="chat-mutual-pick__input"
              maxlength="32"
              placeholder="群聊"
            />
          </div>
          <div class="chat-mutual-pick__list">
            <p v-if="pickLoading" class="chat-mutual-pick__list-hint">加载互关列表…</p>
            <p v-else-if="!mutualUsers.length" class="chat-mutual-pick__list-hint">暂无互关用户，互关后才可发起聊天</p>
            <template v-else>
              <button
                v-for="u in mutualUsers"
                :key="userRowId(u)"
                type="button"
                class="chat-mutual-pick__row"
                :class="{ 'chat-mutual-pick__row--on': pickRowActive(u) }"
                @click="onPickRowClick(u)"
              >
                <span class="chat-mutual-pick__check" aria-hidden="true">
                  <i v-if="pickMode === 'single'" :class="pickRowActive(u) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i>
                  <i v-else :class="pickRowActive(u) ? 'ri-checkbox-fill' : 'ri-checkbox-blank-line'"></i>
                </span>
                <img class="chat-mutual-pick__av" :src="userAvatar(u)" alt="" @error="onPickAvatarError" />
                <span class="chat-mutual-pick__nick">{{ userNick(u) }}</span>
              </button>
            </template>
          </div>
          <div class="chat-mutual-pick__actions">
            <button type="button" class="chat-mutual-pick__btn chat-mutual-pick__btn--ghost" :disabled="pickSubmitting" @click="closePicker">
              取消
            </button>
            <button type="button" class="chat-mutual-pick__btn chat-mutual-pick__btn--primary" :disabled="pickSubmitting" @click="confirmPicker">
              {{ pickSubmitting ? "创建中…" : "创建" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.chat-conv-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.chat-conv-list--compact .chat-conv-row__ops {
  display: none;
}

.chat-conv-row {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  gap: 10px;
  padding: 10px 12px;
  border: none;
  border-bottom: 1px solid #eef2f7;
  background: #fff;
  text-align: left;
  cursor: pointer;
  width: 100%;
  box-sizing: border-box;
}

.chat-conv-row:hover {
  background: #f8fafc;
}

.chat-conv-row--active {
  background: #07c160;
  color: #fff;
}

.chat-conv-row--active:hover {
  background: #06ad56;
}

.chat-conv-row--active .chat-conv-row__preview,
.chat-conv-row--active .chat-conv-row__time,
.chat-conv-row--active .chat-conv-row__name {
  color: #fff;
}

.chat-conv-row--active .chat-conv-row__preview {
  opacity: 0.92;
}

.chat-conv-row__avatar-wrap {
  position: relative;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
}

.chat-conv-list--compact .chat-conv-row__avatar-wrap {
  width: 40px;
  height: 40px;
}

.chat-conv-row__avatar {
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
  background: #e2e8f0;
}

.chat-conv-row__badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  box-sizing: border-box;
}

.chat-conv-row__ann-dot {
  position: absolute;
  bottom: -2px;
  left: -2px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #f97316;
  border: 2px solid #fff;
  box-sizing: border-box;
  box-shadow: 0 0 0 1px rgba(249, 115, 22, 0.35);
}

.chat-conv-row--active .chat-conv-row__ann-dot {
  border-color: rgba(30, 41, 59, 0.95);
  box-shadow: none;
}

.chat-conv-row__body {
  flex: 1;
  min-width: 0;
}

.chat-conv-row__top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}

.chat-conv-row__name {
  font-weight: 600;
  font-size: 15px;
  color: #0f172a;
}

.chat-conv-row__time {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
}

.chat-conv-row__preview {
  margin: 4px 0 0;
  font-size: 13px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-conv-row__ops {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
  flex-shrink: 0;
}

.chat-conv-ico {
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  font-size: 16px;
}

.chat-conv-ico:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.chat-conv-list__hint,
.chat-conv-list__empty {
  padding: 24px 12px;
  text-align: center;
  color: #94a3b8;
  font-size: 14px;
}

.chat-conv-list__footer {
  padding: 8px;
}

.chat-conv-list__footer--stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 0;
}

.chat-conv-more {
  width: 100%;
  padding: 8px;
  border-radius: 10px;
  border: 1px dashed #cbd5e1;
  background: #fff;
  cursor: pointer;
  color: #475569;
}

.chat-mutual-pick-overlay {
  z-index: 2410;
}

.chat-mutual-pick__hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: #64748b;
  line-height: 1.45;
}

.chat-mutual-pick__field {
  margin-bottom: 12px;
}

.chat-mutual-pick__label {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.chat-mutual-pick__input {
  width: 100%;
  box-sizing: border-box;
  height: 40px;
  padding: 0 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  font-size: 15px;
}

.chat-mutual-pick__list {
  flex: 1;
  min-height: 120px;
  max-height: min(42vh, 320px);
  overflow-y: auto;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  background: #fafbfc;
}

.chat-mutual-pick__list-hint {
  margin: 0;
  padding: 20px 14px;
  text-align: center;
  color: #94a3b8;
  font-size: 14px;
}

.chat-mutual-pick__row {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-bottom: 1px solid #eef2f7;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: #0f172a;
  box-sizing: border-box;
}

.chat-mutual-pick__row:last-child {
  border-bottom: none;
}

.chat-mutual-pick__row:hover {
  background: #f1f5f9;
}

.chat-mutual-pick__row--on {
  background: #e0f2fe;
}

.chat-mutual-pick__check {
  flex-shrink: 0;
  width: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #0ea5e9;
}

.chat-mutual-pick__av {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
  background: #e2e8f0;
}

.chat-mutual-pick__nick {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
}

.chat-mutual-pick__actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-shrink: 0;
}

.chat-mutual-pick__btn {
  height: 38px;
  padding: 0 18px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border: none;
}

.chat-mutual-pick__btn--ghost {
  background: #f1f5f9;
  color: #475569;
}

.chat-mutual-pick__btn--primary {
  background: #0ea5e9;
  color: #fff;
}

.chat-mutual-pick__btn:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}
</style>
