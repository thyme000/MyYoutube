<script setup>
import { storeToRefs } from "pinia";
import { useNotificationStore } from "../stores/notificationStore";
import { applyAvatarFallback, DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile";

const notifyStore = useNotificationStore();
const {
  notifyPanelOpen,
  notifyUnreadCount,
  notifyList,
  notifyLoading,
  notifyPageNo,
  notifyPages,
  notifyTotal,
  notifyErrorMsg,
} = storeToRefs(notifyStore);
const {
  toggleNotifyPanel,
  closeNotifyPanel,
  loadMoreNotifications,
  onNotifyItemClick,
  onNotifyDelete,
} = notifyStore;

const onTopbarAvatarError = (evt) => applyAvatarFallback(evt, profileAvatar);

function formatNotifyTime(t) {
  if (!t) return "";
  try {
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return String(t);
    return d.toLocaleString();
  } catch {
    return String(t);
  }
}
</script>

<template>
  <div class="notify-shell">
    <div
      v-if="notifyPanelOpen"
      class="notify-backdrop"
      aria-hidden="true"
      @click="closeNotifyPanel"
    />
    <button
      type="button"
      class="action-btn notify-trigger"
      :class="{ 'notify-trigger--active': notifyPanelOpen }"
      aria-haspopup="dialog"
      :aria-expanded="String(notifyPanelOpen)"
      @click.stop="toggleNotifyPanel"
    >
      通知
      <span v-if="notifyUnreadCount > 0" class="notify-badge" :title="`未读 ${notifyUnreadCount}`">
        {{ notifyUnreadCount > 99 ? "99+" : notifyUnreadCount }}
      </span>
    </button>
    <div
      v-if="notifyPanelOpen"
      class="notify-panel"
      role="dialog"
      aria-label="通知列表"
      @click.stop
    >
      <div class="notify-panel__head">
        <div class="notify-panel__title-wrap">
          <span class="notify-panel__title">通知</span>
          <span v-if="notifyTotal > 0" class="notify-panel__sub">共 {{ notifyTotal }} 条</span>
        </div>
        <button type="button" class="notify-panel__close" aria-label="关闭" @click="closeNotifyPanel">
          <i class="ri-close-line" aria-hidden="true"></i>
        </button>
      </div>
      <p v-if="notifyErrorMsg" class="notify-panel__err">{{ notifyErrorMsg }}</p>
      <div v-if="notifyList.length" class="notify-panel__list">
        <div
          v-for="item in notifyList"
          :key="item.id"
          class="notify-row"
          :class="{ 'notify-row--unread': !item.isRead }"
        >
          <button type="button" class="notify-row__main" @click="onNotifyItemClick(item)">
            <img
              :src="item.senderAvatarUrl || profileAvatar"
              alt=""
              class="notify-row__avatar"
              @error="onTopbarAvatarError"
            />
            <div class="notify-row__body">
              <div class="notify-row__meta">
                <span class="notify-row__nick">{{ item.senderNickname }}</span>
                <span class="notify-row__type">{{ item.typeLabel }}</span>
                <span v-if="!item.isRead" class="notify-row__unread-badge" title="未读">未读</span>
              </div>
              <p v-if="item.content" class="notify-row__content">{{ item.content }}</p>
              <time class="notify-row__time">{{ formatNotifyTime(item.createTime) }}</time>
            </div>
          </button>
          <button
            type="button"
            class="notify-row__delete"
            title="删除"
            aria-label="删除此通知"
            @click.stop="onNotifyDelete(item)"
          >
            <i class="ri-delete-bin-line" aria-hidden="true"></i>
          </button>
        </div>
        <button
          v-if="notifyPages > 0 && notifyPageNo < notifyPages"
          type="button"
          class="notify-load-more"
          :disabled="notifyLoading"
          @click="loadMoreNotifications"
        >
          {{ notifyLoading ? "加载中…" : "加载更多" }}
        </button>
      </div>
      <p v-else-if="!notifyLoading" class="notify-panel__empty">暂无通知</p>
      <p v-else class="notify-panel__loading">加载中…</p>
    </div>
  </div>
</template>
