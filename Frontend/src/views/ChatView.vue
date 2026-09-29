<script setup>
import ChatWorkspace from "../components/chat/ChatWorkspace.vue";
import { useAuthStore } from "../stores/authStore";
import { useDetailStore } from "../stores/detailStore";
import { useProfileStore } from "../stores/profileStore";

const auth = useAuthStore();
const detail = useDetailStore();
const profile = useProfileStore();
</script>

<template>
  <section class="explore-chat-page">
    <ChatWorkspace
      v-if="auth.isAuthed"
      :self-user-id="auth.userId"
      @open-video="({ videoId }) => detail.openDetail({ id: videoId })"
      @open-user="({ userId }) => profile.openOther(userId)"
    />
    <div v-else class="module-empty explore-chat-empty">
      <div class="module-empty-icon"><i class="ri-chat-3-line"></i></div>
      <h5>聊天</h5>
      <p>请先登录后使用会话与消息功能。</p>
    </div>
  </section>
</template>
