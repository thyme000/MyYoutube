<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { clearToken, getApiBaseUrl, getToken, setToken } from "./api/http";
import {
  forgotPasswordApi,
  getLoginCaptcha,
  getForgotPasswordCaptcha,
  getRegisterCaptcha,
  loginApi,
  registerApi,
} from "./api/authApi";
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
const pageHint = ref(`欢迎来到 MyYoutube。请选择左侧模块查看内容。API 基础地址：${getApiBaseUrl()}`);
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

const showLoginPassword = ref(false);
const showRegisterPassword = ref(false);
const showRegisterConfirmPassword = ref(false);

const loginCaptchaRemain = ref(0);
const registerCaptchaRemain = ref(0);
let loginCaptchaTimer = null;
let registerCaptchaTimer = null;
let forgotCaptchaTimer = null;

const forgotOpen = ref(false);
const forgotLoading = ref(false);
const forgotMessage = ref("");
const forgotForm = ref({
  username: "",
  phone: "",
  newPassword: "",
  captcha: "",
});
const forgotCaptchaRemain = ref(0);
const forgotCaptchaPreview = ref("");

function getMockCards(moduleLabel, moduleKey) {
  return Array.from({ length: 8 }).map((_, index) => ({
    id: `${moduleKey}-${index + 1}`,
    title: `${moduleLabel} 示例内容 ${index + 1}`,
    author: `MyYoutube频道示例 ${index + 1}`,
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
    pageHint.value = `接口暂不可用，已回退到本地示例数据。错误信息：${error.message}`;
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
    pageHint.value = `搜索接口失败，已展示示例数据。错误信息：${error.message}`;
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
  if (loginCaptchaRemain.value > 0) return;
  try {
    loginCaptchaPreview.value = await getLoginCaptcha(loginForm.value.username.trim());
    authMessage.value = "登录验证码获取成功。";

    loginCaptchaRemain.value = 60;
    if (loginCaptchaTimer) clearInterval(loginCaptchaTimer);
    loginCaptchaTimer = setInterval(() => {
      loginCaptchaRemain.value -= 1;
      if (loginCaptchaRemain.value <= 0) {
        loginCaptchaRemain.value = 0;
        clearInterval(loginCaptchaTimer);
        loginCaptchaTimer = null;
      }
    }, 1000);
  } catch (error) {
    authMessage.value = `获取登录验证码失败：${error.message}`;
  }
}

async function fetchRegisterCaptcha() {
  if (!registerForm.value.phone.trim()) {
    authMessage.value = "请先填写手机号，再获取验证码。";
    return;
  }
  if (registerCaptchaRemain.value > 0) return;
  try {
    registerCaptchaPreview.value = await getRegisterCaptcha(registerForm.value.phone.trim());
    authMessage.value = "注册验证码获取成功。";

    registerCaptchaRemain.value = 60;
    if (registerCaptchaTimer) clearInterval(registerCaptchaTimer);
    registerCaptchaTimer = setInterval(() => {
      registerCaptchaRemain.value -= 1;
      if (registerCaptchaRemain.value <= 0) {
        registerCaptchaRemain.value = 0;
        clearInterval(registerCaptchaTimer);
        registerCaptchaTimer = null;
      }
    }, 1000);
  } catch (error) {
    authMessage.value = `获取注册验证码失败：${error.message}`;
  }
}

function openForgot() {
  forgotOpen.value = true;
  forgotMessage.value = "";
  forgotForm.value = {
    username: loginForm.value.username,
    phone: "",
    newPassword: "",
    captcha: "",
  };
  forgotCaptchaPreview.value = "";
}

async function fetchForgotCaptcha() {
  if (!forgotForm.value.username.trim()) {
    forgotMessage.value = "请先填写用户名，再获取验证码。";
    return;
  }
  if (!forgotForm.value.phone.trim()) {
    forgotMessage.value = "请先填写手机号，再获取验证码。";
    return;
  }
  if (forgotCaptchaRemain.value > 0) return;
  try {
    forgotCaptchaPreview.value = await getForgotPasswordCaptcha({
      username: forgotForm.value.username,
      phone: forgotForm.value.phone.trim(),
    });
    forgotMessage.value = "";

    forgotCaptchaRemain.value = 60;
    if (forgotCaptchaTimer) clearInterval(forgotCaptchaTimer);
    forgotCaptchaTimer = setInterval(() => {
      forgotCaptchaRemain.value -= 1;
      if (forgotCaptchaRemain.value <= 0) {
        forgotCaptchaRemain.value = 0;
        clearInterval(forgotCaptchaTimer);
        forgotCaptchaTimer = null;
      }
    }, 1000);
  } catch (error) {
    forgotMessage.value = `获取验证码失败：${error.message}`;
  }
}

function closeForgot() {
  forgotOpen.value = false;
  forgotCaptchaRemain.value = 0;
  if (forgotCaptchaTimer) {
    clearInterval(forgotCaptchaTimer);
    forgotCaptchaTimer = null;
  }
}

async function submitForgot() {
  forgotLoading.value = true;
  forgotMessage.value = "";
  try {
    if (!forgotForm.value.username.trim()) {
      forgotMessage.value = "请输入用户名。";
      return;
    }
    if (!forgotForm.value.phone.trim()) {
      forgotMessage.value = "请输入手机号。";
      return;
    }
    if (!forgotForm.value.newPassword) {
      forgotMessage.value = "请输入新密码。";
      return;
    }
    if (!forgotForm.value.captcha.trim()) {
      forgotMessage.value = "请输入验证码。";
      return;
    }
    const payload = {
      username: forgotForm.value.username.trim(),
      phone: forgotForm.value.phone.trim(),
      newPassword: forgotForm.value.newPassword,
      captcha: forgotForm.value.captcha.trim(),
    };

    const message = await forgotPasswordApi(payload);
    forgotMessage.value = message || "已提交忘记密码请求，请稍后登录。";
    closeForgot();
    activeAuthTab.value = "login";
  } catch (error) {
    forgotMessage.value = `请求失败：${error.message}`;
  } finally {
    forgotLoading.value = false;
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

onBeforeUnmount(() => {
  if (loginCaptchaTimer) clearInterval(loginCaptchaTimer);
  if (registerCaptchaTimer) clearInterval(registerCaptchaTimer);
  if (forgotCaptchaTimer) clearInterval(forgotCaptchaTimer);
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
            <div class="input-group password-group">
            <span class="input-icon"><i class="ri-lock-2-line"></i></span>
              <input
                v-model="loginForm.password"
                :type="showLoginPassword ? 'text' : 'password'"
                placeholder="Password"
                required
                title="Password: 10~32 位(字母/数字/下划线)"
              />
              <button
                type="button"
                class="eye-btn"
                v-show="loginForm.password"
                @click="showLoginPassword = !showLoginPassword"
              >
                <i class="ri-eye-line" v-if="!showLoginPassword"></i>
                <i class="ri-eye-off-line" v-else></i>
              </button>
          </div>
          <div class="input-group captcha-group">
            <span class="input-icon"><i class="ri-shield-keyhole-line"></i></span>
              <input v-model="loginForm.captcha" type="text" placeholder="Captcha" required />
              <button
                type="button"
                class="btn-captcha"
                :disabled="loginCaptchaRemain > 0"
                @click="fetchLoginCaptcha"
              >
                {{ loginCaptchaRemain > 0 ? `${loginCaptchaRemain}s` : "GET" }}
              </button>
          </div>
          <p v-if="loginCaptchaPreview" class="captcha-tip">Dev captcha: {{ loginCaptchaPreview }}</p>
          <p class="forgot-pass">
            <a href="javascript:void(0)" @click="openForgot">Forgot your password?</a>
          </p>
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
              <input v-model="registerForm.username" type="text" placeholder="User name" required title="Username: 登录用户名(必须唯一)" />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-mail-line"></i></span>
              <input
                v-model="registerForm.email"
                type="email"
                placeholder="Email"
                required
                title="Email: example@domain.com"
              />
          </div>
          <div class="input-group">
            <span class="input-icon"><i class="ri-smartphone-line"></i></span>
              <input v-model="registerForm.phone" type="text" placeholder="Phone" required title="Phone: 11位手机号" />
          </div>
          <div class="input-group password-group">
            <span class="input-icon"><i class="ri-lock-2-line"></i></span>
            <input
              v-model="registerForm.password"
              :type="showRegisterPassword ? 'text' : 'password'"
              placeholder="Password (10~32)"
              required
              title="Password: 10~32 位(字母/数字/下划线)"
            />
            <button
              type="button"
              class="eye-btn"
              v-show="registerForm.password"
              @click="showRegisterPassword = !showRegisterPassword"
            >
              <i class="ri-eye-line" v-if="!showRegisterPassword"></i>
              <i class="ri-eye-off-line" v-else></i>
            </button>
          </div>
          <div class="input-group password-group">
            <span class="input-icon"><i class="ri-checkbox-circle-line"></i></span>
            <input
              v-model="registerForm.confirmPassword"
              :type="showRegisterConfirmPassword ? 'text' : 'password'"
              placeholder="Confirm password"
              required
              title="Confirm password: 与 Password 保持一致"
            />
            <button
              type="button"
              class="eye-btn"
              v-show="registerForm.confirmPassword"
              @click="showRegisterConfirmPassword = !showRegisterConfirmPassword"
            >
              <i class="ri-eye-line" v-if="!showRegisterConfirmPassword"></i>
              <i class="ri-eye-off-line" v-else></i>
            </button>
          </div>
          <div class="input-group captcha-group">
            <span class="input-icon"><i class="ri-shield-keyhole-line"></i></span>
              <input v-model="registerForm.captcha" type="text" placeholder="Captcha" required />
              <button
                type="button"
                class="btn-captcha"
                :disabled="registerCaptchaRemain > 0"
                @click="fetchRegisterCaptcha"
              >
                {{ registerCaptchaRemain > 0 ? `${registerCaptchaRemain}s` : "GET" }}
              </button>
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

      <div v-if="forgotOpen" class="modal-overlay" @click.self="closeForgot">
        <div class="modal-card forgot-modal-card" @click.stop>
          <div class="forgot-modal-grid">
            <div class="forgot-mascot" aria-hidden="true">
              <svg class="forgot-mascot-svg" viewBox="0 0 200 260" xmlns="http://www.w3.org/2000/svg">
                <g class="mascot-body">
                  <g class="mascot-head">
                    <circle cx="100" cy="108" r="70" fill="#ffe8c8" stroke="rgba(40,40,40,0.85)" stroke-width="2.2" />
                    <path
                      class="mascot-brow-left"
                      d="M 52 78 Q 68 68 86 76"
                      stroke="rgba(40,40,40,0.9)"
                      fill="none"
                      stroke-width="3"
                      stroke-linecap="round"
                    />
                    <path
                      class="mascot-brow-right"
                      d="M 114 72 Q 132 62 152 76"
                      stroke="rgba(40,40,40,0.9)"
                      fill="none"
                      stroke-width="3"
                      stroke-linecap="round"
                    />
                    <ellipse cx="74" cy="102" rx="14" ry="16" fill="#fff" stroke="rgba(40,40,40,0.85)" stroke-width="2" />
                    <ellipse cx="126" cy="102" rx="14" ry="16" fill="#fff" stroke="rgba(40,40,40,0.85)" stroke-width="2" />
                    <g class="mascot-pupils">
                      <circle cx="76" cy="102" r="5.5" fill="#1a1a1a" />
                      <circle cx="128" cy="102" r="5.5" fill="#1a1a1a" />
                    </g>
                    <path
                      class="mascot-mouth"
                      d="M 72 138 Q 88 128 100 132 Q 112 128 128 138"
                      stroke="rgba(40,40,40,0.9)"
                      fill="none"
                      stroke-width="2.4"
                      stroke-linecap="round"
                    />
                  </g>
                  <text class="mascot-q" x="158" y="52" font-size="46" fill="#ffd54f" font-weight="800" font-family="Segoe UI, sans-serif">?</text>
                  <g class="mascot-hands">
                    <circle cx="38" cy="188" r="14" fill="#ffe8c8" stroke="rgba(40,40,40,0.85)" stroke-width="2" />
                    <circle cx="162" cy="188" r="14" fill="#ffe8c8" stroke="rgba(40,40,40,0.85)" stroke-width="2" />
                  </g>
                </g>
              </svg>
              <p class="forgot-mascot-caption">Hmm… 密码跑哪去了？</p>
            </div>

            <div class="forgot-modal-form-wrap">
              <h3>Forgot your password?</h3>
              <form class="modal-form" @submit.prevent="submitForgot">
                <div class="input-group modal-input">
                  <span class="input-icon"><i class="ri-user-line"></i></span>
                  <input v-model="forgotForm.username" type="text" placeholder="User name" required title="Username: 登录用户名(必须唯一)" />
                </div>
                <div class="input-group modal-input">
                  <span class="input-icon"><i class="ri-smartphone-line"></i></span>
                  <input v-model="forgotForm.phone" type="text" placeholder="Phone" required title="Phone: 11位手机号" />
                </div>
                <div class="input-group password-group modal-input">
                  <span class="input-icon"><i class="ri-lock-unlock-line"></i></span>
                  <input
                    v-model="forgotForm.newPassword"
                    type="password"
                    placeholder="New password"
                    required
                    title="New password: 10~32 位(字母/数字/下划线)"
                  />
                </div>
                <div class="input-group captcha-group modal-input">
                  <span class="input-icon"><i class="ri-shield-keyhole-line"></i></span>
                  <input v-model="forgotForm.captcha" type="text" placeholder="Captcha" required />
                  <button
                    type="button"
                    class="btn-captcha"
                    :disabled="forgotCaptchaRemain > 0"
                    @click="fetchForgotCaptcha"
                  >
                    {{ forgotCaptchaRemain > 0 ? `${forgotCaptchaRemain}s` : "GET" }}
                  </button>
                </div>
                <p v-if="forgotCaptchaPreview" class="captcha-tip forgot-captcha-tip">Dev captcha: {{ forgotCaptchaPreview }}</p>

                <button type="submit" class="btn-submit" :disabled="forgotLoading">
                  {{ forgotLoading ? "PROCESSING..." : "SUBMIT" }}
                </button>
              </form>
              <p v-if="forgotMessage" class="auth-message forgot-modal-msg">{{ forgotMessage }}</p>
              <button type="button" class="modal-close" @click="closeForgot">Close</button>
            </div>
          </div>
        </div>
      </div>

      <p v-if="authMessage" class="auth-message">{{ authMessage }}</p>
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
