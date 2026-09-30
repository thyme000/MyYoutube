<script setup>
import { ref, watch } from "vue";
import BaseModal from "../ui/BaseModal.vue";
import {
  toggleMuteRow,
  togglePinRow,
  toggleConversationBanned,
} from "../../stores/chatStore.js";
import { toast } from "../../utils/toast.js";
import ChatFindRecordModal from "./ChatFindRecordModal.vue";
import { useChatSessionMeta } from "../../composables/chat/useChatSessionMeta.js";
import { useChatSessionRoster } from "../../composables/chat/useChatSessionRoster.js";
import { useChatGroupAdmin } from "../../composables/chat/useChatGroupAdmin.js";
import { useChatSessionAnnouncement } from "../../composables/chat/useChatSessionAnnouncement.js";
import { useChatSessionLeave } from "../../composables/chat/useChatSessionLeave.js";

const props = defineProps({
  row: { type: Object, required: true },
  selfUserId: { type: [String, Number], default: null },
});

const emit = defineEmits(["close", "jump-to-seq", "open-user"]);

const { isGroup, isSingle, conversationBanned, selfStr, convName } = useChatSessionMeta(props);
const meta = { isGroup, isSingle, conversationBanned, selfStr, convName };

const {
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
} = useChatSessionRoster(props, meta, {
  onOpenUser: (userId) => emit("open-user", { userId }),
});

const {
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
} = useChatGroupAdmin(props, meta, { members, isGroupOwner, loadMembers });

const {
  annLoading,
  annText,
  annViewOpen,
  annEditOpen,
  annDraft,
  annSaving,
  annRowSummary,
  showAnnUnread,
  loadAnnouncement,
  openAnnView,
  closeAnnView,
  openAnnEditFromView,
  closeAnnEdit,
  confirmAnnPublish,
} = useChatSessionAnnouncement(props, meta);

const {
  leaveConfirmOpen,
  leaveBusy,
  leaveConfirmTitle,
  leaveConfirmMessage,
  openLeaveConfirm,
  closeLeaveConfirm,
  submitLeaveConfirm,
} = useChatSessionLeave(props, meta, { isGroupOwner, onClose: () => emit("close") });

const banBusy = ref(false);
const findRecordOpen = ref(false);

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
              role="button"
              tabindex="0"
              @error="onMemberAvatarError"
              @click.stop="onMemberAvatarClick(m)"
              @keydown.enter.prevent="onMemberAvatarClick(m)"
              @keydown.space.prevent="onMemberAvatarClick(m)"
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

        <BaseModal
      :open="inviteOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite"
      @close="closeInvite"
    >
          <h3 class="chat-session-invite__title">邀请互关好友入群</h3>
          <p v-if="inviteLoading" class="chat-session-invite__hint chat-session-invite__hint--block">加载中…</p>
          <div v-else class="chat-session-invite__list">
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
    </BaseModal>

        <BaseModal
      :open="removeOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite"
      @close="closeRemove"
    >
          <h3 class="chat-session-invite__title">移除群成员</h3>
          <p class="chat-session-invite__hint">仅群主可操作；以下不包含群主本人，可多选后批量移除。</p>
          <div class="chat-session-invite__list">
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
    </BaseModal>

        <BaseModal
      :open="removeBulkConfirmOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite"
      @close="closeRemoveBulkConfirm"
    >
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
    </BaseModal>

        <BaseModal
      :open="leaveConfirmOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite"
      aria-labelledby="chat-leave-confirm-title"
      @close="closeLeaveConfirm"
    >
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
    </BaseModal>

        <BaseModal
      :open="renameOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite"
      aria-labelledby="chat-rename-title"
      @close="closeRenameModal"
    >
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
    </BaseModal>

        <BaseModal
      :open="annViewOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite chat-session-ann-card"
      @close="closeAnnView"
    >
          <h3 class="chat-session-invite__title">群公告</h3>
          <p v-if="annLoading" class="chat-session-invite__hint chat-session-invite__hint--block">加载中…</p>
          <div v-else class="chat-session-ann__body">
            <p v-if="String(annText || '').trim()" class="chat-session-ann__text">{{ annText }}</p>
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
    </BaseModal>

        <BaseModal
      :open="annEditOpen"
      variant="light"
      overlay-class="chat-session-invite-overlay"
      card-class="modal-card chat-session-invite chat-session-ann-card"
      @close="closeAnnEdit"
    >
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
    </BaseModal>

    <ChatFindRecordModal
      v-model="findRecordOpen"
      :conversation-id="String(row?.conversationId || '')"
      :display-name="convName"
      @pick="onFindRecordPick"
    />
  </div>
