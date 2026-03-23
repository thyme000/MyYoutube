<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { clearToken, getApiBaseUrl, getToken, setToken } from "./api/http";
import {
  forgotPasswordApi,
  getLoginCaptcha,
  getForgotPasswordCaptcha,
  getRegisterCaptcha,
  loginApi,
  registerApi,
} from "./api/authApi";
import {
  fetchVideosByModule,
  searchVideos,
  fetchVideoListPage,
  fetchAuthorVideos,
  fetchVideoDetail,
  normalizeVideoDetail,
  publishVideo,
} from "./api/videoApi";
import { VIDEO_CATEGORY_CHIPS } from "./constants/videoCategories";
import {
  getUserInfo,
  getUserProfileById,
  updateUserAvatar,
  updateUserBgImage,
  updateUserProfile,
} from "./api/userApi";
import {
  loadCropFromStorage,
  makeDefaultCropRect,
  normalizeRect,
  saveCropToStorage,
} from "./utils/imageCropStorage";
import { normalizeUserProfile, sanitizeImageUrl } from "./utils/userProfile";
import profileAvatar from "../static/cat.jpg";
import profileBanner from "../static/cartoon.jpg";
/** 短视频无接口数据时的本地 fallback（static 目录下四个 mp4） */
import localShortV1 from "../static/c7bed897269ef9d11cfcaf38c27e855d.mp4?url";
import localShortV2 from "../static/8d20eed729b54235eeaca56d49808aa9.mp4?url";
import localShortV3 from "../static/d7527aad3983ad86bd80b09c5e4d826c.mp4?url";
import localShortV4 from "../static/a5da19be6fe54cb2ca9f50d87315c755.mp4?url";

const primaryMenus = [
  { key: "home", label: "首页", icon: "ri-home-4-line" },
  { key: "shorts", label: "短视频", icon: "ri-video-line" },
  { key: "subscribed", label: "关注", icon: "ri-star-line" },
  { key: "history", label: "历史播放记录", icon: "ri-history-line" },
  { key: "liked", label: "赞过的视频", icon: "ri-thumb-up-line" },
  { key: "watchLater", label: "稍后再看", icon: "ri-time-line" },
];

