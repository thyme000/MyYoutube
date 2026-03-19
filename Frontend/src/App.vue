<script setup>
import { computed, onMounted, ref } from "vue";
import { clearToken, getApiBaseUrl, getToken, setToken } from "./api/http";
import { getLoginCaptcha, getRegisterCaptcha, loginApi, registerApi } from "./api/authApi";
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
const authLoading = ref(false);
const authMessage = ref("");
const activeAuthTab = ref("login");
const token = ref(getToken());

const loginForm = ref({
  username: "",
  password: "",
  captcha: "",
});

const registerForm = ref({
  username: "",
  password: "",
  confirmPassword: "",
  email: "",
  phone: "",
  captcha: "",
});

const loginCaptchaPreview = ref("");
const registerCaptchaPreview = ref("");
const isAuthed = computed(() => Boolean(token.value));

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

async function fetchLoginCaptcha() {
  if (!loginForm.value.username.trim()) {
    authMessage.value = "请先填写登录用户名，再获取验证码。";
    return;
  }
  try {
    loginCaptchaPreview.value = await getLoginCaptcha(loginForm.value.username.trim());
    authMessage.value = "登录验证码获取成功（当前开发阶段直接显示）。";
  } catch (error) {
    authMessage.value = `获取登录验证码失败：${error.message}`;
  }
}

async function fetchRegisterCaptcha() {
  if (!registerForm.value.phone.trim()) {
    authMessage.value = "请先填写手机号，再获取验证码。";
    return;
  }
  try {
    registerCaptchaPreview.value = await getRegisterCaptcha(registerForm.value.phone.trim());
    authMessage.value = "注册验证码获取成功（当前开发阶段直接显示）。";
  } catch (error) {
    authMessage.value = `获取注册验证码失败：${error.message}`;
  }
}

async function submitLogin() {
  authLoading.value = true;
  authMessage.value = "";
  try {
    const responseToken = await loginApi({
      username: loginForm.value.username.trim(),
      password: loginForm.value.password,
      captcha: loginForm.value.captcha.trim(),
    });
    setToken(responseToken);
    token.value = responseToken;
    authMessage.value = "登录成功。";
    const homeMenu = primaryMenus[0];
    activeMenuLabel.value = "首页";
    await loadModuleCards(homeMenu);
  } catch (error) {
    authMessage.value = `登录失败：${error.message}`;
  } finally {
    authLoading.value = false;
  }
}

async function submitRegister() {
  authLoading.value = true;
  authMessage.value = "";
  try {
    const message = await registerApi({
      username: registerForm.value.username.trim(),
      password: registerForm.value.password,
      confirmPassword: registerForm.value.confirmPassword,
      email: registerForm.value.email.trim(),
      phone: registerForm.value.phone.trim(),
      captcha: registerForm.value.captcha.trim(),
    });
    authMessage.value = message || "注册成功，请登录。";
    activeAuthTab.value = "login";
    loginForm.value.username = registerForm.value.username.trim();
  } catch (error) {
    authMessage.value = `注册失败：${error.message}`;
  } finally {
    authLoading.value = false;
  }
}

function logout() {
  clearToken();
  token.value = "";
  cards.value = [];
  authMessage.value = "你已退出登录。";
}

onMounted(async () => {
  if (!isAuthed.value) {
    return;
  }
  const homeMenu = primaryMenus[0];
  await loadModuleCards(homeMenu);
});
</script>

<template>
  <div v-if="!isAuthed" class="login-scene">
    <div class="water-drop p1"></div>
    <div class="water-drop p2"></div>
    <div class="water-drop p3"></div>
    <div class="water-drop p4"></div>
    <div class="water-drop p5"></div>
    <div class="water-drop p6"></div>

    <section class="login-container">
      <h2>{{ activeAuthTab === "login" ? "SIGN IN" : "SIGN UP" }}</h2>

      <div class="auth-switch">
        <button class="switch-btn" :class="{ active: activeAuthTab === 'login' }" @click="activeAuthTab = 'login'">SIGN IN</button>
        <button class="switch-btn" :class="{ active: activeAuthTab === 'register' }" @click="activeAuthTab = 'register'">
          SIGN UP
        </button>
      </div>

      <div class="flip-scene" :class="{ 'register-mode': activeAuthTab === 'register' }">
        <div class="flip-card" :class="{ flipped: activeAuthTab === 'register' }">
          <form class="auth-panel auth-front" @submit.prevent="submitLogin">
          <div class="input-group">
            <span class="input-icon"><i class="ri-user-line"></i></span>
            <input v-model="loginForm.username" type="text" placeholder="User name" required />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-lock-2-line"></i></span>
            <input v-model="loginForm.password" type="password" placeholder="Password" required />
          </div>
          <div class="input-group captcha-group">
            <span class="input-icon"><i class="ri-shield-keyhole-line"></i></span>
            <input v-model="loginForm.captcha" type="text" placeholder="Captcha" required />
            <button type="button" class="btn-captcha" @click="fetchLoginCaptcha">GET</button>
          </div>
          <p v-if="loginCaptchaPreview" class="captcha-tip">Dev captcha: {{ loginCaptchaPreview }}</p>
          <p class="forgot-pass"><a href="javascript:void(0)">Forgot your password?</a></p>
          <button type="submit" class="btn-submit" :disabled="authLoading">
            {{ authLoading ? "PROCESSING..." : "SIGN IN" }}
          </button>
          <p class="switch-link">
            Don't have an account?
            <a href="javascript:void(0)" @click="activeAuthTab = 'register'">Sign up</a>
          </p>
          </form>

          <form class="auth-panel auth-back" @submit.prevent="submitRegister">
          <div class="input-group">
            <span class="input-icon"><i class="ri-user-line"></i></span>
            <input v-model="registerForm.username" type="text" placeholder="User name" required />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-mail-line"></i></span>
            <input v-model="registerForm.email" type="email" placeholder="Email" required />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-smartphone-line"></i></span>
            <input v-model="registerForm.phone" type="text" placeholder="Phone" required />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-lock-2-line"></i></span>
            <input v-model="registerForm.password" type="password" placeholder="Password (10~32)" required />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-checkbox-circle-line"></i></span>
            <input v-model="registerForm.confirmPassword" type="password" placeholder="Confirm password" required />
          </div>
          <div class="input-group captcha-group">
            <span class="input-icon"><i class="ri-shield-keyhole-line"></i></span>
            <input v-model="registerForm.captcha" type="text" placeholder="Captcha" required />
            <button type="button" class="btn-captcha" @click="fetchRegisterCaptcha">GET</button>
          </div>
          <p v-if="registerCaptchaPreview" class="captcha-tip">Dev captcha: {{ registerCaptchaPreview }}</p>
          <button type="submit" class="btn-submit" :disabled="authLoading">
            {{ authLoading ? "PROCESSING..." : "SIGN UP" }}
          </button>
          <p class="switch-link">
            Already have an account?
            <a href="javascript:void(0)" @click="activeAuthTab = 'login'">Sign in</a>
          </p>
          </form>
        </div>
      </div>

      <p v-if="authMessage" class="auth-message">{{ authMessage }}</p>
      <p class="auth-api-hint">API：{{ getApiBaseUrl() }}</p>
    </section>
  </div>

  <div v-else class="app-layout">
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
          <button class="avatar-btn" title="个人中心" @click="onProfile">我</button>
          <button class="action-btn" @click="logout">退出</button>
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