</template>

<style scoped>
/* 会话详情侧栏（点击消息区头像打开，替代会话列表区域） */
.chat-session-detail {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-hover);
}

.chat-session-detail__head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-8);
  padding: var(--space-10) var(--space-12);
  background: var(--color-bg-surface);
  border-bottom: 1px solid var(--color-border);
}

.chat-session-detail__back {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--radius-lg);
  background: transparent;
  color: var(--color-text-strong);
  font-size: var(--font-size-22);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.chat-session-detail__back:hover {
  background: var(--color-bg-hover);
}

.chat-session-detail__head-title {
  font-size: var(--font-size-16);
  font-weight: 600;
  color: var(--color-text-strong);
}

.chat-session-detail__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-10) var(--space-10) var(--space-20);
  display: flex;
  flex-direction: column;
  gap: var(--space-10);
}

.chat-session-detail__card {
  background: var(--color-bg-surface);
  border-radius: var(--radius-xl);
  padding: var(--space-12);
  box-shadow: 0 1px 0 rgb(var(--slate-900-rgb) / 0.04);
}

.chat-session-detail__hint,
.chat-session-detail__err {
  margin: 0;
  font-size: var(--font-size-13);
  color: var(--color-text-muted);
}

.chat-session-detail__err {
  color: var(--red-700);
}

.chat-session-detail__members {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-14) var(--space-12);
}

.chat-session-detail__member {
  width: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  border: none;
  padding: 0;
  background: transparent;
  cursor: default;
  text-align: center;
}

.chat-session-detail__member--add {
  cursor: pointer;
  color: var(--color-text-muted);
}

.chat-session-detail__member--add:hover {
  color: var(--color-text-strong);
}

.chat-session-detail__member-av {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  object-fit: cover;
  background: var(--slate-200);
}

.chat-session-detail__add-ico {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  border: 1px dashed var(--color-border-strong);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-22);
  line-height: 1;
  background: var(--color-bg-sunken);
}

.chat-session-detail__member-actions {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: var(--space-12) var(--space-10);
  align-items: flex-start;
}

.chat-session-detail__member--remove {
  cursor: pointer;
  color: var(--color-text-muted);
}

.chat-session-detail__member--remove:hover {
  color: var(--red-700);
}

.chat-session-detail__remove-ico {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  border: 1px dashed var(--red-300);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-22);
  line-height: 1;
  background: rgb(var(--white-rgb) / 0.066)f2;
  color: var(--red-600);
}