const exploreMenus = [
  { key: "music", label: "音乐", icon: "ri-music-2-line" },
  { key: "movies", label: "电影", icon: "ri-film-line" },
  { key: "live", label: "直播", icon: "ri-broadcast-line" },
  { key: "news", label: "新闻", icon: "ri-newspaper-line" },
  { key: "fashion", label: "时尚", icon: "ri-shirt-line" },
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
const showProfileCenter = ref(false);
/** null = 自己的主页；有值 = 正在查看该 userId 的主页 */
const profileTargetUserId = ref(null);
/** 当前登录用户 id，来自 /user/info，用于判断是否「看自己」 */
const currentUserId = ref(null);
const profileUser = ref(null);
const profileLoading = ref(false);
const profileMsg = ref("");
const profileUploadBusy = ref(false);
const avatarFileInput = ref(null);
const bgFileInput = ref(null);
/** 头像 / 背景 方形预览弹层 */
const mediaPreviewOpen = ref(false);
/** 'avatar' | 'bg' */
const mediaPreviewType = ref("avatar");
const relationTableOpen = ref(false);
const relationTableTitle = ref("");
const relationTableUsers = ref([]);
/** 'following' | 'followers' — 控制操作列：粉丝为三点菜单，关注为取关 */
const relationTableKind = ref("followers");
/** 粉丝列表：当前展开三点菜单的行 id */
const relationFollowerMenuId = ref(null);
/** 编辑个人资料（昵称、简介） */
const profileEditOpen = ref(false);
const profileEditForm = ref({ nickName: "", introduction: "" });
const profileEditSaving = ref(false);
/** 创作中心（全屏主区，模拟 YouTube Studio） */
const showStudio = ref(false);
/** 进入创作中心时 pushState，便于浏览器后退与「返回」同步 */
const studioHistoryPushed = ref(false);
const homeCategoryKey = ref("all");
const studioSideNav = ref("info");
/** 频道内容子 tab：video=全部作品 shorts=短视频 live=直播（直播接口未就绪时仅占位） */
const studioContentTab = ref("video");
const studioPageNo = ref(1);
const studioPageSize = ref(10);
const studioRows = ref([]);
const studioTotal = ref(0);
const studioPages = ref(0);
const studioLoading = ref(false);
const studioMsg = ref("");
const uploadModalOpen = ref(false);
const uploadFileInputRef = ref(null);
/** 视频详情页 */
const showVideoDetail = ref(false);
const videoDetail = ref(null);
const detailLoading = ref(false);
const detailErrorMsg = ref("");
const detailPlayerRef = ref(null);
/** 因浏览器策略改用静音自动播放时提示用户可开声 */
const detailAutoplayMutedFallback = ref(false);
const detailSidebarTab = ref("all");
const detailSidebarList = ref([]);
const detailSidebarPageNo = ref(1);
const detailSidebarPages = ref(0);
const detailSidebarLoading = ref(false);
const detailSidebarLoadingMore = ref(false);
const detailSidebarHasMore = ref(true);
const detailSidebarScrollRef = ref(null);
const DETAIL_SIDEBAR_PAGE_SIZE = 10;
/** 地址栏 ?v= 与 sessionStorage 恢复详情页（刷新不丢） */
const VIDEO_URL_PARAM = "v";
const VIDEO_DETAIL_STORAGE_PREFIX = "myyoutube_vdetail_";

const detailSidebarTabs = [
  { key: "all", label: "全部" },
  { key: "long", label: "视频" },
  { key: "short", label: "短视频" },
];

/** 评论区占位（后端未就绪） */
const detailMockComments = [
  {
    user: "@demo_user",
    time: "2个月前",
    text: "占位评论：后端接入后将显示真实评论。",
    likes: 42,
    avatar: profileAvatar,
  },
  {
    user: "@viewer",
    time: "1周前",
    text: "👍 期待接口上线。",
    likes: 12,
    avatar: profileAvatar,
  },
];
/** 短视频全屏播放（纵向视频两侧模糊填充） */
const shortPlayerOpen = ref(false);
const shortPlayerVideoSrc = ref("");
const shortPlayerTitle = ref("");
const shortPlayerIsPortrait = ref(false);
const shortFgVideoRef = ref(null);
const shortBgVideoRef = ref(null);
/** 短视频模块内嵌 feed（默认沉浸播放，非弹窗） */
const shortsFeedIndex = ref(0);
const shortsFeedFgRef = ref(null);
const shortsFeedBgRef = ref(null);
const shortsFeedCenterRef = ref(null);
const shortsFeedIsPortrait = ref(false);
const shortsFeedDuration = ref(0);
const shortsFeedCurrentTime = ref(0);
const shortsFeedVolume = ref(0.85);
const shortsFeedMuted = ref(false);
/** 暂停时在画面中央显示三角形播放图标 */
const shortsFeedPaused = ref(true);
/** 切换视频后自动播放（含进入短视频页） */
const shortsFeedAutoplayPending = ref(false);
/** 当前作者是否已关注（按视频 id） */
const shortsFollowedMap = ref({});
const feedCoverPool = [
  "https://images.unsplash.com/photo-1516117172878-fd2c41f4a759?auto=format&fit=crop&w=1200&q=80",
  "https://images.unsplash.com/photo-1519389950473-47ba0277781c?auto=format&fit=crop&w=1200&q=80",
  "https://images.unsplash.com/photo-1489515217757-5fd1be406fef?auto=format&fit=crop&w=1200&q=80",
  "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?auto=format&fit=crop&w=1200&q=80",
];

/** 演示用：横版 / 竖版样片（竖版播放时两侧模糊填充） */
const DEMO_VIDEO_LANDSCAPE =
  "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4";
const DEMO_VIDEO_PORTRAIT =
  "https://assets.mixkit.co/videos/preview/mixkit-tree-with-yellow-flowers-1173-large.mp4";

const profileWorks = [
  {
    id: "work-1",
    title: "直到某一天 你的行为不再左右我",
    likes: "3911",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_PORTRAIT,
  },
  {
    id: "work-2",
    title: "0-0 美瞳推荐 #美瞳",
    likes: "8336",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_LANDSCAPE,
  },
  {
    id: "work-3",
    title: "我要成为这个世界的大王",
    likes: "8.2万",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_PORTRAIT,
  },
  {
    id: "work-4",
    title: "把春天穿在身上 舒胡噜的这天",
    likes: "2.8万",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_LANDSCAPE,
  },
  {
    id: "work-5",
    title: "夜晚车窗前的小情绪记录",
    likes: "1.4万",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_PORTRAIT,
  },
  {
    id: "work-6",
    title: "今天的穿搭分享 #ootd",
    likes: "7693",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_LANDSCAPE,
  },
  {
    id: "work-7",
    title: "花束与温柔都要给自己",
    likes: "5231",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_PORTRAIT,
  },
  {
    id: "work-8",
    title: "阳光刚刚好 出门随拍",
    likes: "1.1万",
    image: profileBanner,
    videoUrl: DEMO_VIDEO_LANDSCAPE,
  },
];
const avatarImgError = ref(false);
const bgImgError = ref(false);

watch(
  () => profileUser.value?.avatarUrl,
  () => {
    avatarImgError.value = false;
  }
);
watch(
  () => profileUser.value?.bgImage,
  () => {
    bgImgError.value = false;
  }
);

const profileDisplayAvatar = computed(() => {
  if (avatarImgError.value) {
    return profileAvatar;
  }
  const u = sanitizeImageUrl(profileUser.value?.avatarUrl);
  return u || profileAvatar;
});

const profileDisplayBg = computed(() => {
  if (bgImgError.value) {
    return profileBanner;
  }
  const u = sanitizeImageUrl(profileUser.value?.bgImage);
  return u || profileBanner;
});

function onProfileAvatarImgError() {
  avatarImgError.value = true;
}

function onProfileBgImgError() {
  bgImgError.value = true;
}

function onMediaPreviewImgError() {
  if (mediaPreviewType.value === "bg") {
    onProfileBgImgError();
  } else {
    onProfileAvatarImgError();
  }
}

const displayNickname = computed(() => {
  const u = profileUser.value;
  if (!u) {
    return "加载中…";
  }
  if (u.nickName?.trim()) {
    return u.nickName.trim();
  }
  if (u.username?.trim()) {
    return u.username.trim();
  }
  return "未命名用户";
});

const isViewingSelf = computed(() => {
  if (profileTargetUserId.value == null) {
    return true;
  }
  if (currentUserId.value == null) {
    return false;
  }
  return Number(profileTargetUserId.value) === Number(currentUserId.value);
});

/** 个人简介展示文案（自己主页可点进编辑） */
const introDisplayText = computed(() => {
  const t = profileUser.value?.introduction?.trim();
  if (t) {
    return t;
  }
  return isViewingSelf.value ? "暂无简介，点击此处编辑" : "暂无简介";
});

/** 顶栏「我」头像：登录后优先用户头像，否则默认图 */
const topbarAvatarSrc = computed(() => {
  const u = profileUser.value;
  const url = u?.avatarUrl ? sanitizeImageUrl(u.avatarUrl) : "";
  return url || profileAvatar;
});

const studioTabs = [
  { key: "video", label: "视频" },
  { key: "shorts", label: "短视频" },
  { key: "live", label: "直播" },
];

const detailAuthorAvatar = computed(() => {
  const u = videoDetail.value?.authorAvatarUrl;
  const url = u ? sanitizeImageUrl(u) : "";
  return url || profileAvatar;
});

const detailFanText = computed(() => {
  const n = videoDetail.value?.authorFanCount;
  if (n == null || n === "") {
    return "粉丝 —";
  }
  return `${n} 位粉丝`;
});

/** 裁剪区域（归一化相对原图）：仅自己主页使用，本地按「用户 + 图片 URL」记忆 */
const cropRectBg = ref({ L: 0, T: 0, W: 1, H: 1 });
const cropRectAvatar = ref({ L: 0, T: 0, W: 1, H: 1 });
const cropRectDraft = ref({ L: 0, T: 0, W: 1, H: 1 });
const cropNaturalSize = ref({ w: 0, h: 0 });

/** 横幅裁剪容器（用于 ResizeObserver 量宽高） */
const heroCropContainerRef = ref(null);
const heroSize = ref({ w: 0, h: 0 });
const profileBgNaturalSize = ref({ w: 0, h: 0 });
const profileAvatarNaturalSize = ref({ w: 0, h: 0 });
const AVATAR_BOX_PX = 110;

let heroResizeObserver = null;

function isCropFullFrame(rect) {
  const { L, T, W, H } = rect;
  return L <= 0.001 && T <= 0.001 && W >= 0.999 && H >= 0.999;
}

function measureHeroContainer() {
  const el = heroCropContainerRef.value;
  if (!el) {
    return;
  }
  const r = el.getBoundingClientRect();
  heroSize.value = { w: r.width, h: r.height };
}

const heroImgUsePrecise = computed(() => {
  if (!isViewingSelf.value) {
    return false;
  }
  if (isCropFullFrame(cropRectBg.value)) {
    return false;
  }
  const nw = profileBgNaturalSize.value.w;
  const nh = profileBgNaturalSize.value.h;
  const Ew = heroSize.value.w;
  const Eh = heroSize.value.h;
  return nw > 0 && nh > 0 && Ew > 0 && Eh > 0;
});

/** 与弹窗内归一化选区一致：scale = 容器宽 / 选区像素宽，再平移使选区左上角对齐容器左上角 */
const heroImgStyle = computed(() => {
  if (!heroImgUsePrecise.value) {
    return {};
  }
  const { L, T, W, H } = cropRectBg.value;
  const nw = profileBgNaturalSize.value.w;
  const nh = profileBgNaturalSize.value.h;
  const Ew = heroSize.value.w;
  const scale = Ew / (W * nw);
  const imgW = nw * scale;
  const imgH = nh * scale;
  const left = -L * nw * scale;
  const top = -T * nh * scale;
  return {
    position: "absolute",
    width: `${imgW}px`,
    height: `${imgH}px`,
    left: `${left}px`,
    top: `${top}px`,
    maxWidth: "none",
  };
});

const avatarImgUsePrecise = computed(() => {
  if (!isViewingSelf.value) {
    return false;
  }
  if (isCropFullFrame(cropRectAvatar.value)) {
    return false;
  }
  const nw = profileAvatarNaturalSize.value.w;
  const nh = profileAvatarNaturalSize.value.h;
  return nw > 0 && nh > 0;
});

const avatarImgStyle = computed(() => {
  if (!avatarImgUsePrecise.value) {
    return {};
  }
  const { L, T, W, H } = cropRectAvatar.value;
  const nw = profileAvatarNaturalSize.value.w;
  const nh = profileAvatarNaturalSize.value.h;
  const Ew = AVATAR_BOX_PX;
  const scale = Ew / (W * nw);
  const imgW = nw * scale;
  const imgH = nh * scale;
  const left = -L * nw * scale;
  const top = -T * nh * scale;
  return {
    position: "absolute",
    width: `${imgW}px`,
    height: `${imgH}px`,
    left: `${left}px`,
    top: `${top}px`,
    maxWidth: "none",
  };
});

function onProfileHeroBgLoad(e) {
  profileBgNaturalSize.value = {
    w: e.target.naturalWidth,
    h: e.target.naturalHeight,
  };
}

function onProfileAvatarNaturalLoad(e) {
  profileAvatarNaturalSize.value = {
    w: e.target.naturalWidth,
    h: e.target.naturalHeight,
  };
}

function preloadImageNaturalSize(url, targetRef) {
  if (!url) {
    return;
  }
  const im = new Image();
  im.onload = () => {
    targetRef.value = { w: im.naturalWidth, h: im.naturalHeight };
  };
  im.src = url;
}

watch(
  () => profileDisplayBg.value,
  (url) => {
    preloadImageNaturalSize(url, profileBgNaturalSize);
  },
  { immediate: true }
);

watch(
  () => profileDisplayAvatar.value,
  (url) => {
    preloadImageNaturalSize(url, profileAvatarNaturalSize);
  },
  { immediate: true }
);

watch(
  () => [showProfileCenter.value, heroCropContainerRef.value],
  async () => {
    await nextTick();
    if (heroResizeObserver) {
      heroResizeObserver.disconnect();
      heroResizeObserver = null;
    }
    if (!showProfileCenter.value || !heroCropContainerRef.value) {
      return;
    }
    measureHeroContainer();
    heroResizeObserver = new ResizeObserver(() => {
      measureHeroContainer();
    });
    heroResizeObserver.observe(heroCropContainerRef.value);
  },
  { flush: "post" }
);

const cropBoxStyle = computed(() => ({
  left: `${cropRectDraft.value.L * 100}%`,
  top: `${cropRectDraft.value.T * 100}%`,
  width: `${cropRectDraft.value.W * 100}%`,
  height: `${cropRectDraft.value.H * 100}%`,
}));

function applyCropsFromStorage() {
  if (!isViewingSelf.value) {
    return;
  }
  const uid = currentUserId.value ?? profileUser.value?.id;
  if (!uid) {
    return;
  }
  const urlBg = sanitizeImageUrl(profileUser.value?.bgImage) || profileDisplayBg.value;
  const urlAv = sanitizeImageUrl(profileUser.value?.avatarUrl) || profileDisplayAvatar.value;
  if (urlBg) {
    const s = loadCropFromStorage("bg", uid, urlBg);
    cropRectBg.value = s || { L: 0, T: 0, W: 1, H: 1 };
  }
  if (urlAv) {
    const s = loadCropFromStorage("avatar", uid, urlAv);
    cropRectAvatar.value = s || { L: 0, T: 0, W: 1, H: 1 };
  }
}

function applyDraftCrop() {
  if (!isViewingSelf.value) {
    return;
  }
  const uid = currentUserId.value ?? profileUser.value?.id;
  if (!uid) {
    return;
  }
  const r = normalizeRect({ ...cropRectDraft.value });
  if (mediaPreviewType.value === "bg") {
    const url = sanitizeImageUrl(profileUser.value?.bgImage) || profileDisplayBg.value;
    cropRectBg.value = r;
    saveCropToStorage("bg", uid, url, r);
  } else {
    const url = sanitizeImageUrl(profileUser.value?.avatarUrl) || profileDisplayAvatar.value;
    cropRectAvatar.value = r;
    saveCropToStorage("avatar", uid, url, r);
  }
}

function onCropImgLoad(e) {
  const img = e.target;
  const nw = img.naturalWidth;
  const nh = img.naturalHeight;
  cropNaturalSize.value = { w: nw, h: nh };
  const { L, T, W, H } = cropRectDraft.value;
  const isFull = L <= 0.001 && T <= 0.001 && W >= 0.999 && H >= 0.999;
  if (isFull && nw > 0 && nh > 0) {
    const aspect = mediaPreviewType.value === "bg" ? 16 / 5 : 1;
    cropRectDraft.value = makeDefaultCropRect(nw, nh, aspect, 1);
  }
}

function onCropBoxDragStart(e) {
  if (e.button !== 0 || e.target.classList.contains("crop-handle-br")) {
    return;
  }
  e.preventDefault();
  e.stopPropagation();
  const inner = e.currentTarget.closest(".media-preview-crop-stage-inner");
  if (!inner) {
    return;
  }
  const rect = inner.getBoundingClientRect();
  const start = { ...cropRectDraft.value };
  const sx = e.clientX;
  const sy = e.clientY;
  function onMove(ev) {
    const dx = (ev.clientX - sx) / rect.width;
    const dy = (ev.clientY - sy) / rect.height;
    let L = start.L + dx;
    let T = start.T + dy;
    L = Math.min(Math.max(L, 0), 1 - start.W);
    T = Math.min(Math.max(T, 0), 1 - start.H);
    cropRectDraft.value = { ...start, L, T };
  }
  function onUp() {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  }
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

function onCropResizeStart(e) {
  e.stopPropagation();
  e.preventDefault();
  const inner = e.currentTarget.closest(".media-preview-crop-stage-inner");
  if (!inner) {
    return;
  }
  const rect = inner.getBoundingClientRect();
  const nw = cropNaturalSize.value.w;
  const nh = cropNaturalSize.value.h;
  if (nw <= 0 || nh <= 0) {
    return;
  }
  const ratioWH =
    mediaPreviewType.value === "bg" ? (16 / 5) * (nh / nw) : 1 * (nh / nw);
  const start = { ...cropRectDraft.value };
  const sx = e.clientX;
  const sy = e.clientY;
  function onMove(ev) {
    const dx = (ev.clientX - sx) / rect.width;
    let newW = start.W + dx;
    let newH = newW / ratioWH;
    newW = Math.max(0.05, newW);
    newH = Math.max(0.05, newH);
    if (start.L + newW > 1) {
      newW = 1 - start.L;
      newH = newW / ratioWH;
    }
    if (start.T + newH > 1) {
      newH = 1 - start.T;
      newW = newH * ratioWH;
    }
    cropRectDraft.value = normalizeRect({ ...start, W: newW, H: newH });
  }
  function onUp() {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  }
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

watch(
  () => [
    isViewingSelf.value,
    currentUserId.value,
    profileUser.value?.bgImage,
    profileUser.value?.avatarUrl,
    profileUser.value?.id,
    profileDisplayBg.value,
    profileDisplayAvatar.value,
  ],
  () => {
    applyCropsFromStorage();
  }
);

const mediaPreviewSrc = computed(() =>
  mediaPreviewType.value === "avatar" ? profileDisplayAvatar.value : profileDisplayBg.value
);

function buildMockOtherProfile(userId) {
  const id = Number(userId);
  return normalizeUserProfile({
    id,
    nickName: `用户 ${id}`,
    avatarUrl: feedCoverPool[id % feedCoverPool.length],
    bgImage: feedCoverPool[(id + 1) % feedCoverPool.length],
    fans: 1200,
    likeCount: 3500,
    videoCount: 8,
    attentionCount: 88,
    introduction: "这是他人主页的示例数据（后端 /user/profile/{id} 未就绪时自动展示）。",
  });
}

const followingUsers = [
  { id: 1, nickName: "Alice", avatarUrl: feedCoverPool[0] },
  { id: 2, nickName: "Tom", avatarUrl: feedCoverPool[1] },
  { id: 3, nickName: "Luna", avatarUrl: feedCoverPool[2] },
];

const followerUsers = [
  { id: 11, nickName: "Ivy", avatarUrl: feedCoverPool[0] },
  { id: 12, nickName: "Ken", avatarUrl: feedCoverPool[1] },
  { id: 13, nickName: "Mia", avatarUrl: feedCoverPool[2] },
  { id: 14, nickName: "Ryan", avatarUrl: feedCoverPool[3] },
];

function getShortsLocalFallbackCards() {
  const urls = [localShortV1, localShortV2, localShortV3, localShortV4];
  return urls.map((url, index) => ({
    id: `local-short-${index + 1}`,
    title: `本地视频 ${index + 1}`,
    author: "MyYoutube",
    viewsText: "本地",
    videoUrl: url,
    coverUrl: feedCoverPool[index % feedCoverPool.length],
    avatarUrl: feedCoverPool[index % feedCoverPool.length],
    authorAvatarUrl: feedCoverPool[(index + 2) % feedCoverPool.length],
    authorId: 100,
    videoType: "SHORT_VIDEO",
  }));
}

function getMockCards(moduleLabel, moduleKey) {
  return Array.from({ length: 8 }).map((_, index) => ({
    id: `${moduleKey}-${index + 1}`,
    title: `${moduleLabel} 示例内容 ${index + 1}`,
    author: `MyYoutube频道示例 ${index + 1}`,
    viewsText: "1.2万次观看",
    videoUrl: index % 2 === 0 ? DEMO_VIDEO_PORTRAIT : DEMO_VIDEO_LANDSCAPE,
    coverUrl: feedCoverPool[index % feedCoverPool.length],
    avatarUrl: feedCoverPool[index % feedCoverPool.length],
    authorAvatarUrl: feedCoverPool[(index + 1) % feedCoverPool.length],
    authorId: 10001 + index,
    videoType: index % 3 === 0 ? "SHORT_VIDEO" : "LONG_VIDEO",
  }));
}

function getCardCover(index) {
  return feedCoverPool[index % feedCoverPool.length];
}

/** 仅当 id 为纯数字字符串时可用于 /videos/detail/{id}，全程用字符串避免精度丢失 */
function parseVideoIdForApi(id) {
  if (id == null || id === "") {
    return null;
  }
  const s = String(id).trim();
  if (/^\d+$/.test(s)) {
    return s;
  }
  return null;
}

function buildDetailFromCard(card) {
  const vid = parseVideoIdForApi(card?.id);
  return normalizeVideoDetail({
    id: vid ?? (card?.id != null ? String(card.id) : ""),
    title: card?.title ?? "未命名视频",
    description: "（示例数据）视频简介占位，连接后端后将显示真实描述。",
    coverUrl: card?.coverUrl || card?.avatarUrl || "",
    videoUrl: resolveVideoUrl(card) || DEMO_VIDEO_LANDSCAPE,
    videoType: card?.videoType ?? "LONG_VIDEO",
    playCount: 0,
    likeCount: 0,
    commentCount: 0,
    authorId: card?.authorId ?? null,
    authorNickname: card?.authorNickname ?? card?.author ?? "频道",
    authorAvatarUrl: card?.authorAvatarUrl || "",
    authorFanCount: 0,
  });
}

function isLongVideoItem(item) {
  const t = item?.videoType;
  if (t == null || t === "") {
    return true;
  }
  const s = String(t).toUpperCase();
  if (s.includes("SHORT") || s === "SHORT_VIDEO" || t === "short") {
    return false;
  }
  return true;
}

function genMockSidebarList(tab) {
  const n = 8;
  const base = (videoDetail.value?.authorId ?? 1) * 17;
  return Array.from({ length: n }).map((_, i) => ({
    id: `mock-side-${tab}-${base}-${i}`,
    title: `推荐内容 ${i + 1}`,
    author: videoDetail.value?.authorNickname ?? "示例频道",
    viewsText: `${(i + 1) * 1200} 次播放`,
    videoUrl: i % 2 === 0 ? DEMO_VIDEO_PORTRAIT : DEMO_VIDEO_LANDSCAPE,
    coverUrl: feedCoverPool[(base + i) % feedCoverPool.length],
    avatarUrl: feedCoverPool[(base + i) % feedCoverPool.length],
    authorAvatarUrl: profileAvatar,
    authorId: videoDetail.value?.authorId ?? null,
    videoType: tab === "short" ? "SHORT_VIDEO" : "LONG_VIDEO",
  }));
}

function cardAuthorAvatar(card, index) {
  const u = card?.authorAvatarUrl ? sanitizeImageUrl(card.authorAvatarUrl) : "";
  return u || profileAvatar;
}

function cardCoverSrc(card, index) {
  return card?.coverUrl || card?.avatarUrl || getCardCover(index);
}

/** 列表/侧栏与详情同一套昵称：优先接口 authorNickname，否则用已拼好的 author */
function cardAuthorLabel(card) {
  if (!card) {
    return "";
  }
  const nick = card.authorNickname;
  if (typeof nick === "string" && nick.trim()) {
    return nick.trim();
  }
  const a = card.author;
  if (typeof a === "string" && a.trim()) {
    return a.trim();
  }
  return "—";
}

function videoIdsEqual(a, b) {
  if (a == null || b == null) {
    return false;
  }
  return String(a) === String(b);
}

function snapshotVideoDetailForRestore(d) {
  if (!d || d.id == null) {
    return;
  }
  const key = `${VIDEO_DETAIL_STORAGE_PREFIX}${encodeURIComponent(String(d.id))}`;
  const snap = {
    id: d.id,
    title: d.title ?? "",
    author: d.authorNickname ?? "",
    authorNickname: d.authorNickname ?? "",
    viewsText: d.viewsText,
    videoUrl: d.videoUrl ?? "",
    coverUrl: d.coverUrl ?? "",
    avatarUrl: d.coverUrl ?? "",
    authorAvatarUrl: d.authorAvatarUrl ?? "",
    authorId: d.authorId,
    videoType: d.videoType,
    description: d.description,
  };
  try {
    sessionStorage.setItem(key, JSON.stringify(snap));
  } catch {
    /* ignore quota */
  }
}

/**
 * 从首页进入详情：pushState，浏览器「返回」可回到无 ?v= 的首页。
 * 在详情内切换视频：replaceState，不增加历史记录。
 */
function syncVideoDetailToAddressBar(wasAlreadyInDetail) {
  const d = videoDetail.value;
  if (!d || d.id == null) {
    return;
  }
  try {
    const url = new URL(window.location.href);
    const nextV = String(d.id);
    const curV = url.searchParams.get(VIDEO_URL_PARAM);
    if (curV != null && curV === nextV) {
      return;
    }
    url.searchParams.set(VIDEO_URL_PARAM, nextV);
    const next = url.toString();
    if (wasAlreadyInDetail) {
      history.replaceState({ myyoutubeVideo: true }, "", next);
    } else {
      history.pushState({ myyoutubeVideo: true }, "", next);
    }
  } catch {
    /* ignore */
  }
}

function clearVideoDetailFromAddressBar() {
  try {
    const url = new URL(window.location.href);
    url.searchParams.delete(VIDEO_URL_PARAM);
    const q = url.searchParams.toString();
    const next = `${url.pathname}${q ? `?${q}` : ""}${url.hash}`;
    history.replaceState({}, "", next);
  } catch {
    /* ignore */
  }
}

async function restoreVideoDetailFromUrl(vidRaw) {
  if (!vidRaw) {
    return false;
  }
  const key = `${VIDEO_DETAIL_STORAGE_PREFIX}${encodeURIComponent(vidRaw)}`;
  const stored = sessionStorage.getItem(key);
  if (stored) {
    try {
      const card = JSON.parse(stored);
      await openVideoDetail(card);
      return true;
    } catch {
      /* fall through */
    }
  }
  const numericId = parseVideoIdForApi(vidRaw);
  if (numericId != null) {
    await openVideoDetail({ id: numericId });
    return true;
  }
  return false;
}

async function tryDetailVideoAutoplay() {
  const el = detailPlayerRef.value;
  if (!el || !videoDetail.value?.videoUrl) {
    return;
  }
  if (!el.paused) {
    return;
  }
  try {
    el.playsInline = true;
  } catch {
    /* ignore */
  }
  try {
    el.muted = false;
    detailAutoplayMutedFallback.value = false;
    await el.play();
  } catch {
    try {
      el.muted = true;
      detailAutoplayMutedFallback.value = true;
      await el.play();
    } catch {
      /* 静音仍被拦截则只能由用户点击控件播放 */
    }
  }
}

function onDetailVideoCanPlay() {
  void tryDetailVideoAutoplay();
}

function onDetailVideoVolumeChange() {
  const el = detailPlayerRef.value;
  if (el && !el.muted) {
    detailAutoplayMutedFallback.value = false;
  }
}

function scheduleDetailVideoAutoplayRetries() {
  void tryDetailVideoAutoplay();
  setTimeout(() => void tryDetailVideoAutoplay(), 80);
  setTimeout(() => void tryDetailVideoAutoplay(), 320);
  setTimeout(() => void tryDetailVideoAutoplay(), 700);
  setTimeout(() => void tryDetailVideoAutoplay(), 1200);
  setTimeout(() => void tryDetailVideoAutoplay(), 2000);
  setTimeout(() => void tryDetailVideoAutoplay(), 3000);
  setTimeout(() => void tryDetailVideoAutoplay(), 4500);
}

/** 刷新 / 往返缓存后 DOM 就绪较晚，多补几轮 */
function onWindowPageShow() {
  if (!showVideoDetail.value || !videoDetail.value?.videoUrl) {
    return;
  }
  nextTick(() => {
    scheduleDetailVideoAutoplayRetries();
    setTimeout(() => scheduleDetailVideoAutoplayRetries(), 150);
    setTimeout(() => scheduleDetailVideoAutoplayRetries(), 600);
  });
}

function getMenuByActiveKey() {
  const p = primaryMenus.find((m) => m.key === activeMenuKey.value);
  if (p) {
    return p;
  }
  const e = exploreMenus.find((m) => m.key === activeMenuKey.value);
  if (e) {
    return e;
  }
  return primaryMenus[0];
}

/** 离开详情 / 关闭创作中心后刷新列表，避免主区空白 */
async function reloadFeedAfterLeavingDetail() {
  if (showProfileCenter.value || showStudio.value) {
    return;
  }
  const menu = getMenuByActiveKey();
  await loadModuleCards(menu);
  if (menu.key === "shorts") {
    nextTick(() => {
      shortsFeedFgRef.value?.load();
      shortsFeedBgRef.value?.load();
    });
  }
}

async function openVideoDetail(card) {
  if (!card) {
    return;
  }
  detailAutoplayMutedFallback.value = false;
  const wasAlreadyInDetail = showVideoDetail.value;
  showVideoDetail.value = true;
  showProfileCenter.value = false;
  showStudio.value = false;
  uploadModalOpen.value = false;
  videoDetail.value = null;
  detailErrorMsg.value = "";
  detailLoading.value = true;
  const vid = parseVideoIdForApi(card.id);
  try {
    if (vid != null) {
      const raw = await fetchVideoDetail(vid);
      const d = normalizeVideoDetail(raw);
      if (d && card) {
        if (d.authorId == null && card.authorId != null) {
          d.authorId = card.authorId;
        }
        if (!d.authorAvatarUrl && card.authorAvatarUrl) {
          d.authorAvatarUrl = card.authorAvatarUrl;
        }
        const listNick = cardAuthorLabel(card);
        if (
          listNick &&
          listNick !== "—" &&
          (!d.authorNickname ||
            String(d.authorNickname).startsWith("用户 ") ||
            d.authorNickname === "频道")
        ) {
          d.authorNickname = listNick;
        }
      }
      videoDetail.value = d;
    } else {
      videoDetail.value = buildDetailFromCard(card);
    }
  } catch (e) {
    videoDetail.value = buildDetailFromCard(card);
    detailErrorMsg.value = `详情接口不可用，已展示本地数据：${e.message}`;
  } finally {
    detailLoading.value = false;
  }
  detailSidebarTab.value = "all";
  await loadDetailSidebar(true);
  nextTick(() => {
    requestAnimationFrame(() => {
      scheduleDetailVideoAutoplayRetries();
      setTimeout(() => scheduleDetailVideoAutoplayRetries(), 400);
    });
    const sc = detailSidebarScrollRef.value;
    if (sc) {
      sc.scrollTop = 0;
    }
    if (videoDetail.value) {
      snapshotVideoDetailForRestore(videoDetail.value);
      syncVideoDetailToAddressBar(wasAlreadyInDetail);
    }
  });
}

function closeVideoDetail() {
  detailAutoplayMutedFallback.value = false;
  try {
    detailPlayerRef.value?.pause?.();
  } catch {
    /* ignore */
  }
  const hasV = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
  if (hasV && typeof window.history.length === "number" && window.history.length > 1) {
    history.back();
    return;
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  detailErrorMsg.value = "";
  detailSidebarList.value = [];
  clearVideoDetailFromAddressBar();
}

function isSidebarItemNowPlaying(item) {
  return item && videoDetail.value && videoIdsEqual(item.id, videoDetail.value.id);
}

function setDetailSidebarTab(key) {
  if (detailSidebarTab.value === key) {
    return;
  }
  detailSidebarTab.value = key;
  loadDetailSidebar(true);
}

async function loadDetailSidebar(reset) {
  if (!videoDetail.value) {
    return;
  }
  if (reset) {
    detailSidebarPageNo.value = 1;
    detailSidebarList.value = [];
    detailSidebarHasMore.value = true;
  }
  if (!detailSidebarHasMore.value && !reset) {
    return;
  }
  if (detailSidebarLoading.value || detailSidebarLoadingMore.value) {
    return;
  }
  const authorId = videoDetail.value.authorId;
  if (authorId == null) {
    detailSidebarList.value = genMockSidebarList(detailSidebarTab.value);
    detailSidebarHasMore.value = false;
    return;
  }
  const isFirst = reset || detailSidebarList.value.length === 0;
  if (isFirst) {
    detailSidebarLoading.value = true;
  } else {
    detailSidebarLoadingMore.value = true;
  }
  try {
    const tab = detailSidebarTab.value;
    const vt = tab === "short" ? "SHORT_VIDEO" : "LONG_VIDEO";
    const res = await fetchAuthorVideos(authorId, {
      pageNo: detailSidebarPageNo.value,
      pageSize: DETAIL_SIDEBAR_PAGE_SIZE,
      videoType: vt,
    });
    let batch = res.list || [];
    if (tab === "long") {
      batch = batch.filter(isLongVideoItem);
    }
    if (reset) {
      detailSidebarList.value = batch;
    } else {
      detailSidebarList.value = [...detailSidebarList.value, ...batch];
    }
    detailSidebarPages.value = res.pages ?? 0;
    detailSidebarHasMore.value = detailSidebarPageNo.value < (res.pages || 0);
    if (detailSidebarHasMore.value) {
      detailSidebarPageNo.value += 1;
    }
  } catch {
    detailSidebarList.value = genMockSidebarList(detailSidebarTab.value);
    detailSidebarHasMore.value = false;
  } finally {
    detailSidebarLoading.value = false;
    detailSidebarLoadingMore.value = false;
  }
}

function onDetailSidebarScroll(e) {
  const el = e.target;
  if (!el) {
    return;
  }
  if (detailSidebarLoading.value || detailSidebarLoadingMore.value) {
    return;
  }
  if (!detailSidebarHasMore.value) {
    return;
  }
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 140) {
    loadDetailSidebar(false);
  }
}

function openVideoDetailFromSidebar(item) {
  if (!item || item.id === videoDetail.value?.id) {
    return;
  }
  openVideoDetail({
    id: item.id,
    title: item.title,
    author: item.author,
    authorNickname: item.authorNickname ?? item.author,
    viewsText: item.viewsText,
    videoUrl: item.videoUrl,
    coverUrl: item.coverUrl || item.avatarUrl,
    avatarUrl: item.coverUrl || item.avatarUrl,
    authorAvatarUrl: item.authorAvatarUrl,
    authorId: item.authorId,
    videoType: item.videoType,
  });
}

function onShortsFeedFgEnded() {
  const n = cards.value.length;
  if (n <= 1) {
    const v = shortsFeedFgRef.value;
    const bg = shortsFeedBgRef.value;
    if (v) {
      v.currentTime = 0;
      v.play()
        .then(() => bg?.play().catch(() => {}))
        .catch(() => {});
    }
    return;
  }
  shortsFeedNext();
}

async function loadModuleCards(menu, { fallback = true } = {}) {
  loading.value = true;
  try {
    if (menu.key === "home") {
      const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === homeCategoryKey.value);
      const res = await fetchVideoListPage({
        pageNo: 1,
        pageSize: 24,
        category: chip?.code || undefined,
        sort: "LATEST",
      });
      cards.value = res.list.length ? res.list : getMockCards(menu.label, menu.key);
      pageHint.value = `首页 · ${chip?.label ?? "全部"} · /videos/list`;
      return;
    }
    const list = await fetchVideosByModule(menu.key);
    if (menu.key === "shorts") {
      cards.value = list.length ? list : getShortsLocalFallbackCards();
    } else {
      cards.value = list.length ? list : getMockCards(menu.label, menu.key);
    }
    pageHint.value = `你正在查看「${menu.label}」模块。当前数据来源：Spring Boot 接口。`;
  } catch (error) {
    if (!fallback) {
      throw error;
    }
    if (menu.key === "home") {
      cards.value = getMockCards(menu.label, menu.key);
    } else {
      cards.value = menu.key === "shorts" ? getShortsLocalFallbackCards() : getMockCards(menu.label, menu.key);
    }
    pageHint.value = `接口暂不可用，已回退到本地示例数据。错误信息：${error.message}`;
  } finally {
    loading.value = false;
  }
}

async function setActiveMenu(menu) {
  showStudio.value = false;
  uploadModalOpen.value = false;
  if (showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  showProfileCenter.value = false;
  mediaPreviewOpen.value = false;
  activeMenuKey.value = menu.key;
  activeMenuLabel.value = menu.label;
  if (menu.key === "shorts") {
    shortsFeedIndex.value = 0;
    shortsFeedAutoplayPending.value = true;
  }
  await loadModuleCards(menu);
  if (menu.key === "shorts") {
    nextTick(() => {
      shortsFeedFgRef.value?.load();
      shortsFeedBgRef.value?.load();
    });
  }
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
  if (!isAuthed.value) {
    pageHint.value = "请先登录后再使用创作中心。";
    return;
  }
  if (showStudio.value) {
    uploadModalOpen.value = true;
    return;
  }
  showStudio.value = true;
  if (showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  showProfileCenter.value = false;
  mediaPreviewOpen.value = false;
  studioSideNav.value = "info";
  studioContentTab.value = "video";
  studioPageNo.value = 1;
  loadStudioVideos();
  try {
    history.pushState({ myyoutubeStudio: true }, "", window.location.href);
    studioHistoryPushed.value = true;
  } catch {
    studioHistoryPushed.value = false;
  }
}

function closeStudio() {
  if (studioHistoryPushed.value) {
    history.back();
    return;
  }
  showStudio.value = false;
  uploadModalOpen.value = false;
  void reloadFeedAfterLeavingDetail();
}

function selectStudioSideNav(key) {
  studioSideNav.value = key;
  if (key === "info") {
    loadStudioVideos();
  }
}

function selectHomeCategory(chip) {
  homeCategoryKey.value = chip.key;
  if (activeMenuKey.value === "home") {
    loadModuleCards(primaryMenus[0]);
  }
}

function switchStudioTab(key) {
  studioContentTab.value = key;
  studioPageNo.value = 1;
  loadStudioVideos();
}

async function loadStudioVideos() {
  if (studioSideNav.value !== "info") {
    return;
  }
  if (!getToken()) {
    studioMsg.value = "请先登录";
    return;
  }
  if (!currentUserId.value) {
    await loadProfileUser();
  }
  const uid = currentUserId.value;
  if (!uid) {
    studioMsg.value = "无法获取当前用户 ID";
    studioRows.value = [];
    return;
  }
  studioLoading.value = true;
  studioMsg.value = "";
  try {
    if (studioContentTab.value === "live") {
      studioRows.value = [];
      studioTotal.value = 0;
      studioPages.value = 0;
      studioMsg.value = "直播内容尚未接入，敬请期待。";
      return;
    }
    const vt = studioContentTab.value === "shorts" ? "SHORT_VIDEO" : "LONG_VIDEO";
    const res = await fetchAuthorVideos(uid, {
      pageNo: studioPageNo.value,
      pageSize: studioPageSize.value,
      videoType: vt,
    });
    studioRows.value = res.list;
    studioTotal.value = res.total;
    studioPages.value = res.pages;
  } catch (e) {
    studioRows.value = [];
    studioMsg.value = `加载失败：${e.message}`;
  } finally {
    studioLoading.value = false;
  }
}

function openUploadModal() {
  uploadModalOpen.value = true;
}

function closeUploadModal() {
  uploadModalOpen.value = false;
}

function triggerUploadPick() {
  uploadFileInputRef.value?.click();
}

function pickUploadFile(e) {
  if (e?.dataTransfer?.files?.length) {
    return e.dataTransfer.files[0];
  }
  if (e?.target?.files?.length) {
    return e.target.files[0];
  }
  return null;
}

function onUploadInputOrDrop(e) {
  const f = pickUploadFile(e);
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!f) {
    return;
  }
  pageHint.value = `已选择：${f.name}（视频文件需后端存储与发布接口，当前为前端占位）`;
  uploadModalOpen.value = false;
}

function onTopbarAvatarError(evt) {
  const el = evt?.target;
  if (el) {
    el.src = profileAvatar;
  }
}


function onNotify() {
  pageHint.value = "点击了通知按钮：后续可展示消息列表和未读状态。";
}

async function loadProfileUser() {
  if (!getToken()) {
    return;
  }
  profileLoading.value = true;
  profileMsg.value = "";
  try {
    if (profileTargetUserId.value == null) {
      const data = normalizeUserProfile(await getUserInfo());
      profileUser.value = data;
      if (data?.id != null) {
        currentUserId.value = data.id;
      }
    } else {
      try {
        profileUser.value = normalizeUserProfile(await getUserProfileById(profileTargetUserId.value));
      } catch {
        profileUser.value = buildMockOtherProfile(profileTargetUserId.value);
      }
    }
  } catch (error) {
    profileMsg.value = `加载用户信息失败：${error.message}`;
  } finally {
    profileLoading.value = false;
    applyCropsFromStorage();
  }
}

function openMediaPreview(type) {
  mediaPreviewType.value = type;
  const crop = type === "bg" ? cropRectBg.value : cropRectAvatar.value;
  cropRectDraft.value = { ...crop };
  cropNaturalSize.value = { w: 0, h: 0 };
  mediaPreviewOpen.value = true;
}

function closeMediaPreview() {
  if (isViewingSelf.value) {
    applyDraftCrop();
  }
  mediaPreviewOpen.value = false;
}

function pickMediaFromPreview() {
  const type = mediaPreviewType.value;
  closeMediaPreview();
  nextTick(() => {
    if (type === "avatar") {
      triggerAvatarPick();
    } else {
      triggerBgPick();
    }
  });
}

async function downloadMediaPreview() {
  const url = mediaPreviewSrc.value;
  if (!url) {
    return;
  }
  const base = mediaPreviewType.value === "avatar" ? "avatar" : "background";
  try {
    const res = await fetch(url, { mode: "cors" });
    if (!res.ok) {
      throw new Error("fetch failed");
    }
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${base}-${Date.now()}.jpg`;
    a.click();
    URL.revokeObjectURL(a.href);
  } catch {
    const a = document.createElement("a");
    a.href = url;
    a.target = "_blank";
    a.rel = "noopener";
    a.click();
  }
}

function triggerAvatarPick() {
  avatarFileInput.value?.click();
}

function triggerBgPick() {
  bgFileInput.value?.click();
}

async function onAvatarFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) {
    return;
  }
  profileUploadBusy.value = true;
  profileMsg.value = "";
  try {
    const url = await updateUserAvatar(file);
    if (profileUser.value) {
      profileUser.value = normalizeUserProfile({ ...profileUser.value, avatarUrl: url });
    } else {
      await loadProfileUser();
    }
    profileMsg.value = "头像已更新";
  } catch (error) {
    profileMsg.value = `头像上传失败：${error.message}`;
  } finally {
    profileUploadBusy.value = false;
  }
}

async function onBgFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) {
    return;
  }
  profileUploadBusy.value = true;
  profileMsg.value = "";
  try {
    const url = await updateUserBgImage(file);
    if (profileUser.value) {
      profileUser.value = normalizeUserProfile({ ...profileUser.value, bgImage: url });
    } else {
      await loadProfileUser();
    }
    profileMsg.value = "背景图已更新";
  } catch (error) {
    profileMsg.value = `背景图上传失败：${error.message}`;
  } finally {
    profileUploadBusy.value = false;
  }
}

function onProfile() {
  showStudio.value = false;
  uploadModalOpen.value = false;
  if (showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  profileTargetUserId.value = null;
  showProfileCenter.value = true;
  activeMenuLabel.value = "个人中心";
  pageHint.value = "这里展示用户主页、作品和数据概览。";
  loadProfileUser();
}

/** 进入他人主页（可从视频作者、关注列表等调用 userId；此处提供顶部「Ta」演示入口） */
function openOtherUserProfile(userId) {
  profileTargetUserId.value = userId;
  showProfileCenter.value = true;
  activeMenuLabel.value = "个人中心";
  pageHint.value = "正在查看他人主页。";
  loadProfileUser();
}

function openRelationTable(type) {
  relationFollowerMenuId.value = null;
  if (type === "following") {
    relationTableKind.value = "following";
    relationTableTitle.value = "关注列表";
    relationTableUsers.value = followingUsers;
  } else {
    relationTableKind.value = "followers";
    relationTableTitle.value = "粉丝列表";
    relationTableUsers.value = followerUsers;
  }
  relationTableOpen.value = true;
}

function closeRelationTable() {
  relationTableOpen.value = false;
  relationFollowerMenuId.value = null;
}

function toggleFollowerMenu(userId) {
  relationFollowerMenuId.value = relationFollowerMenuId.value === userId ? null : userId;
}

function onFollowerRemove(user) {
  relationFollowerMenuId.value = null;
  pageHint.value = `已移除粉丝「${user.nickName}」（演示，需对接后端）`;
}

function onFollowerFollowBack(user) {
  relationFollowerMenuId.value = null;
  pageHint.value = `已回关「${user.nickName}」（演示，需对接后端）`;
}

function onFollowingUnfollow(user) {
  pageHint.value = `已取关「${user.nickName}」（演示，需对接后端）`;
}

function openProfileEdit() {
  if (!isViewingSelf.value || !profileUser.value) {
    return;
  }
  profileEditForm.value = {
    nickName: profileUser.value.nickName || "",
    introduction: profileUser.value.introduction || "",
  };
  profileEditOpen.value = true;
}

function closeProfileEdit() {
  profileEditOpen.value = false;
}

async function saveProfileEdit() {
  if (!profileUser.value) {
    return;
  }
  profileEditSaving.value = true;
  profileMsg.value = "";
  const nick = profileEditForm.value.nickName.trim();
  const intro = profileEditForm.value.introduction.trim();
  const uid = profileUser.value.id;
  try {
    if (uid == null || uid === "") {
      throw new Error("缺少用户 ID，无法请求后端");
    }
    const data = await updateUserProfile(uid, { nickname: nick, introduction: intro });
    const merged = data && typeof data === "object" ? { ...profileUser.value, ...data } : { ...profileUser.value };
    profileUser.value = normalizeUserProfile({
      ...merged,
      nickName: nick,
      introduction: intro,
    });
    profileMsg.value = "资料已保存";
  } catch (error) {
    profileUser.value = normalizeUserProfile({
      ...profileUser.value,
      nickName: nick,
      introduction: intro,
    });
    profileMsg.value = `已保存到本地展示（接口未就绪或失败：${error.message}）`;
  } finally {
    profileEditSaving.value = false;
    profileEditOpen.value = false;
  }
}

function resolveVideoUrl(item) {
  return item?.videoUrl || item?.playUrl || item?.url || "";
}

function videoCardPlayable(card) {
  return Boolean(resolveVideoUrl(card));
}

const shortsFeedCurrent = computed(() => {
  const list = cards.value;
  if (!list.length) {
    return null;
  }
  const i = Math.min(Math.max(0, shortsFeedIndex.value), list.length - 1);
  return list[i];
});

const shortsFeedVideoSrc = computed(() => resolveVideoUrl(shortsFeedCurrent.value));

const shortsFeedAuthorAvatar = computed(() => {
  const c = shortsFeedCurrent.value;
  if (c?.avatarUrl) {
    return c.avatarUrl;
  }
  return getCardCover(shortsFeedIndex.value);
});

const shortsIsFollowedCurrent = computed(() => {
  const id = shortsFeedCurrent.value?.id;
  if (id == null) {
    return false;
  }
  return Boolean(shortsFollowedMap.value[String(id)]);
});

function toggleShortsFollow() {
  const id = shortsFeedCurrent.value?.id;
  if (id == null) {
    return;
  }
  const key = String(id);
  const next = { ...shortsFollowedMap.value };
  next[key] = !next[key];
  shortsFollowedMap.value = next;
}

function openShortVideo(item) {
  const url = resolveVideoUrl(item);
  if (!url) {
    pageHint.value = "该内容暂无视频地址";
    return;
  }
  shortPlayerVideoSrc.value = url;
  shortPlayerTitle.value = item.title || "视频";
  shortPlayerIsPortrait.value = false;
  shortPlayerOpen.value = true;
  nextTick(() => {
    shortFgVideoRef.value?.load();
    shortBgVideoRef.value?.load();
  });
}

function closeShortPlayer() {
  shortPlayerOpen.value = false;
  shortPlayerVideoSrc.value = "";
  shortFgVideoRef.value?.pause();
  shortBgVideoRef.value?.pause();
}

function onShortFgLoadedMetadata(e) {
  const v = e.target;
  shortPlayerIsPortrait.value =
    v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
}

function onShortFgTimeUpdate(e) {
  const fg = e.target;
  const bg = shortBgVideoRef.value;
  if (!bg || !fg) {
    return;
  }
  if (Math.abs(bg.currentTime - fg.currentTime) > 0.2) {
    bg.currentTime = fg.currentTime;
  }
}

function onShortFgPlay() {
  shortBgVideoRef.value?.play().catch(() => {});
}

function onShortFgPause() {
  shortBgVideoRef.value?.pause();
}

function onShortFgSeeked(e) {
  const fg = e.target;
  const bg = shortBgVideoRef.value;
  if (bg) {
    bg.currentTime = fg.currentTime;
  }
}

function onVideoCardClick(card) {
  if (activeMenuKey.value === "shorts") {
    return;
  }
  openVideoDetail(card);
}

function shortsFeedPrev() {
  const n = cards.value.length;
  if (!n) {
    return;
  }
  shortsFeedIndex.value = (shortsFeedIndex.value - 1 + n) % n;
}

function shortsFeedNext() {
  const n = cards.value.length;
  if (!n) {
    return;
  }
  shortsFeedIndex.value = (shortsFeedIndex.value + 1) % n;
}

function formatShortsTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) {
    return "0:00";
  }
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60);
  return `${m}:${s.toString().padStart(2, "0")}`;
}

async function onShortsFeedFgLoadedMetadata(e) {
  const v = e.target;
  shortsFeedIsPortrait.value =
    v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
  shortsFeedDuration.value = Number.isFinite(v.duration) ? v.duration : 0;
  shortsFeedCurrentTime.value = v.currentTime || 0;
  v.volume = shortsFeedVolume.value;
  v.muted = shortsFeedMuted.value;
  shortsFeedVolume.value = v.volume;
  shortsFeedMuted.value = v.muted;
  if (shortsFeedAutoplayPending.value) {
    shortsFeedAutoplayPending.value = false;
    try {
      await v.play();
      await shortsFeedBgRef.value?.play().catch(() => {});
    } catch {
      /* 浏览器策略可能阻止自动播放，此时显示中央播放按钮 */
    }
  }
  shortsFeedPaused.value = v.paused;
}

function onShortsFeedFgTimeUpdate(e) {
  const fg = e.target;
  shortsFeedCurrentTime.value = fg.currentTime;
  const bg = shortsFeedBgRef.value;
  if (!bg || !fg) {
    return;
  }
  if (Math.abs(bg.currentTime - fg.currentTime) > 0.2) {
    bg.currentTime = fg.currentTime;
  }
}

function onShortsScrubInput(e) {
  const v = shortsFeedFgRef.value;
  if (!v) {
    return;
  }
  const t = Number(e.target.value);
  v.currentTime = t;
  shortsFeedCurrentTime.value = t;
}

function onShortsVolumeInput(e) {
  const v = shortsFeedFgRef.value;
  if (!v) {
    return;
  }
  const vol = Number(e.target.value);
  v.volume = vol;
  v.muted = vol === 0;
  shortsFeedVolume.value = vol;
  shortsFeedMuted.value = v.muted;
}

function shortsToggleMute() {
  const v = shortsFeedFgRef.value;
  if (!v) {
    return;
  }
  v.muted = !v.muted;
  shortsFeedMuted.value = v.muted;
}

function shortsTogglePlay() {
  const v = shortsFeedFgRef.value;
  if (!v) {
    return;
  }
  if (v.paused) {
    v.play().catch(() => {});
  } else {
    v.pause();
  }
}

async function shortsRequestPip() {
  const v = shortsFeedFgRef.value;
  if (!v) {
    return;
  }
  try {
    if (document.pictureInPictureElement === v) {
      await document.exitPictureInPicture();
    } else if (v.requestPictureInPicture) {
      await v.requestPictureInPicture();
    }
  } catch {
    /* 浏览器策略或用户拒绝 */
  }
}

async function shortsRequestFullscreen() {
  const el = shortsFeedCenterRef.value;
  if (!el) {
    return;
  }
  try {
    if (document.fullscreenElement === el) {
      await document.exitFullscreen();
    } else {
      await el.requestFullscreen();
    }
  } catch {
    /* ignore */
  }
}

function onShortsFeedKeydown(ev) {
  if (showProfileCenter.value || activeMenuKey.value !== "shorts") {
    return;
  }
  const tag = ev.target?.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable) {
    return;
  }
  const k = ev.key;
  if (k === "ArrowUp" || k === "w" || k === "W") {
    ev.preventDefault();
    shortsFeedPrev();
  } else if (k === "ArrowDown" || k === "s" || k === "S") {
    ev.preventDefault();
    shortsFeedNext();
  }
}

function onShortsFeedFgPlay() {
  shortsFeedPaused.value = false;
  shortsFeedBgRef.value?.play().catch(() => {});
}

function onShortsFeedFgPause() {
  shortsFeedPaused.value = true;
  shortsFeedBgRef.value?.pause();
}

function onShortsFeedFgSeeked(e) {
  const fg = e.target;
  const bg = shortsFeedBgRef.value;
  if (bg) {
    bg.currentTime = fg.currentTime;
  }
}

function pauseShortsFeedVideos() {
  shortsFeedFgRef.value?.pause();
  shortsFeedBgRef.value?.pause();
}

/** 短视频：滚轮 / 触摸上下切换（与常见 App 一致：上滑下一条、下滑上一条） */
let shortsWheelCooldown = false;
function onShortsFeedWheel(e) {
  e.preventDefault();
  if (shortsWheelCooldown) {
    return;
  }
  const dy = e.deltaY;
  if (Math.abs(dy) < 18) {
    return;
  }
  shortsWheelCooldown = true;
  if (dy > 0) {
    shortsFeedNext();
  } else {
    shortsFeedPrev();
  }
  window.setTimeout(() => {
    shortsWheelCooldown = false;
  }, 420);
}

const shortsTouchStartY = ref(0);

function onShortsFeedTouchStart(ev) {
  const t = ev.touches?.[0]?.clientY;
  if (t != null) {
    shortsTouchStartY.value = t;
  }
}

function onShortsFeedTouchEnd(ev) {
  const y = ev.changedTouches[0]?.clientY ?? shortsTouchStartY.value;
  const dy = shortsTouchStartY.value - y;
  if (Math.abs(dy) < 48) {
    return;
  }
  if (dy > 0) {
    shortsFeedNext();
  } else {
    shortsFeedPrev();
  }
}

function onWorkCardClick(item) {
  if (!resolveVideoUrl(item)) {
    return;
  }
  openShortVideo(item);
}

function onShortPlayerKeydown(ev) {
  if (ev.key === "Escape") {
    closeShortPlayer();
  }
}

watch(shortPlayerOpen, (open) => {
  if (open) {
    window.addEventListener("keydown", onShortPlayerKeydown);
  } else {
    window.removeEventListener("keydown", onShortPlayerKeydown);
  }
});

watch(cards, (list) => {
  if (!list.length) {
    shortsFeedIndex.value = 0;
    return;
  }
  if (shortsFeedIndex.value >= list.length) {
    shortsFeedIndex.value = 0;
  }
});

watch(activeMenuKey, (key) => {
  if (key !== "shorts") {
    pauseShortsFeedVideos();
  }
});

watch(
  () => !showProfileCenter.value && activeMenuKey.value === "shorts",
  (on) => {
    if (on) {
      window.addEventListener("keydown", onShortsFeedKeydown);
    } else {
      window.removeEventListener("keydown", onShortsFeedKeydown);
    }
  },
  { immediate: true }
);

watch(shortsFeedIndex, () => {
  shortsFeedIsPortrait.value = false;
  shortsFeedAutoplayPending.value = true;
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
});

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
    await loadProfileUser();
    const v = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
    if (v) {
      const ok = await restoreVideoDetailFromUrl(v);
      if (!ok) {
        clearVideoDetailFromAddressBar();
        await loadModuleCards(homeMenu);
      }
    } else {
      await loadModuleCards(homeMenu);
    }
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
  showProfileCenter.value = false;
  showStudio.value = false;
  studioHistoryPushed.value = false;
  uploadModalOpen.value = false;
  clearVideoDetailFromAddressBar();
  showVideoDetail.value = false;
  videoDetail.value = null;
  profileUser.value = null;
  currentUserId.value = null;
  profileTargetUserId.value = null;
  mediaPreviewOpen.value = false;
  authMessage.value = "你已退出登录。";
}

function onWindowPopState() {
  if (showStudio.value) {
    showStudio.value = false;
    uploadModalOpen.value = false;
    studioHistoryPushed.value = false;
    void reloadFeedAfterLeavingDetail();
    return;
  }
  const v = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
  if (!v && showVideoDetail.value) {
    detailAutoplayMutedFallback.value = false;
    try {
      detailPlayerRef.value?.pause?.();
    } catch {
      /* ignore */
    }
    showVideoDetail.value = false;
    videoDetail.value = null;
    detailErrorMsg.value = "";
    detailSidebarList.value = [];
    void reloadFeedAfterLeavingDetail();
  }
}

watch(
  () => [showVideoDetail.value, detailLoading.value, videoDetail.value?.videoUrl],
  ([show, loading, url]) => {
    if (!show || loading || !url) {
      return;
    }
    nextTick(() => {
      requestAnimationFrame(() => {
        scheduleDetailVideoAutoplayRetries();
        setTimeout(() => scheduleDetailVideoAutoplayRetries(), 500);
      });
    });
  },
  { flush: "post" },
);

onMounted(async () => {
  if (!isAuthed.value) {
    return;
  }
  await loadProfileUser();
  const homeMenu = primaryMenus[0];
  const v = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
  if (v) {
    const ok = await restoreVideoDetailFromUrl(v);
    if (!ok) {
      clearVideoDetailFromAddressBar();
      await loadModuleCards(homeMenu);
    } else {
      await nextTick();
      requestAnimationFrame(() => {
        scheduleDetailVideoAutoplayRetries();
        setTimeout(() => scheduleDetailVideoAutoplayRetries(), 400);
        setTimeout(() => scheduleDetailVideoAutoplayRetries(), 1200);
      });
    }
  } else {
    await loadModuleCards(homeMenu);
  }
  window.addEventListener("popstate", onWindowPopState);
  window.addEventListener("pageshow", onWindowPageShow);
});

onBeforeUnmount(() => {
  window.removeEventListener("popstate", onWindowPopState);
  window.removeEventListener("pageshow", onWindowPageShow);
  if (loginCaptchaTimer) clearInterval(loginCaptchaTimer);
  if (registerCaptchaTimer) clearInterval(registerCaptchaTimer);
  if (forgotCaptchaTimer) clearInterval(forgotCaptchaTimer);
  if (heroResizeObserver) {
    heroResizeObserver.disconnect();
    heroResizeObserver = null;
  }
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
    <aside v-if="!showStudio && !showVideoDetail" class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <button class="icon-btn" title="折叠侧边栏" @click="sidebarCollapsed = !sidebarCollapsed">☰</button>
        <h1 class="brand">MyYoutube</h1>
      </div>

      <nav class="nav-section">
        <template v-for="menu in primaryMenus" :key="menu.key">
        <button
          class="nav-item"
          :class="{ active: menu.key === activeMenuKey }"
          @click="setActiveMenu(menu)"
        >
            <span class="nav-icon"><i :class="menu.icon"></i></span>
          <span class="nav-label">{{ menu.label }}</span>
        </button>
        </template>
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
            <span class="nav-icon"><i :class="menu.icon"></i></span>
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
          <button v-if="showVideoDetail" type="button" class="action-btn" @click="closeVideoDetail">返回</button>
          <button v-else-if="showStudio" type="button" class="action-btn" @click="closeStudio">返回</button>
          <button class="action-btn" @click="onCreate">{{ showStudio ? "上传视频" : "创建" }}</button>
          <button class="action-btn" @click="onNotify">通知</button>
          <button
            type="button"
            class="avatar-btn avatar-btn--img"
            title="个人中心"
            aria-label="个人中心"
            @click="onProfile"
          >
            <img :src="topbarAvatarSrc" alt="" class="topbar-avatar-img" @error="onTopbarAvatarError" />
          </button>
          <button class="action-btn" @click="logout">退出</button>
        </div>
      </header>

      <main
        v-show="!showVideoDetail"
        class="content"
        :class="{
          'content--shorts':
            !showProfileCenter && activeMenuKey === 'shorts' && !showStudio && !showVideoDetail,
          'content--studio': showStudio,
        }"
      >
        <!-- 创作中心（YouTube Studio 风格） -->
        <div v-if="showStudio" class="studio-page">
          <aside class="studio-sidebar">
            <div class="studio-sidebar-user">
              <img :src="topbarAvatarSrc" alt="" class="studio-sidebar-avatar" />
              <div class="studio-sidebar-meta">
                <span class="studio-sidebar-title">我的频道</span>
                <span class="studio-sidebar-name">{{ displayNickname }}</span>
              </div>
            </div>
            <nav class="studio-side-nav">
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'info' }"
                @click="selectStudioSideNav('info')"
              >
                <i class="ri-dashboard-3-line"></i> 信息中心
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'data' }"
                @click="selectStudioSideNav('data')"
              >
                <i class="ri-bar-chart-2-line"></i> 数据分析
              </button>
              <button
                type="button"
                class="studio-side-link"
                :class="{ active: studioSideNav === 'audio' }"
                @click="selectStudioSideNav('audio')"
              >
                <i class="ri-music-2-line"></i> 音频库
              </button>
            </nav>
          </aside>
          <div class="studio-body">
            <div class="studio-body-head">
              <h2 class="studio-body-title">频道内容</h2>
              <div v-if="studioSideNav === 'info'" class="studio-tabs">
                <button
                  v-for="tab in studioTabs"
                  :key="tab.key"
                  type="button"
                  class="studio-tab"
                  :class="{ active: studioContentTab === tab.key }"
                  @click="switchStudioTab(tab.key)"
                >
                  {{ tab.label }}
                </button>
              </div>
            </div>
            <div v-if="studioSideNav === 'data'" class="studio-placeholder-card">
              <p>数据分析功能开发中，敬请期待。</p>
            </div>
            <div v-else-if="studioSideNav === 'audio'" class="studio-placeholder-card">
              <p>音频库功能开发中，敬请期待。</p>
            </div>
            <template v-else>
              <div v-if="studioContentTab === 'live'" class="studio-placeholder-card">
                <p>{{ studioMsg || "直播内容尚未接入。" }}</p>
              </div>
              <div v-else-if="studioLoading" class="studio-placeholder-card">加载中…</div>
              <div v-else class="studio-table-panel">
                <table class="studio-table">
                  <thead>
                    <tr>
                      <th>视频</th>
                      <th>公开范围</th>
                      <th>日期</th>
                      <th>观看次数</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!studioRows.length">
                      <td colspan="4" class="studio-table-empty">没有可显示的内容</td>
                    </tr>
                    <tr v-for="row in studioRows" :key="row.id">
                      <td>
                        <div class="studio-td-video">
                          <img :src="row.avatarUrl || profileBanner" alt="" class="studio-thumb" />
                          <span>{{ row.title }}</span>
                        </div>
                      </td>
                      <td>公开</td>
                      <td>—</td>
                      <td>{{ row.viewsText }}</td>
                    </tr>
                  </tbody>
                </table>
                <div class="studio-pager">
                  <button
                    type="button"
                    class="studio-pager-btn"
                    :disabled="studioPageNo <= 1"
                    @click="studioPageNo--; loadStudioVideos()"
                  >
                    上一页
                  </button>
                  <span class="studio-pager-info">{{ studioPageNo }} / {{ Math.max(1, studioPages || 1) }}</span>
                  <button
                    type="button"
                    class="studio-pager-btn"
                    :disabled="studioPageNo >= (studioPages || 1)"
                    @click="studioPageNo++; loadStudioVideos()"
                  >
                    下一页
                  </button>
                </div>
                <p v-if="studioMsg && studioContentTab !== 'live'" class="studio-hint">{{ studioMsg }}</p>
                <button type="button" class="studio-upload-fab" @click="openUploadModal">上传视频</button>
              </div>
            </template>
          </div>
        </div>

        <template v-else>
        <div v-if="activeMenuKey === 'home' && !showProfileCenter" class="home-feed-header">
          <nav class="home-category-bar" role="navigation" aria-label="视频分类">
            <button
              v-for="chip in VIDEO_CATEGORY_CHIPS"
              :key="chip.key"
              type="button"
              class="home-chip"
              :class="{ 'home-chip--active': homeCategoryKey === chip.key }"
              @click="selectHomeCategory(chip)"
            >
              {{ chip.label }}
            </button>
          </nav>
        </div>

        <section v-if="showProfileCenter" class="profile-page">
          <input
            ref="avatarFileInput"
            type="file"
            accept="image/*"
            class="visually-hidden"
            aria-hidden="true"
            @change="onAvatarFileChange"
          />
          <input
            ref="bgFileInput"
            type="file"
            accept="image/*"
            class="visually-hidden"
            aria-hidden="true"
            @change="onBgFileChange"
          />
          <p v-if="profileLoading" class="profile-api-hint">正在加载用户信息…</p>
          <p v-else-if="profileMsg" class="profile-api-hint">{{ profileMsg }}</p>

          <div class="profile-top-row">
            <aside class="profile-side-card">
              <button
                type="button"
                class="profile-avatar-wrap"
                title="查看头像"
                @click="openMediaPreview('avatar')"
              >
                <img
                  v-if="!isViewingSelf"
                  :src="profileDisplayAvatar"
                  alt="用户头像"
                  class="profile-avatar-sm"
                  @error="onProfileAvatarImgError"
                />
                <div
                  v-else
                  class="profile-avatar-sm profile-avatar-crop-inner"
                  role="img"
                  :aria-label="displayNickname"
                >
                  <img
                    :src="profileDisplayAvatar"
                    :class="['profile-avatar-img-el', avatarImgUsePrecise ? 'profile-avatar-img-el--precise' : 'profile-avatar-img-el--cover']"
                    :style="avatarImgStyle"
                    alt=""
                    draggable="false"
                    @load="onProfileAvatarNaturalLoad"
                    @error="onProfileAvatarImgError"
                  />
                </div>
              </button>
              <h4>个人资料</h4>
              <p>昵称：{{ displayNickname }}</p>
              <p class="profile-id-line">ID：{{ profileUser?.id ?? "—" }}</p>
              <div class="profile-stat-list">
                <button type="button" class="profile-stat-row profile-stat-btn" @click="openRelationTable('following')">
                  <span class="profile-stat-left">关注 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
                </button>
                <button type="button" class="profile-stat-row profile-stat-btn" @click="openRelationTable('followers')">
                  <span class="profile-stat-left">粉丝 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.fans ?? "—" }}</strong>
                </button>
                <div class="profile-stat-row">
                  <span>获赞</span>
                  <strong>{{ profileUser?.likeCount ?? "—" }}</strong>
                </div>
                <div class="profile-stat-row">
                  <span>作品数</span>
                  <strong>{{ profileUser?.videoCount ?? profileWorks.length }}</strong>
                </div>
              </div>
              <div
                class="profile-intro"
                :class="{ 'profile-intro--editable': isViewingSelf }"
                :role="isViewingSelf ? 'button' : undefined"
                :tabindex="isViewingSelf ? 0 : undefined"
                @click="isViewingSelf && openProfileEdit()"
                @keydown.enter.prevent="isViewingSelf && openProfileEdit()"
              >
                <h5>个人简介</h5>
                <p>{{ introDisplayText }}</p>
                <span v-if="isViewingSelf" class="profile-intro-edit-hint">点击编辑资料</span>
              </div>
            </aside>

            <section class="profile-hero-col">
              <button
                type="button"
                class="profile-hero profile-hero-clickable profile-hero--stretch"
                title="查看背景图"
                @click="openMediaPreview('bg')"
              >
                <div ref="heroCropContainerRef" class="profile-hero-img" role="img" aria-hidden="true">
                  <img
                    :src="profileDisplayBg"
                    :class="['profile-hero-img-el', heroImgUsePrecise ? 'profile-hero-img-el--precise' : 'profile-hero-img-el--cover']"
                    :style="heroImgStyle"
                    alt=""
                    draggable="false"
                    @load="onProfileHeroBgLoad"
                    @error="onProfileBgImgError"
                  />
                </div>
                <div class="profile-hero-mask"></div>
              </button>
            </section>
          </div>
          <section class="profile-works-section">
              <div class="profile-works-title">
                <h4>作品列表</h4>
                <span>共 {{ profileWorks.length }} 条</span>
              </div>
              <div class="works-grid works-grid-profile">
                <article
                  v-for="item in profileWorks"
                  :key="item.id"
                  class="work-card"
                  :class="{ 'work-card--playable': resolveVideoUrl(item) }"
                  @click="onWorkCardClick(item)"
                >
                  <img :src="item.image" :alt="item.title" />
                  <div class="work-overlay">
                    <span class="work-like"><i class="ri-heart-line"></i> {{ item.likes }}</span>
                    <p>{{ item.title }}</p>
                  </div>
                </article>
              </div>
          </section>
        </section>

        <div v-if="mediaPreviewOpen" class="modal-overlay modal-overlay--light" @click.self="closeMediaPreview">
          <div class="modal-card media-preview-card media-preview-card--light" role="dialog" aria-modal="true" @click.stop>
            <h3 class="media-preview-title media-preview-title--light">
              {{ mediaPreviewType === "avatar" ? "头像" : "背景图" }}
            </h3>
            <p v-if="isViewingSelf" class="media-preview-hint">
              拖动方框选择要展示的区域；可拖右下角调整大小。关闭窗口后生效并保存在本机。
            </p>
            <div class="media-preview-body media-preview-body--stack">
              <div class="media-preview-crop-stage">
                <div class="media-preview-crop-stage-inner">
                  <img
                    class="media-preview-full-img"
                    :src="mediaPreviewSrc"
                    :alt="mediaPreviewType === 'avatar' ? '头像预览' : '背景预览'"
                    draggable="false"
                    @load="onCropImgLoad"
                    @error="onMediaPreviewImgError"
                  />
                  <div
                    v-if="isViewingSelf"
                    class="crop-box"
                    :style="cropBoxStyle"
                    @pointerdown="onCropBoxDragStart"
                  >
                    <div class="crop-handle-br" title="调整选区大小" @pointerdown.stop="onCropResizeStart" />
                  </div>
                </div>
              </div>
              <div class="media-preview-side media-preview-side--light">
                <button
                  v-if="isViewingSelf"
                  type="button"
                  class="profile-action-btn primary"
                  :disabled="profileUploadBusy"
                  @click="pickMediaFromPreview"
                >
                  {{ profileUploadBusy ? "处理中…" : "更换图片" }}
                </button>
                <button v-else type="button" class="profile-action-btn primary" @click="downloadMediaPreview">
                  下载图片
                </button>
                <button type="button" class="profile-action-btn ghost" @click="closeMediaPreview">关闭</button>
              </div>
            </div>
          </div>
        </div>

        <div
          v-if="relationTableOpen"
          class="modal-overlay modal-overlay--light relation-modal-overlay"
          @click.self="closeRelationTable"
        >
          <div class="modal-card relation-table-card relation-table-card--white" @click="relationFollowerMenuId = null">
            <h3 class="relation-table-heading">{{ relationTableTitle }}</h3>
            <div class="relation-table-wrap relation-table-wrap--white">
              <table class="relation-table relation-table--white">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>头像</th>
                    <th>昵称</th>
                    <th class="relation-th-action">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="user in relationTableUsers" :key="user.id">
                    <td>{{ user.id }}</td>
                    <td>
                      <img class="relation-avatar" :src="user.avatarUrl" alt="" />
                    </td>
                    <td>{{ user.nickName }}</td>
                    <td class="relation-action-cell">
                      <template v-if="relationTableKind === 'followers'">
                        <button
                          type="button"
                          class="relation-more-btn"
                          aria-label="更多操作"
                          @click.stop="toggleFollowerMenu(user.id)"
                        >
                          <i class="ri-more-2-fill"></i>
                        </button>
                        <div
                          v-if="relationFollowerMenuId === user.id"
                          class="relation-flyout"
                          @click.stop
                        >
                          <button type="button" class="relation-flyout-btn" @click="onFollowerRemove(user)">
                            移除
                          </button>
                          <button type="button" class="relation-flyout-btn relation-flyout-btn--primary" @click="onFollowerFollowBack(user)">
                            回关
                          </button>
                        </div>
                      </template>
                      <template v-else>
                        <button type="button" class="relation-unfollow-btn" @click="onFollowingUnfollow(user)">
                          取关
                        </button>
                      </template>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <button type="button" class="relation-modal-close" @click="closeRelationTable">关闭</button>
          </div>
        </div>

        <div
          v-if="profileEditOpen"
          class="modal-overlay modal-overlay--light profile-edit-overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="profile-edit-title"
          @click.self="closeProfileEdit"
        >
          <div class="modal-card profile-edit-modal" @click.stop>
            <h3 id="profile-edit-title" class="profile-edit-title">编辑资料</h3>
            <div class="profile-edit-form">
              <label class="profile-edit-label">
                <span>昵称</span>
                <input
                  v-model="profileEditForm.nickName"
                  type="text"
                  maxlength="64"
                  autocomplete="nickname"
                  placeholder="请输入昵称"
                />
              </label>
              <label class="profile-edit-label">
                <span>个人简介</span>
                <textarea
                  v-model="profileEditForm.introduction"
                  rows="4"
                  maxlength="500"
                  placeholder="介绍一下自己…"
                ></textarea>
              </label>
            </div>
            <div class="profile-edit-actions">
              <button type="button" class="profile-edit-btn profile-edit-btn--ghost" @click="closeProfileEdit">取消</button>
              <button
                type="button"
                class="profile-edit-btn profile-edit-btn--primary"
                :disabled="profileEditSaving"
                @click="saveProfileEdit"
              >
                {{ profileEditSaving ? "保存中…" : "保存" }}
              </button>
            </div>
          </div>
        </div>

        <div
          v-if="shortPlayerOpen"
          class="modal-overlay short-player-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="短视频播放"
          @click.self="closeShortPlayer"
        >
          <div class="short-player-shell" @click.stop>
            <div class="short-player-head">
              <span class="short-player-title">{{ shortPlayerTitle }}</span>
              <button type="button" class="short-player-close" aria-label="关闭" @click="closeShortPlayer">
                <i class="ri-close-line"></i>
              </button>
            </div>
            <div
              class="short-video-stage"
              :class="{
                'short-video-stage--portrait': shortPlayerIsPortrait,
                'short-video-stage--landscape': !shortPlayerIsPortrait,
              }"
            >
              <video
                ref="shortBgVideoRef"
                class="short-video-bg"
                muted
                playsinline
                preload="metadata"
                :src="shortPlayerVideoSrc"
              ></video>
              <video
                ref="shortFgVideoRef"
                class="short-video-fg"
                controls
                playsinline
                preload="metadata"
                :src="shortPlayerVideoSrc"
                @loadedmetadata="onShortFgLoadedMetadata"
                @timeupdate="onShortFgTimeUpdate"
                @play="onShortFgPlay"
                @pause="onShortFgPause"
                @seeked="onShortFgSeeked"
              ></video>
            </div>
          </div>
        </div>

        <section v-if="!showProfileCenter && activeMenuKey === 'shorts'" class="shorts-feed">
          <div v-if="loading" class="shorts-feed-loading">正在加载短视频…</div>
          <template v-else-if="shortsFeedVideoSrc">
            <div class="shorts-feed-body">
              <div
                ref="shortsFeedCenterRef"
                class="shorts-feed-center"
                @wheel.prevent="onShortsFeedWheel"
                @touchstart="onShortsFeedTouchStart"
                @touchend="onShortsFeedTouchEnd"
              >
                <div class="shorts-feed-stage-wrap">
                  <div
                    class="short-video-stage short-video-stage--feed"
                    :class="{
                      'short-video-stage--portrait': shortsFeedIsPortrait,
                      'short-video-stage--landscape': !shortsFeedIsPortrait,
                    }"
                  >
                    <video
                      ref="shortsFeedBgRef"
                      class="short-video-bg"
                      muted
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                    ></video>
                    <video
                      :key="shortsFeedCurrent?.id"
                      ref="shortsFeedFgRef"
                      class="short-video-fg"
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                      @click="shortsTogglePlay"
                      @loadedmetadata="onShortsFeedFgLoadedMetadata"
                      @timeupdate="onShortsFeedFgTimeUpdate"
                      @play="onShortsFeedFgPlay"
                      @pause="onShortsFeedFgPause"
                      @seeked="onShortsFeedFgSeeked"
                      @ended="onShortsFeedFgEnded"
                    ></video>
                    <div
                      v-show="shortsFeedPaused"
                      class="shorts-feed-pause-overlay"
                      @click.stop="shortsTogglePlay"
                    >
                      <span class="shorts-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="shorts-feed-nav-side" aria-label="切换视频">
                    <button type="button" class="shorts-feed-nav-line" title="上一条" @click.stop="shortsFeedPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="shorts-feed-nav-line" title="下一条" @click.stop="shortsFeedNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="shorts-feed-ui">
                    <div class="shorts-feed-caption">
                      <p class="shorts-feed-user">@{{ shortsFeedCurrent?.author || "创作者" }}</p>
                      <p class="shorts-feed-title">{{ shortsFeedCurrent?.title }}</p>
                      <p class="shorts-feed-meta">{{ shortsFeedCurrent?.viewsText || "观看" }}</p>
                    </div>
                    <div class="shorts-feed-action-col">
                      <div class="shorts-feed-author-block">
                        <div class="shorts-feed-author-avatar-wrap">
                          <img class="shorts-feed-author-avatar" :src="shortsFeedAuthorAvatar" alt="" />
                          <button
                            v-if="!shortsIsFollowedCurrent"
                            type="button"
                            class="shorts-feed-follow-btn"
                            title="关注"
                            aria-label="关注"
                            @click.stop="toggleShortsFollow"
                          >
                            <i class="ri-add-line"></i>
                          </button>
                          <button
                            v-else
                            type="button"
                            class="shorts-feed-follow-btn shorts-feed-follow-btn--followed"
                            title="已关注"
                            aria-label="已关注"
                            @click.stop="toggleShortsFollow"
                          >
                            <i class="ri-check-line"></i>
                          </button>
                        </div>
                      </div>
                      <button type="button" class="shorts-feed-action-btn" title="点赞" aria-label="点赞">
                        <i class="ri-heart-fill"></i>
                      </button>
                      <button type="button" class="shorts-feed-action-btn" title="评论" aria-label="评论">
                        <i class="ri-chat-3-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-action-btn" title="收藏" aria-label="收藏">
                        <i class="ri-star-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-action-btn" title="分享" aria-label="分享">
                        <i class="ri-share-forward-line"></i>
                      </button>
                    </div>
                  </div>
                  <div class="shorts-feed-bottom-bar">
                    <input
                      type="range"
                      class="shorts-feed-scrub"
                      min="0"
                      :max="Math.max(shortsFeedDuration, 0.01)"
                      step="0.1"
                      :value="shortsFeedCurrentTime"
                      @input="onShortsScrubInput"
                    />
                    <div class="shorts-feed-bottom-bar__row">
                      <span class="shorts-feed-time">
                        {{ formatShortsTime(shortsFeedCurrentTime) }} / {{ formatShortsTime(shortsFeedDuration) }}
                      </span>
                      <div class="shorts-feed-tools">
                        <button type="button" class="shorts-feed-tool-btn" title="小窗播放（画中画）" @click="shortsRequestPip">
                          <i class="ri-picture-in-picture-line"></i>
                        </button>
                        <button type="button" class="shorts-feed-tool-btn" title="静音" @click="shortsToggleMute">
                          <i :class="shortsFeedMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                        </button>
                        <input
                          type="range"
                          class="shorts-feed-vol-range"
                          min="0"
                          max="1"
                          step="0.05"
                          :value="shortsFeedVolume"
                          title="音量"
                          aria-label="音量"
                          @input="onShortsVolumeInput"
                        />
                        <button type="button" class="shorts-feed-tool-btn" title="全屏" @click="shortsRequestFullscreen">
                          <i class="ri-fullscreen-line"></i>
                        </button>
                        <button
                          type="button"
                          class="shorts-feed-tool-btn shorts-feed-tool-btn--playpause"
                          :title="shortsFeedPaused ? '播放' : '暂停'"
                          @click="shortsTogglePlay"
                        >
                          <i :class="shortsFeedPaused ? 'ri-play-fill' : 'ri-pause-fill'"></i>
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="shorts-feed-empty">暂无短视频可播放（缺少视频地址）</div>
        </section>

        <section v-if="!showProfileCenter && activeMenuKey !== 'shorts'" class="card-grid">
          <article v-if="loading" class="video-card">
            <div class="video-cover video-cover-loading">加载中...</div>
            <div class="video-info">
              <strong>正在请求后端数据</strong>
              <span>请稍候</span>
            </div>
          </article>

          <article
            v-for="(card, index) in cards"
            :key="card.id"
            class="video-card"
            :class="{ 'video-card--playable': videoCardPlayable(card) }"
            @click="onVideoCardClick(card)"
          >
            <img class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
            <div class="video-info">
              <img
                class="video-card-author-avatar"
                :src="cardAuthorAvatar(card, index)"
                alt=""
                @error="(e) => (e.target.src = profileAvatar)"
              />
              <div class="video-info-text">
                <strong>{{ card.title }}</strong>
                <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
              </div>
            </div>
          </article>
        </section>
        </template>
      </main>

      <div v-if="showVideoDetail" class="video-detail-page video-detail-page--layer">
        <div v-if="detailLoading" class="video-detail-loading">加载中…</div>
        <template v-else-if="videoDetail">
          <div class="video-detail-layout">
            <div class="video-detail-main">
              <div class="video-detail-player-wrap">
                <video
                  v-if="videoDetail.videoUrl"
                  :key="String(videoDetail.id)"
                  ref="detailPlayerRef"
                  class="video-detail-player"
                  controls
                  playsinline
                  preload="auto"
                  autoplay
                  :poster="videoDetail.coverUrl || undefined"
                  :src="videoDetail.videoUrl"
                  @canplay="onDetailVideoCanPlay"
                  @loadeddata="() => void tryDetailVideoAutoplay()"
                  @loadedmetadata="() => void tryDetailVideoAutoplay()"
                  @canplaythrough="() => void tryDetailVideoAutoplay()"
                  @volumechange="onDetailVideoVolumeChange"
                />
                <div v-else class="video-detail-player-empty">暂无播放地址</div>
                <p v-if="detailAutoplayMutedFallback" class="video-detail-autoplay-hint">
                  浏览器限制自动播放已静音，请点播放器音量图标开声
                </p>
              </div>
              <h1 class="video-detail-title">{{ videoDetail.title }}</h1>
              <div class="video-detail-meta-row">
                <div class="video-detail-channel">
                  <div class="video-detail-channel-avatar-wrap">
                    <img
                      :src="detailAuthorAvatar"
                      alt=""
                      class="video-detail-channel-avatar"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                  </div>
                  <div class="video-detail-channel-text">
                    <strong>{{ videoDetail.authorNickname }}</strong>
                    <span>{{ detailFanText }}</span>
                  </div>
                  <button type="button" class="video-detail-subscribe" disabled>关注</button>
                </div>
                <div class="video-detail-actions">
                  <button type="button" class="video-detail-action-pill" disabled>
                    <i class="ri-thumb-up-line"></i> {{ videoDetail.likeCount ?? 0 }}
                  </button>
                  <button type="button" class="video-detail-action-pill" disabled>
                    <i class="ri-share-forward-line"></i> 分享
                  </button>
                </div>
              </div>
              <p class="video-detail-views">{{ videoDetail.viewsText }}</p>
              <p v-if="detailErrorMsg" class="video-detail-api-hint">{{ detailErrorMsg }}</p>
              <p v-if="videoDetail.description" class="video-detail-desc">{{ videoDetail.description }}</p>

              <section class="video-detail-comments" aria-label="评论区">
                <div class="video-detail-comments-head">
                  <h3>评论</h3>
                  <span class="video-detail-comments-note">（评论功能即将接入后端）</span>
                  <button type="button" class="video-detail-sort-btn" disabled>
                    <i class="ri-menu-line"></i> 排序
                  </button>
                </div>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input type="text" class="video-detail-comment-field" disabled placeholder="添加评论…" />
                </div>
                <ul class="video-detail-comments-mock">
                  <li v-for="(c, ci) in detailMockComments" :key="ci" class="video-detail-comment-item">
                    <img :src="c.avatar" alt="" class="video-detail-comment-avatar" />
                    <div>
                      <div class="video-detail-comment-meta">
                        <span class="video-detail-comment-user">{{ c.user }}</span>
                        <span class="video-detail-comment-time">{{ c.time }}</span>
                      </div>
                      <p class="video-detail-comment-text">{{ c.text }}</p>
                      <div class="video-detail-comment-actions">
                        <span><i class="ri-thumb-up-line"></i> {{ c.likes }}</span>
                        <span>回复</span>
                      </div>
                    </div>
                  </li>
                </ul>
              </section>
            </div>

            <aside class="video-detail-side" aria-label="更多内容">
              <div class="video-detail-side-chips">
                <button
                  v-for="tab in detailSidebarTabs"
                  :key="tab.key"
                  type="button"
                  class="video-detail-chip"
                  :class="{ 'video-detail-chip--active': detailSidebarTab === tab.key }"
                  @click="setDetailSidebarTab(tab.key)"
                >
                  {{ tab.label }}
                </button>
              </div>
              <div ref="detailSidebarScrollRef" class="video-detail-side-scroll" @scroll.passive="onDetailSidebarScroll">
                <p v-if="detailSidebarLoading && !detailSidebarList.length" class="video-detail-side-hint">加载中…</p>
                <button
                  v-for="item in detailSidebarList"
                  :key="item.id"
                  type="button"
                  class="video-detail-side-item"
                  :class="{ 'video-detail-side-item--current': isSidebarItemNowPlaying(item) }"
                  @click="openVideoDetailFromSidebar(item)"
                >
                  <div class="video-detail-side-thumb-wrap">
                    <img :src="item.coverUrl || item.avatarUrl || profileBanner" alt="" class="video-detail-side-thumb" />
                    <div
                      v-if="isSidebarItemNowPlaying(item)"
                      class="video-detail-now-playing"
                      aria-hidden="true"
                    >
                      <i class="ri-play-fill video-detail-now-playing-icon" aria-hidden="true"></i>
                      <span class="video-detail-now-playing-text">正在播放</span>
                    </div>
                  </div>
                  <div class="video-detail-side-info">
                    <span class="video-detail-side-title">{{ item.title }}</span>
                    <span class="video-detail-side-sub">{{ cardAuthorLabel(item) }} · {{ item.viewsText }}</span>
                  </div>
                </button>
                <p v-if="detailSidebarLoadingMore" class="video-detail-side-hint">加载更多…</p>
                <p v-if="!detailSidebarLoading && !detailSidebarList.length" class="video-detail-side-hint">暂无推荐</p>
              </div>
            </aside>
          </div>
        </template>
      </div>

      <input
        ref="uploadFileInputRef"
        type="file"
        accept="video/*"
        class="visually-hidden"
        aria-hidden="true"
        @change="onUploadInputOrDrop"
      />

      <div
        v-if="uploadModalOpen"
        class="modal-overlay upload-modal-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="上传视频"
        @click.self="closeUploadModal"
      >
        <div class="upload-modal" @click.stop>
          <div class="upload-modal-head">
            <h3>上传视频</h3>
            <button type="button" class="upload-modal-close" aria-label="关闭" @click="closeUploadModal">
              <i class="ri-close-line"></i>
            </button>
          </div>
          <div
            class="upload-modal-drop"
            @dragover.prevent
            @drop.prevent="onUploadInputOrDrop"
          >
            <div class="upload-modal-icon">
              <i class="ri-upload-cloud-2-line"></i>
            </div>
            <p class="upload-modal-main-text">将要上传的视频文件拖放到此处</p>
            <p class="upload-modal-sub">你的视频在发布之前将处于私享状态。</p>
            <button type="button" class="upload-modal-select" @click="triggerUploadPick">选择文件</button>
          </div>
          <p class="upload-modal-foot">
            上传即表示你同意平台服务条款与社区准则。请勿侵犯他人版权或隐私。
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
