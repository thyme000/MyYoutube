<script setup>
import ChatWorkspace from "../components/chat/ChatWorkspace.vue";

defineProps({
  authed: { type: Boolean, default: false },
  selfUserId: { type: [String, Number], default: null },
});
defineEmits(["open-video", "open-user"]);
</script>

<template>
  <section class="explore-chat-page">
    <ChatWorkspace
      v-if="authed"
      :self-user-id="selfUserId"
      @open-video="(payload) => $emit('open-video', payload)"
      @open-user="(payload) => $emit('open-user', payload)"
    />
    <div v-else class="module-empty explore-chat-empty">
      <div class="module-empty-icon"><i class="ri-chat-3-line"></i></div>
      <h5>聊天</h5>
      <p>请先登录后使用会话与消息功能。</p>
    </div>
  </section>
</template>