.chat-session-detail__member-name {
  font-size: var(--font-size-12);
  color: var(--color-text-secondary);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-session-detail__owner-tag {
  font-size: var(--font-size-10);
  color: var(--color-text-inverse);
  background: var(--wechat);
  border-radius: var(--radius-sm);
  padding: 1px var(--space-4);
  line-height: 1.2;
}

.chat-session-detail__owner-tag--muted {
  background: var(--red-500);
}

.chat-member-menu {
  position: fixed;
  min-width: 132px;
  padding: var(--space-6);
  border-radius: var(--radius-2xl);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  z-index: 12100;
}

.chat-member-menu__btn {
  border: 1px solid var(--color-border);
  background: var(--color-bg-sunken);
  color: var(--color-text-strong);
  border-radius: var(--radius-xl);
  padding: var(--space-8) var(--space-10);
  font-size: var(--font-size-13);
  line-height: 1.2;
  cursor: pointer;
  text-align: left;
}

.chat-member-menu__btn:hover:not(:disabled) {
  background: var(--color-bg-hover);
}

.chat-member-menu__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.chat-session-detail__rows {
  padding: 0;
  overflow: hidden;
}

.chat-session-detail__row {
  display: flex;
  align-items: center;
  gap: var(--space-8);
  padding: var(--space-12) var(--space-14);
  border-bottom: 1px solid var(--slate-100);
  width: 100%;
  box-sizing: border-box;
  text-align: left;
}

.chat-session-detail__row:last-child {
  border-bottom: none;
}

.chat-session-detail__row--static {
  cursor: default;
}

.chat-session-detail__row--btn {
  border: none;
  background: var(--color-bg-surface);
  cursor: pointer;
  font: inherit;
}

.chat-session-detail__row--btn:hover {
  background: var(--color-bg-sunken);
}

.chat-session-detail__row--btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-session-detail__row-k {
  flex-shrink: 0;
  color: var(--color-text-strong);
  font-size: var(--font-size-15);
}

.chat-session-detail__row-v {
  flex: 1;
  min-width: 0;
  text-align: right;
  font-size: var(--font-size-14);
  color: var(--color-text-muted);
}

.chat-session-detail__row-v--muted {
  color: var(--color-text-faint);
}

.chat-session-detail__ann-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--orange-500);
  margin-right: var(--space-6);
  vertical-align: middle;
  flex-shrink: 0;
}

.chat-session-ann-card {
  max-width: min(420px, calc(100vw - 32px));
}

.chat-session-ann__body {
  max-height: min(52vh, 360px);
  overflow-y: auto;
  margin: 0 0 var(--space-4);
}

.chat-session-ann__text {
  margin: 0;
  font-size: var(--font-size-14);
  line-height: 1.55;
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-session-ann__field {
  margin: var(--space-8) 0 var(--space-4);
}

.chat-session-ann__textarea {
  width: 100%;
  min-height: 160px;
  padding: var(--space-10) var(--space-12);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-14);
  line-height: 1.5;
  color: var(--color-text-strong);
  resize: vertical;
  box-sizing: border-box;
  font-family: inherit;
}

.chat-session-ann__textarea:focus {
  outline: none;
  border-color: var(--slate-400);
  box-shadow: 0 0 0 3px rgb(var(--slate-400-rgb) / 0.25);
}

.chat-session-detail__row-chev {
  flex-shrink: 0;
  color: var(--color-text-disabled);
  font-size: var(--font-size-18);
}

.chat-session-detail__ghost-btn {
  margin: var(--space-8) var(--space-14) var(--space-12);
  padding: var(--space-8) var(--space-12);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-surface);
  font-size: var(--font-size-14);
  color: var(--color-text-secondary);
  cursor: pointer;
}

/** 群头像行右侧：与「群聊名称」行右侧 value 对齐 */
.chat-session-detail__ghost-btn--inline {
  margin: 0;
  margin-left: auto;
  padding: var(--space-6) var(--space-12);
  font-size: var(--font-size-14);
  flex-shrink: 0;
}

.chat-session-detail__ghost-btn:hover:not(:disabled) {
  background: var(--color-bg-sunken);
}

.chat-session-detail__hidden-file {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.chat-session-detail__search-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: var(--space-12) var(--space-14);
  border: none;
  border-radius: var(--radius-xl);
  background: var(--color-bg-surface);
  font-size: var(--font-size-15);
  color: var(--color-text-strong);
  cursor: pointer;
  box-shadow: 0 1px 0 rgb(var(--slate-900-rgb) / 0.04);
}

.chat-session-detail__search-row:hover {
  background: var(--color-bg-sunken);
}

.chat-session-detail__search-row i {
  color: var(--color-text-disabled);
  font-size: var(--font-size-18);
}

.chat-session-detail__toggles {
  padding: var(--space-4) 0;
}

