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