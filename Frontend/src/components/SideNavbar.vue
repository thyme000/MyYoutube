<script setup>
import { ref } from "vue";
import { storeToRefs } from "pinia";
import { useNavigationStore } from "../stores/navigationStore";
import { useProfileStore } from "../stores/profileStore";
import { useAuthStore } from "../stores/authStore";
import { chatTotalUnread } from "../stores/chatStore.js";
import { primaryMenus, exploreMenus } from "../constants/menus";

defineEmits(["select-menu", "open-my-profile"]);

const navigationStore = useNavigationStore();
const { activeMenuKey } = storeToRefs(navigationStore);
const profileStore = useProfileStore();
const authStore = useAuthStore();
const { isAuthed } = storeToRefs(authStore);

const sidebarCollapsed = ref(false);
const exploreExpanded = ref(true);
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
    <div class="sidebar-header">
      <button class="icon-btn" title="折叠侧边栏" @click="sidebarCollapsed = !sidebarCollapsed">☰</button>
      <h1 class="brand">THYME</h1>
    </div>

    <nav class="nav-section">
      <template v-for="menu in primaryMenus" :key="menu.key">
        <button
          class="nav-item"
          :class="{ active: menu.key === activeMenuKey && !(profileStore.open && profileStore.isViewingSelf) }"
          @click="$emit('select-menu', menu)"
        >
          <span class="nav-icon"><i :class="menu.icon"></i></span>
          <span class="nav-label">{{ menu.label }}</span>
        </button>
      </template>
      <button
        type="button"
        class="nav-item"
        :class="{ active: profileStore.open && profileStore.isViewingSelf }"
        title="我的主页"
        @click="$emit('open-my-profile')"
      >
        <span class="nav-icon"><i class="ri-user-smile-line"></i></span>
        <span class="nav-label">我的</span>
      </button>
    </nav>

    <section class="nav-section">
      <button class="section-toggle" :aria-expanded="String(exploreExpanded)" @click="exploreExpanded = !exploreExpanded">
        <span>探索</span>
        <span class="arrow">▾</span>
      </button>
      <div v-show="exploreExpanded">
        <button
          v-for="menu in exploreMenus"
          :key="menu.key"
          class="nav-item"
          :class="{ active: menu.key === activeMenuKey && !(profileStore.open && profileStore.isViewingSelf) }"
          @click="$emit('select-menu', menu)"
        >
          <span class="nav-icon"><i :class="menu.icon"></i></span>
          <span class="nav-label">{{ menu.label }}</span>
          <span
            v-if="menu.key === 'chat' && isAuthed && (chatTotalUnread ?? 0) > 0"
            class="nav-item__chat-unread"
            aria-label="未读聊天消息"
          >
            {{ chatTotalUnread > 99 ? "99+" : chatTotalUnread }}
          </span>
        </button>
      </div>
    </section>
  </aside>
</template>