.chat-session-detail__toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-10) var(--space-14);
  border-bottom: 1px solid var(--slate-100);
  font-size: var(--font-size-15);
  color: var(--color-text-strong);
  cursor: pointer;
}

.chat-session-detail__toggle:last-child {
  border-bottom: none;
}

.chat-session-detail__toggle input {
  width: 44px;
  height: 26px;
  accent-color: var(--wechat);
  cursor: pointer;
}

.chat-session-detail__danger {
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
  padding: var(--space-8) 0 0;
}

.chat-session-detail__danger-btn {
  border: none;
  background: transparent;
  color: var(--red-500);
  font-size: var(--font-size-15);
  padding: var(--space-12);
  cursor: pointer;
  border-radius: var(--radius-lg);
}

.chat-session-detail__danger-btn:hover {
  background: rgb(var(--red-500-rgb) / 0.06);
}

.chat-session-invite-overlay {
  z-index: 12050;
}

.chat-session-invite__hint {
  margin: 0 0 var(--space-12);
  font-size: var(--font-size-13);
  color: var(--color-text-muted);
  line-height: 1.45;
}

.chat-session-invite__hint--block {
  margin-bottom: 0;
  padding: var(--space-16) var(--space-8);
  text-align: center;
}

.chat-session-invite__list {
  flex: 1;
  min-height: 120px;
  max-height: min(42vh, 320px);
  overflow-y: auto;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-xl);
  background: var(--color-bg-sunken);
  margin-bottom: var(--space-16);
}

.chat-session-invite__row {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--space-10);
  width: 100%;
  padding: var(--space-10) var(--space-12);
  border: none;
  border-bottom: 1px solid var(--color-border-subtle);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--color-text-strong);
  box-sizing: border-box;
}

.chat-session-invite__row:last-child {
  border-bottom: none;
}

.chat-session-invite__row:hover {
  background: var(--color-bg-hover);
}

.chat-session-invite__row--on {
  background: var(--sky-100);
}

.chat-session-invite__av {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  object-fit: cover;
  flex-shrink: 0;
  background: var(--slate-200);
}

.chat-session-invite__nick {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-15);
  font-weight: 500;
}

.chat-session-invite__actions {
  margin-top: auto;
  padding-top: var(--space-4);
  display: flex;
  justify-content: flex-end;
  gap: var(--space-10);
  flex-shrink: 0;
}

.chat-session-invite__btn {
  height: 38px;
  padding: 0 var(--space-18);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-14);
  font-weight: 600;
  cursor: pointer;
  border: none;
}

.chat-session-invite__btn--ghost {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.chat-session-invite__btn--ghost:hover {
  background: var(--slate-200);
}

.chat-session-invite__btn--primary {
  background: var(--sky-500);
  color: var(--color-text-inverse);
}

.chat-session-invite__btn--primary:hover:not(:disabled) {
  background: var(--sky-600);
}

.chat-session-invite__btn--primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-session-rename__field {
  margin: 0 0 var(--space-4);
}

.chat-session-rename__input {
  width: 100%;
  box-sizing: border-box;
  height: 42px;
  padding: 0 var(--space-14);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  font-size: var(--font-size-15);
  color: var(--color-text-strong);
  background: var(--color-bg-surface);
}

.chat-session-rename__input::placeholder {
  color: var(--color-text-faint);
}

.chat-session-rename__input:focus {
  outline: none;
  border-color: var(--sky-500);
  box-shadow: 0 0 0 3px rgb(var(--sky-500-rgb) / 0.18);
}

.chat-session-invite__btn--danger {
  background: var(--red-500);
  color: var(--color-text-inverse);
}

.chat-session-invite__btn--danger:hover:not(:disabled) {
  background: var(--red-600);
}

.chat-session-invite__btn--danger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-session-remove__picked {
  margin: -6px 0 var(--space-14);
  font-size: var(--font-size-13);
  color: var(--color-text-secondary);
  font-weight: 500;
}
</style>
