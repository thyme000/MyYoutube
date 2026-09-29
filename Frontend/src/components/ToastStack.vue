<script setup>
import { storeToRefs } from "pinia";
import { useToastStore } from "../stores/toastStore";
import { DEFAULT_PROFILE_AVATAR_URL as profileAvatar } from "../utils/userProfile";

const toastStore = useToastStore();
const { toasts } = storeToRefs(toastStore);
const { removeToast, onNotifyToastClick } = toastStore;
</script>

<template>
  <div v-if="toasts.length" class="toast-stack" aria-live="polite" aria-atomic="true">
    <div
      v-for="t in toasts"
      :key="t.id"
      class="toast-item"
      :class="t.notify ? 'toast-item--live-notify' : `toast-item--${t.type}`"
    >
      <template v-if="t.notify">
        <div
          class="toast-notify-main"
          :class="{
            'toast-notify-main--clickable':
              String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
              (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
              (t.notify.videoId != null && t.notify.videoId !== '') ||
              (t.notify.notificationId != null && t.notify.notificationId !== ''),
          }"
          :role="
            String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
            (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
            (t.notify.videoId != null && t.notify.videoId !== '') ||
            (t.notify.notificationId != null && t.notify.notificationId !== '')
              ? 'button'
              : undefined
          "
          :tabindex="
            String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
            (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
            (t.notify.videoId != null && t.notify.videoId !== '') ||
            (t.notify.notificationId != null && t.notify.notificationId !== '')
              ? 0
              : undefined
          "
          @click="onNotifyToastClick(t)"
          @keydown.enter.prevent="onNotifyToastClick(t)"
          @keydown.space.prevent="onNotifyToastClick(t)"
        >
          <img
            :src="t.notify.avatarUrl || profileAvatar"
            alt=""
            class="toast-notify-avatar"
            @error="(e) => (e.target.src = profileAvatar)"
          />
          <div class="toast-notify-copy">
            <div class="toast-notify-title">{{ t.notify.title }}</div>
            <div v-if="t.notify.content" class="toast-notify-body">{{ t.notify.content }}</div>
            <div v-else class="toast-notify-body toast-notify-body--muted">（无正文预览）</div>
            <span v-if="String(t.notify.notifyKind || '') === 'CHAT_NOTICE'" class="toast-notify-cta">
              点击查看说明 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
            </span>
            <span
              v-else-if="t.notify.conversationId != null && String(t.notify.conversationId).trim() !== ''"
              class="toast-notify-cta"
            >
              点击打开聊天 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
            </span>
            <span
              v-else-if="t.notify.videoId != null && t.notify.videoId !== ''"
              class="toast-notify-cta"
            >
              点击查看视频 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
            </span>
            <span
              v-else-if="t.notify.notificationId != null && t.notify.notificationId !== ''"
              class="toast-notify-cta"
            >
              点击标记已读 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
            </span>
          </div>
        </div>
        <button
          type="button"
          class="toast-item__close toast-item__close--glass"
          aria-label="关闭"
          @click.stop="removeToast(t.id)"
        >
          <i class="ri-close-line"></i>
        </button>
      </template>
      <template v-else>
        <span class="toast-item__text">{{ t.message }}</span>
        <button type="button" class="toast-item__close" aria-label="关闭提示" @click="removeToast(t.id)">
          <i class="ri-close-line"></i>
        </button>
      </template>
    </div>
  </div>
</template>
