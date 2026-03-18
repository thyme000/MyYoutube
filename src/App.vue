<script setup>
import { onMounted, ref } from "vue";
import { getApiBaseUrl } from "./api/http";
import { fetchVideosByModule, searchVideos } from "./api/videoApi";

const primaryMenus = [
  { key: "home", label: "首页", icon: "🏠" },
  { key: "shorts", label: "短视频 (Shorts)", icon: "🎬" },
  { key: "subscribed", label: "关注", icon: "⭐" },
  { key: "history", label: "历史播放记录", icon: "🕘" },
  { key: "liked", label: "赞过的视频", icon: "👍" },
  { key: "watchLater", label: "稍后再看", icon: "🧾" },
];

const exploreMenus = [
  { key: "music", label: "音乐", icon: "🎵" },
  { key: "movies", label: "电影", icon: "🎞️" },
  { key: "live", label: "直播", icon: "📡" },
  { key: "news", label: "新闻", icon: "📰" },
  { key: "fashion", label: "时尚", icon: "👗" },
];

const activeMenuKey = ref("home");
const activeMenuLabel = ref("首页");
const pageHint = ref(`当前是演示骨架页面。API 基础地址：${getApiBaseUrl()}`);
const searchKeyword = ref("");
const sidebarCollapsed = ref(false);
const exploreExpanded = ref(true);
const loading = ref(false);
const cards = ref([]);

function getMockCards(moduleLabel, moduleKey) {
  return Array.from({ length: 8 }).map((_, index) => ({
    id: `${moduleKey}-${index + 1}`,
    title: `${moduleLabel} 示例内容 ${index + 1}`,
    author: `频道演示 ${index + 1}`,
    viewsText: "1.2万次观看",
  }));
}

async function loadModuleCards(menu, { fallback = true } = {}) {
  loading.value = true;
  try {
    const list = await fetchVideosByModule(menu.key);
    cards.value = list.length ? list : getMockCards(menu.label, menu.key);
    pageHint.value = `你正在查看「${menu.label}」模块。当前数据来源：Spring Boot 接口。`;
  } catch (error) {
    if (!fallback) {
      throw error;
    }
    cards.value = getMockCards(menu.label, menu.key);
    pageHint.value = `接口暂不可用，已回退到本地演示数据。错误信息：${error.message}`;
  } finally {
    loading.value = false;
  }
}

async function setActiveMenu(menu) {
  activeMenuKey.value = menu.key;
  activeMenuLabel.value = menu.label;
  await loadModuleCards(menu);
}

async function onSearch() {
  const keyword = searchKeyword.value.trim();
  if (!keyword) {
    pageHint.value = "请输入关键词后再搜索。";
    return;
  }

  loading.value = true;
  try {
    const list = await searchVideos(keyword);
    cards.value = list.length ? list : getMockCards(`搜索：${keyword}`, "search");
    activeMenuLabel.value = `搜索结果：${keyword}`;
    pageHint.value = `搜索接口请求成功，关键词：「${keyword}」。`;
  } catch (error) {
    cards.value = getMockCards(`搜索：${keyword}`, "search");
    pageHint.value = `搜索接口失败，已展示演示数据。错误信息：${error.message}`;
  } finally {
    loading.value = false;
  }
}

function onCreate() {
  pageHint.value = "点击了创建按钮：后续可打开上传弹窗或发布流程。";
}

function onNotify() {
  pageHint.value = "点击了通知按钮：后续可展示消息列表和未读状态。";
}

function onProfile() {
  pageHint.value = "点击了个人中心：后续可跳转到用户主页或设置页面。";
}

onMounted(async () => {
  const homeMenu = primaryMenus[0];
  await loadModuleCards(homeMenu);
});
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <button class="icon-btn" title="折叠侧边栏" @click="sidebarCollapsed = !sidebarCollapsed">☰</button>
        <h1 class="brand">MyYoutube</h1>
      </div>

      <nav class="nav-section">
        <button
          v-for="menu in primaryMenus"
          :key="menu.key"
          class="nav-item"
          :class="{ active: menu.key === activeMenuKey }"
          @click="setActiveMenu(menu)"
        >
          <span class="nav-icon">{{ menu.icon }}</span>
          <span class="nav-label">{{ menu.label }}</span>
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
            :class="{ active: menu.key === activeMenuKey }"
            @click="setActiveMenu(menu)"
          >
            <span class="nav-icon">{{ menu.icon }}</span>
            <span class="nav-label">{{ menu.label }}</span>
          </button>
        </div>
      </section>
    </aside>

    <div class="main-wrapper">
      <header class="topbar">
        <div class="search-box">
          <input
            v-model="searchKeyword"
            type="text"
            placeholder="搜索视频、频道或关键词"
            aria-label="搜索"
            @keyup.enter="onSearch"
          />
          <button class="icon-btn" @click="onSearch">搜索</button>
        </div>

        <div class="top-actions">
          <button class="action-btn" @click="onCreate">创建</button>
          <button class="action-btn" @click="onNotify">通知</button>
          <button class="avatar-btn" title="个人中心" @click="onProfile">J</button>
        </div>
      </header>

      <main class="content">
        <section class="welcome-card">
          <h2>{{ activeMenuLabel }}</h2>
          <p>{{ pageHint }}</p>
        </section>

        <section class="card-grid">
          <article v-if="loading" class="video-card">
            <div class="video-cover">加载中...</div>
            <div class="video-info">
              <strong>正在请求后端数据</strong>
              <span>请稍候</span>
            </div>
          </article>

          <article v-for="card in cards" :key="card.id" class="video-card">
            <div class="video-cover">16:9 封面占位</div>
            <div class="video-info">
              <strong>{{ card.title }}</strong>
              <span>{{ card.author }} · {{ card.viewsText || "1.2万次观看" }}</span>
            </div>
          </article>
        </section>
      </main>
    </div>
  </div>
</template>
