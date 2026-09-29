<script setup>
import { ref, watch, onUnmounted, computed } from "vue";
import ChatConversationList from "./ChatConversationList.vue";
import {
  chatConversations,
  chatConversationsLoading,
  chatActiveConversationId,
  chatTotalUnread,
  openConversation,
  refreshConversationList,
  initChatWhenAuthed,
  loadMoreConversations,
} from "../../stores/chatStore.js";
import { navigateToChat } from "../../utils/chatNavigator.js";

const props = defineProps({
  /** 当前在「聊天」全屏页时为 true，隐藏悬浮条（会话列表在页面内展示） */
  chatPageActive: { type: Boolean, default: false },
  authed: { type: Boolean, default: false },
  /** 短视频 / 关注页右侧留白更少，缩入后露出更窄，减少挡竖屏视野 */
  narrowPeek: { type: Boolean, default: false },
  /** 为 true 时完全隐藏（搜索全屏放大、赞过/历史等全屏流、首页短视频全屏、长视频详情层等，避免挡操作区） */
  suppress: { type: Boolean, default: false },
});

const collapsed = ref(true);
const floatRootRef = ref(null);
/** 刚从聊天全屏页切走时，播一次从右缘缩入的动画 */
const dockSlideActive = ref(false);

const visible = computed(() => props.authed && !props.chatPageActive && !props.suppress);
let leaveTimer = 0;
const COLLAPSE_DELAY_MS = 720;

function clearLeaveTimer() {
  if (leaveTimer) {
    window.clearTimeout(leaveTimer);
    leaveTimer = 0;
  }
}

function expand() {
  clearLeaveTimer();
  collapsed.value = false;
}

function scheduleCollapse() {
  clearLeaveTimer();
  leaveTimer = window.setTimeout(() => {
    collapsed.value = true;
    leaveTimer = 0;
  }, COLLAPSE_DELAY_MS);
}

function onEnter() {
  expand();
}

function onLeave() {
  scheduleCollapse();
}

watch(
  () => props.chatPageActive,
  (v) => {
    if (v) {
      collapsed.value = true;
      clearLeaveTimer();
    }
  },
);

watch(
  () => props.authed,
  (a) => {
    if (a && !props.chatPageActive) {
      void refreshConversationList(true);
    }
  },
);

watch(
  () => [props.authed, props.chatPageActive],
  () => {
    if (props.authed && !props.chatPageActive) {
      void initChatWhenAuthed();
    }
  },
);

onUnmounted(() => clearLeaveTimer());

async function onSelect(id) {
  navigateToChat(id);
  await openConversation(id);
}

function onLoadMore() {
  void loadMoreConversations();
}
</script>

<template>
  <div
    ref="floatRootRef"
    v-show="visible"
    class="chat-float"
    :class="{
      'chat-float--collapsed': collapsed,
      'chat-float--narrow-peek': narrowPeek,
      'chat-float--dock-slide': dockSlideActive,
    }"
    @mouseenter="onEnter"
    @mouseleave="onLeave"
    @animationend="onDockAnimationEnd"
  >
    <div class="chat-float__inner">
      <div class="chat-float__head">
        <span class="chat-float__title">会话</span>
        <span v-if="(chatTotalUnread ?? 0) > 0" class="chat-float__badge">{{ chatTotalUnread > 99 ? "99+" : chatTotalUnread }}</span>
      </div>
      <ChatConversationList
        :items="chatConversations"
        :active-id="chatActiveConversationId"
        :loading="chatConversationsLoading"
        :compact="true"
        @select="onSelect"
        @load-more="onLoadMore"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-float {
  --chat-float-peek: 44px;
  position: fixed;
  top: 52px;
  right: 0;
  bottom: 8px;
  z-index: 11080;
  width: 300px;
  max-width: min(300px, 92vw);
  pointer-events: auto;
  transform: translateX(0);
  /* 收起/展开：偏长、末端明显减速，避免「一下到位」的突兀感 */
  transition: transform 0.62s cubic-bezier(0.16, 1, 0.28, 1);
  filter: drop-shadow(-4px 0 12px rgba(15, 23, 42, 0.12));
}

/* 短视频 / 关注页：缩入后仅留更窄一条，少挡竖屏内容 */
.chat-float--narrow-peek {
  --chat-float-peek: 18px;
}

.chat-float--collapsed {
  transform: translateX(calc(100% - var(--chat-float-peek)));
}

/* 从聊天全屏切回其它页时：自屏幕外平滑缩入到收起态 */
.chat-float--dock-slide {
  transition: none;
}

.chat-float--dock-slide.chat-float--collapsed {
  /* 从聊天页切出：略更长 + 同系缓出，末端缓缓贴齐露出条 */
  animation: chatFloatDockIn 0.78s cubic-bezier(0.16, 1, 0.28, 1) both;
}

.chat-float__inner {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px 0 0 12px;
  border: 1px solid #e2e8f0;
  border-right: none;
  overflow: hidden;
}

.chat-float__head {
  flex-shrink: 0;
  padding: 10px 12px;
  border-bottom: 1px solid #eef2f7;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: linear-gradient(180deg, #f8fafc, #fff);
}

.chat-float__title {
  font-weight: 600;
  font-size: 14px;
  color: #0f172a;
}

.chat-float__badge {
  min-width: 20px;
  padding: 0 6px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
}
</style>
