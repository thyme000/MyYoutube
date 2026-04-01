<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import OSS from "ali-oss";
import { clearToken, getApiBaseUrl, getAuthExpiredEventName, getToken, setToken } from "./api/http";
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
  fetchMyVideos,
  fetchShortsFeedPage,
  fetchShortHistoryPage,
  fetchLongHistoryPage,
  fetchVideoDetail,
  normalizeVideoDetail,
  publishVideo,
  reportShortHistoryBatch,
} from "./api/videoApi";
import { reportVideoBehavior } from "./api/behaviorApi";
import { getOssSts } from "./api/otherApi";
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
import {
  normalizeUserProfile,
  sanitizeImageUrl,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
} from "./utils/userProfile";
import { parseDigitSnowflakeId, safeSnowflakeId } from "./utils/snowflakeId.js";
/** 短视频无接口数据时的本地 fallback（static 目录下四个 mp4） */
import localShortV1 from "../static/c7bed897269ef9d11cfcaf38c27e855d.mp4?url";
import localShortV2 from "../static/8d20eed729b54235eeaca56d49808aa9.mp4?url";
import localShortV3 from "../static/d7527aad3983ad86bd80b09c5e4d826c.mp4?url";
import localShortV4 from "../static/a5da19be6fe54cb2ca9f50d87315c755.mp4?url";
import appIco from "../static/11.ico?url";

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
const searchInputRef = ref(null);
const sidebarCollapsed = ref(false);
const exploreExpanded = ref(true);
const loading = ref(false);
const cards = ref([]);
const viewportWidth = ref(typeof window !== "undefined" ? window.innerWidth : 1440);
const homeShortsRows = ref([]);
const homeShortsPageNo = ref(1);
const homeShortsPages = ref(0);
const homeShortsLoadingMore = ref(false);
const homeShortsStripRef = ref(null);
const homeLongPageNo = ref(1);
const homeLongPages = ref(0);
const homeLongLoadingMore = ref(false);
const homeLongHasMore = computed(() => homeLongPageNo.value < (homeLongPages.value || 0));
const homeLongLoadLock = ref(false);
/** 离开首页或打开个人中心前保存，回到首页时可无请求恢复，减少列表跳动 */
const homeFeedSnapshot = ref(null);
const hoveringPreviewCardId = ref("");
const activePreviewCardId = ref("");
const activePreviewVideoUrl = ref("");
let hoverPreviewTimer = null;
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
const pendingCropUploadFile = ref(null);
const pendingCropUploadType = ref(null);
const pendingCropUploadUrl = ref("");
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
const homeSortKey = ref("RECOMMENDED");
const homeSortOptions = [
  { value: "RECOMMENDED", label: "推荐优先" },
  { value: "LATEST", label: "最新发布" },
  { value: "MOST_PLAYED", label: "最多播放" },
  { value: "MOST_LIKED", label: "最多点赞" },
];
const HOME_LONG_HEAD_COUNT_DESKTOP = 9;
const HOME_LONG_HEAD_COUNT_MOBILE = 6;
const studioSideNav = ref("info");
/** 频道内容子 tab：video=长视频 shorts=短视频 live=直播（直播接口未就绪时仅占位） */
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
const uploadForm = ref({
  title: "",
  description: "",
  category: "LIFE",
  visibility: "PUBLIC",
  tags: "",
  allowComment: 1,
});
const uploadTagEditorOpen = ref(false);
const uploadTagDraft = ref("");
const uploadPickedFile = ref(null);
const uploadPickedDuration = ref(0);
const uploadSubmitting = ref(false);
const uploadMsg = ref("");
const uploadProgress = ref(0);
const uploadCategoryOptions = [
  { value: "GAME", label: "游戏" },
  { value: "MOVIE", label: "影视" },
  { value: "MUSIC", label: "音乐" },
  { value: "KNOWLEDGE", label: "知识" },
  { value: "LIFE", label: "生活" },
  { value: "TECH", label: "科技" },
  { value: "ENTERTAINMENT", label: "娱乐" },
  { value: "OTHER", label: "其他" },
];
const uploadVisibilityOptions = [
  { value: "PUBLIC", label: "公开" },
  { value: "PRIVATE", label: "私有" },
  { value: "UNLISTED", label: "不公开" },
];
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
const historyLongRows = ref([]);
const historyLongLoading = ref(false);
const historyLongPageNo = ref(1);
const historyLongPages = ref(0);
const historyLongExpanded = ref(false);
const historyShortRows = ref([]);
const historyShortLoading = ref(false);
const historyShortPageNo = ref(1);
const historyShortPages = ref(0);
const historyShortExpanded = ref(false);
const SHORTS_HISTORY_BATCH_SIZE = 10;
let shortsHistoryQueue = [];
const shortsHistoryQueueSet = new Set();
const shortsHistorySeenSet = new Set();
let shortsHistoryFlushInFlight = false;
/** 当前作者是否已关注（按视频 id） */
const shortsFollowedMap = ref({});
/** PLAY 上报：累计顺播时长达到总时长比例，且排除拖动（单次 timeupdate 跳跃过大不计入） */
const BEHAVIOR_PLAY_THRESHOLD = 0.7;
const BEHAVIOR_MAX_PLAY_DELTA_SEC = 3;
const reportedPlayReportedSet = ref(new Set());
/** 详情页：顺播累计秒数 */
const detailPlaybackAccSec = ref(0);
const detailLastTimeSec = ref(null);
/** 详情页长视频：用户手动暂停后，禁止自动播放重试，直到用户再次点击播放 */
const detailUserPausedManually = ref(false);
let detailPlaybackResumeInFlight = false;
let detailPlaybackResumeLastSentAtMs = 0;
let detailPlaybackResumeTickCount = 0;
let detailPlaybackResumeAppliedVideoId = "";
/** 短视频流：顺播累计秒数 + 当前条 id */
const shortsPlaybackAccSec = ref(0);
const shortsLastTimeSec = ref(null);
const shortsAccVideoId = ref("");
let shortsLongResumeInFlight = false;
let shortsLongResumeLastSentAtMs = 0;
let shortsLongResumeTickCount = 0;
let shortsLongResumeAppliedVideoId = "";
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
const LOCAL_STATIC_VIDEO_POOL = [localShortV1, localShortV2, localShortV3, localShortV4];
const UI_STATE_STORAGE_KEY = "myyoutube_ui_state_v1";

function pickFallbackVideoUrl(seed = "") {
  const s = String(seed || "");
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    hash = (hash * 31 + s.charCodeAt(i)) | 0;
  }
  const idx = Math.abs(hash || Math.floor(Math.random() * 9973)) % LOCAL_STATIC_VIDEO_POOL.length;
  return LOCAL_STATIC_VIDEO_POOL[idx] || DEMO_VIDEO_LANDSCAPE;
}

const profileWorksRows = ref([]);
const profileWorksLoading = ref(false);
const profileWorksFilter = ref("ALL");
const profileWorkVideoUrlCache = ref({});
const profileWorkVideoUrlLoading = ref({});
const profileWorkDetailCache = ref({});
const feedScope = ref("shorts");
const profileFeedQueue = ref([]);
const profileFeedOpen = ref(false);
const toasts = ref([]);
let toastSeed = 0;

/** 首页 Shorts 卡片：播放量 / 点赞展示 */
function formatHomeShortStat(v) {
  if (v === undefined || v === null || v === "") {
    return "0";
  }
  const num = Number(v);
  if (!Number.isFinite(num)) {
    return "0";
  }
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}万`;
  }
  return String(num);
}

function formatWorkLikeText(item) {
  const v = item?.likeCount ?? item?.likes;
  if (v === undefined || v === null || v === "") {
    return "—";
  }
  if (typeof v === "string" && /[万千]/.test(v)) {
    return v;
  }
  const num = Number(v);
  if (!Number.isFinite(num)) {
    return "—";
  }
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1)}万`;
  }
  return String(num);
}

const profileWorksDisplay = computed(() => {
  const base = profileWorksRows.value.map((item) => ({
    ...(profileWorkDetailCache.value[String(item.id)] || {}),
    id: item.id,
    title: item.title || "未命名视频",
    image: item.coverUrl || item.avatarUrl || profileBanner,
    likes: formatWorkLikeText(item),
    playCount: item.playCount,
    videoUrl: resolveVideoUrl(item),
    authorNickname: item.authorNickname || item.author || "",
    description: item.description || "",
    tags: item.tags || "",
    authorAvatarUrl: sanitizeImageUrl(item.authorAvatarUrl || item.authorAvatar || "") || profileDisplayAvatar.value,
  }));
  if (profileWorksFilter.value === "PRIVATE") {
    return base.filter((item) => String(item.visibility || "").toUpperCase() === "PRIVATE");
  }
  if (profileWorksFilter.value === "PUBLIC") {
    return base.filter((item) => String(item.visibility || "").toUpperCase() !== "PRIVATE");
  }
  return base;
});

const uploadTagList = computed(() =>
  String(uploadForm.value.tags || "")
    .split(",")
    .map((x) => x.trim())
    .filter((x) => x)
);

const homeLongHeadCount = computed(() =>
  viewportWidth.value >= 1320 ? HOME_LONG_HEAD_COUNT_DESKTOP : HOME_LONG_HEAD_COUNT_MOBILE
);

const homeLongHeadCards = computed(() => {
  if (activeMenuKey.value !== "home") return [];
  return cards.value.slice(0, homeLongHeadCount.value);
});

const homeLongTailCards = computed(() => {
  if (activeMenuKey.value !== "home") return [];
  return cards.value.slice(homeLongHeadCount.value);
});

function parseTagsToList(raw) {
  if (!raw) return [];
  return String(raw)
    .replace(/[，\s]+/g, ",")
    .split(",")
    .map((x) => x.trim())
    .filter((x) => x);
}

function stripUserIdLikeName(name) {
  const n = String(name || "").trim();
  if (!n) return "";
  if (/^用户\s*\d+$/i.test(n)) return "";
  return n;
}

function formatRelativeTimeText(raw) {
  if (!raw) return "";
  const d = new Date(raw);
  if (!Number.isFinite(d.getTime())) return "";
  const diff = Date.now() - d.getTime();
  if (diff < 0) return "刚刚";
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return "刚刚";
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day}天前`;
  const month = Math.floor(day / 30);
  if (month < 12) return `${month}个月前`;
  const year = Math.floor(month / 12);
  return `${year}年前`;
}

function pushToast(message, type = "info") {
  const text = String(message || "").trim();
  if (!text) return;
  toastSeed += 1;
  const id = `toast-${Date.now()}-${toastSeed}`;
  const next = [...toasts.value, { id, message: text, type }];
  toasts.value = next.slice(-3);
  window.setTimeout(() => {
    removeToast(id);
  }, 3000);
}

function removeToast(id) {
  toasts.value = toasts.value.filter((t) => t.id !== id);
}

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
  return String(profileTargetUserId.value) === String(currentUserId.value);
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
  { key: "video", label: "长视频" },
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
  if (!pendingCropUploadFile.value) {
    return;
  }
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
  pendingCropUploadUrl.value ||
  (mediaPreviewType.value === "avatar" ? profileDisplayAvatar.value : profileDisplayBg.value)
);

function hashStringToIndex(str, mod) {
  let h = 0;
  const s = String(str ?? "");
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0;
  }
  return mod ? h % mod : h;
}

function buildMockOtherProfile(userId) {
  const id = safeSnowflakeId(userId);
  const idx = hashStringToIndex(id, feedCoverPool.length);
  return normalizeUserProfile({
    id,
    nickName: `用户 ${id}`,
    avatarUrl: feedCoverPool[idx],
    bgImage: feedCoverPool[(idx + 1) % feedCoverPool.length],
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
    playCount: 1200 + index * 100,
    likeCount: 30 + index * 5,
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
    videoUrl: pickFallbackVideoUrl(`${moduleKey}-${index + 1}`),
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
  return parseDigitSnowflakeId(id);
}

function formatBehaviorWatchTime(date = new Date()) {
  const pad = (v) => String(v).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function behaviorPlayReportKey(videoId) {
  return String(videoId ?? "");
}

function markPlayReported(videoId) {
  const key = behaviorPlayReportKey(videoId);
  if (!key) return;
  const next = new Set(reportedPlayReportedSet.value);
  next.add(key);
  reportedPlayReportedSet.value = next;
}

function hasPlayReported(videoId) {
  return reportedPlayReportedSet.value.has(behaviorPlayReportKey(videoId));
}

function clearPreviewTimer() {
  if (hoverPreviewTimer) {
    clearTimeout(hoverPreviewTimer);
    hoverPreviewTimer = null;
  }
}

function stopCardHoverPreview() {
  clearPreviewTimer();
  hoveringPreviewCardId.value = "";
  activePreviewCardId.value = "";
  activePreviewVideoUrl.value = "";
}

function cardPreviewKey(card, listKey = "") {
  return `${listKey}:${String(card?.id ?? "")}`;
}

function isCardPreviewPlaying(card, listKey = "") {
  return activePreviewCardId.value && activePreviewCardId.value === cardPreviewKey(card, listKey);
}

function onCardHoverEnter(card, listKey = "") {
  const url = resolveVideoUrl(card);
  if (!url) return;
  const key = cardPreviewKey(card, listKey);
  clearPreviewTimer();
  hoveringPreviewCardId.value = key;
  hoverPreviewTimer = setTimeout(() => {
    if (hoveringPreviewCardId.value !== key) return;
    activePreviewCardId.value = key;
    activePreviewVideoUrl.value = url;
  }, 500);
}

function onCardHoverLeave(card, listKey = "") {
  const key = cardPreviewKey(card, listKey);
  if (hoveringPreviewCardId.value === key) {
    hoveringPreviewCardId.value = "";
  }
  if (activePreviewCardId.value === key) {
    stopCardHoverPreview();
  } else {
    clearPreviewTimer();
  }
}

async function reportBehaviorSilently(videoIdLike, event, watchDurationMs = 0) {
  if (!isAuthed.value) return;
  const videoId = parseDigitSnowflakeId(videoIdLike);
  if (!videoId) return;
  try {
    await reportVideoBehavior({
      videoId,
      event,
      watchTime: formatBehaviorWatchTime(new Date()),
      watchDuration: Math.max(0, Math.floor(Number(watchDurationMs) || 0)),
    });
  } catch {
    // 静默上报，不打断用户体验
  }
}

function buildDetailFromCard(card) {
  const vid = parseVideoIdForApi(card?.id);
  const fallbackVideoUrl = pickFallbackVideoUrl(card?.id ?? card?.title ?? "");
  const fallbackCover = feedCoverPool[Math.floor(Math.random() * feedCoverPool.length)] || profileBanner;
  return normalizeVideoDetail({
    id: vid ?? (card?.id != null ? String(card.id) : ""),
    title: card?.title ?? "未命名视频",
    description: "（示例数据）视频简介占位，连接后端后将显示真实描述。",
    coverUrl: card?.coverUrl || card?.avatarUrl || fallbackCover,
    videoUrl: resolveVideoUrl(card) || fallbackVideoUrl,
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
  const base = hashStringToIndex(String(videoDetail.value?.authorId ?? "1"), 100000);
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
  // 用户手动暂停后，阻止自动重试 play
  if (detailUserPausedManually.value) {
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
  applyDetailPlaybackResumeIfNeeded();
  void tryDetailVideoAutoplay();
}

function onDetailVideoVolumeChange() {
  const el = detailPlayerRef.value;
  if (el && !el.muted) {
    detailAutoplayMutedFallback.value = false;
  }
}

function onDetailVideoTimeUpdate(e) {
  const el = e?.target;
  if (!el) return;
  const d = videoDetail.value;
  if (!d?.id) return;
  const duration = Number(el.duration);
  const current = Number(el.currentTime || 0);
  if (!Number.isFinite(duration) || duration <= 0) {
    return;
  }
  const prev = detailLastTimeSec.value;
  detailLastTimeSec.value = current;
  if (prev != null) {
    const delta = current - prev;
    if (delta > 0 && delta <= BEHAVIOR_MAX_PLAY_DELTA_SEC) {
      detailPlaybackAccSec.value += delta;
    }
  }
  if (
    detailPlaybackAccSec.value >= duration * BEHAVIOR_PLAY_THRESHOLD &&
    !hasPlayReported(d.id)
  ) {
    markPlayReported(d.id);
    void reportBehaviorSilently(d.id, "PLAY", current * 1000);
  }

  // 长视频：按 timeupdate 次数上报。每 30 次触发一次兜底续播上报。
  if (isLongDetailVideo() && el && !el.paused) {
    detailPlaybackResumeTickCount += 1;
    if (detailPlaybackResumeTickCount >= 30) {
      detailPlaybackResumeTickCount = 0;
      void flushDetailPlaybackResume("tick");
    }
  }
}

function onDetailVideoEnded(e) {
  const el = e?.target;
  const d = videoDetail.value;
  if (!d?.id) return;
  const current = Number(el?.currentTime || 0);
  void reportBehaviorSilently(d.id, "FINISH", current * 1000);

  // ended 时也算播放完成点：写一次续播（可选，但能减少断点缺失）
  void flushDetailPlaybackResume("ended");
}

function onDetailLikeClick() {
  const d = videoDetail.value;
  if (!d?.id) return;
  const current = Number(detailPlayerRef.value?.currentTime || 0);
  void reportBehaviorSilently(d.id, "LIKE", current * 1000);
}

function onDetailDislikeClick() {
  const d = videoDetail.value;
  if (!d?.id) return;
  const current = Number(detailPlayerRef.value?.currentTime || 0);
  void reportBehaviorSilently(d.id, "DISLIKE", current * 1000);
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

function isLongDetailVideo() {
  const vt = videoDetail.value?.videoType;
  if (!vt) return false;
  const s = String(vt).toLowerCase().trim();
  // 后端可能是 "long"，列表可能是 "LONG_VIDEO"，或其它命名包含 "long"
  // 这里做兼容，避免刚进入详情尚未完成数据填充时错过上报。
  return s === "long" || s === "long_video" || s === "longvideo" || s.includes("long");
}

function getDetailCurrentPlayTimeMs() {
  const el = detailPlayerRef.value;
  if (!el) return 0;
  // currentTime 是秒，后端需要毫秒
  const ms = Math.floor((Number(el.currentTime || 0) || 0) * 1000);
  return ms > 0 ? ms : 0;
}

function getDetailPlaybackResumePayload(playTimeMs) {
  const userId = currentUserId.value;
  const videoId = videoDetail.value?.id;
  if (!userId || userId === "" || !videoId) return null;
  if (!Number.isFinite(Number(playTimeMs))) return null;
  return {
    videoId,
    playTime: playTimeMs,
    userId,
  };
}

function applyDetailPlaybackResumeIfNeeded() {
  if (!isLongDetailVideo()) return;
  const detail = videoDetail.value;
  const player = detailPlayerRef.value;
  if (!detail || !player) return;
  const vid = String(detail.id ?? "");
  if (!vid) return;
  if (detailPlaybackResumeAppliedVideoId === vid) return;
  const playbackMs = Number(detail.playbackTime ?? 0);
  if (!Number.isFinite(playbackMs) || playbackMs <= 0) {
    detailPlaybackResumeAppliedVideoId = vid;
    return;
  }
  const targetSec = playbackMs / 1000;
  if (!Number.isFinite(targetSec) || targetSec <= 0) {
    detailPlaybackResumeAppliedVideoId = vid;
    return;
  }
  const duration = Number(player.duration);
  if (Number.isFinite(duration) && duration > 0) {
    player.currentTime = Math.max(0, Math.min(targetSec, Math.max(0, duration - 1)));
    detailPlaybackResumeAppliedVideoId = vid;
    return;
  }
  // duration 尚未可用时，loadedmetadata/canplay 到来后会再次调用。
}

async function sendDetailPlaybackResume(payload, { keepalive = false } = {}) {
  if (!payload) return;
  try {
    const headers = {
      "Content-Type": "application/json",
    };
    if (token.value) {
      headers["Authorization"] = token.value;
    }
    // 用 fetch + keepalive：页面切走/关闭也尽量能发出去
    await fetch("/videos/playback/resume", {
      method: "PUT",
      headers,
      body: JSON.stringify(payload),
      keepalive,
    });
  } catch {
    // 静默失败：续播记录不影响播放体验
  }
}

function stopDetailPlaybackResumeTimer() {
  detailPlaybackResumeTickCount = 0;
}

function resetDetailPlaybackResumeState() {
  stopDetailPlaybackResumeTimer();
  detailPlaybackResumeInFlight = false;
  detailPlaybackResumeTickCount = 0;
  detailPlaybackResumeAppliedVideoId = "";
  detailUserPausedManually.value = false;
}

function flushDetailPlaybackResume(reason) {
  if (!isLongDetailVideo()) return Promise.resolve();
  const player = detailPlayerRef.value;
  if (!player) return Promise.resolve();

  // “切走/关页/离开详情”只在播放态上报；用户手动 pause 的情况由 reason="pause" 单独处理
  if (reason !== "pause" && player.paused) {
    return Promise.resolve();
  }

  const playTimeMs = getDetailCurrentPlayTimeMs();
  if (reason !== "pause" && reason !== "switch" && reason !== "leave" && reason !== "unload" && playTimeMs <= 0) {
    return Promise.resolve();
  }

  const payload = getDetailPlaybackResumePayload(playTimeMs);
  if (!payload) return Promise.resolve();

  const now = Date.now();
  // tick 兜底避免过于频繁；pause/switch/leave/unload 走立即
  if (reason === "tick" && now - detailPlaybackResumeLastSentAtMs < 8_000) {
    return Promise.resolve();
  }
  if (detailPlaybackResumeInFlight) {
    return Promise.resolve();
  }
  detailPlaybackResumeInFlight = true;
  detailPlaybackResumeLastSentAtMs = now;

  const keepalive = reason === "unload" || reason === "leave";
  return sendDetailPlaybackResume(payload, { keepalive }).finally(() => {
    detailPlaybackResumeInFlight = false;
  });
}

function onDetailVideoPause(e) {
  // 用户手动暂停：置标记 + 立即上报续播（长视频）
  if (isLongDetailVideo()) {
    stopDetailPlaybackResumeTimer();
    const isUser = e?.isTrusted !== false; // 有些环境下 isTrusted 可能不可用
    if (isUser) {
      detailUserPausedManually.value = true;
      void flushDetailPlaybackResume("pause");
    }
  }
}

function onDetailVideoPlay(e) {
  // 用户再次点击播放：解除暂停屏蔽 + 开启兜底定时器
  if (isLongDetailVideo()) {
    const isUser = e?.isTrusted !== false;
    if (isUser) {
      detailUserPausedManually.value = false;
    }
    // 播放恢复后将按 timeupdate 次数上报，这里重置计数避免刚播放就上报。
    detailPlaybackResumeTickCount = 0;
  }
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
  // 切换详情视频前：如果上一条是长视频且正在播放，先 flush 一次续播
  if (showVideoDetail.value && isLongDetailVideo()) {
    const player = detailPlayerRef.value;
    if (player && !player.paused) {
      void flushDetailPlaybackResume("switch");
    }
  }

  detailPlaybackAccSec.value = 0;
  detailLastTimeSec.value = null;
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
  detailAutoplayMutedFallback.value = false;
  const wasAlreadyInDetail = showVideoDetail.value;
  showVideoDetail.value = true;
  showProfileCenter.value = false;
  showStudio.value = false;
  uploadModalOpen.value = false;
  videoDetail.value = null;
  detailErrorMsg.value = "";
  // 新开详情：重置“用户手动暂停”屏蔽，避免误影响自动播放重试
  resetDetailPlaybackResumeState();
  detailLoading.value = true;
  const vid = parseVideoIdForApi(card.id);
  const instantDetail = buildDetailFromCard(card);
  videoDetail.value = instantDetail;
  detailLoading.value = false;
  nextTick(() => {
    scheduleDetailVideoAutoplayRetries();
  });
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
        if (!d.videoUrl) {
          d.videoUrl = pickFallbackVideoUrl(d.id || card?.id || card?.title || "");
        }
        if (!d.coverUrl) {
          d.coverUrl = card?.coverUrl || card?.avatarUrl || feedCoverPool[Math.floor(Math.random() * feedCoverPool.length)];
        }
      }
      videoDetail.value = d;
      nextTick(() => {
        applyDetailPlaybackResumeIfNeeded();
      });
    } else {
      videoDetail.value = instantDetail;
    }
  } catch (e) {
    videoDetail.value = instantDetail;
    detailErrorMsg.value = `详情接口不可用，已展示本地数据：${e.message}`;
  }
  detailLoading.value = false;
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
  // 离开详情：如果当前是长视频且正在播放，立即上报（兜底/切走场景）
  void flushDetailPlaybackResume("leave");
  resetDetailPlaybackResumeState();
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
  const cur = shortsFeedCurrent.value;
  const now = Number(shortsFeedCurrentTime.value || 0);
  if (cur?.id) {
    void reportBehaviorSilently(cur.id, "FINISH", now * 1000);
  }
  if (isLongShortsFeedVideo(cur)) {
    void flushShortsLongPlaybackResume("ended");
  }
  const n = unifiedFeedList.value.length;
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

function onWindowResize() {
  viewportWidth.value = window.innerWidth || 1440;
}

function saveHomeFeedSnapshot() {
  if (activeMenuKey.value !== "home") {
    return;
  }
  homeFeedSnapshot.value = {
    cards: [...cards.value],
    homeShortsRows: [...homeShortsRows.value],
    homeLongPageNo: homeLongPageNo.value,
    homeLongPages: homeLongPages.value,
    homeShortsPageNo: homeShortsPageNo.value,
    homeShortsPages: homeShortsPages.value,
    homeCategoryKey: homeCategoryKey.value,
    homeSortKey: homeSortKey.value,
  };
}

function restoreHomeFeedSnapshot() {
  const s = homeFeedSnapshot.value;
  if (!s) {
    return false;
  }
  cards.value = [...s.cards];
  homeShortsRows.value = [...s.homeShortsRows];
  homeLongPageNo.value = s.homeLongPageNo;
  homeLongPages.value = s.homeLongPages;
  homeShortsPageNo.value = s.homeShortsPageNo;
  homeShortsPages.value = s.homeShortsPages;
  homeCategoryKey.value = s.homeCategoryKey;
  homeSortKey.value = s.homeSortKey;
  return true;
}

async function loadHomeShortsMore() {
  if (homeShortsLoadingMore.value) return;
  if (homeShortsPageNo.value >= (homeShortsPages.value || 0)) return;
  homeShortsLoadingMore.value = true;
  try {
    const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === homeCategoryKey.value);
    const nextPage = homeShortsPageNo.value + 1;
    const res = await fetchShortsFeedPage({
      pageNo: nextPage,
      pageSize: 18,
      category: chip?.code || undefined,
    });
    const nextList = Array.isArray(res.list) ? res.list : [];
    if (nextList.length) {
      homeShortsRows.value = [...homeShortsRows.value, ...nextList];
    }
    homeShortsPages.value = res.pages ?? homeShortsPages.value;
    homeShortsPageNo.value = nextPage;
  } catch {
    // 保持当前列表，避免影响首页主流
  } finally {
    homeShortsLoadingMore.value = false;
    if (activeMenuKey.value === "home") {
      saveHomeFeedSnapshot();
    }
  }
}

function maybeLoadMoreHomeShortsByElement(el) {
  if (!el) return;
  const remain = el.scrollWidth - el.scrollLeft - el.clientWidth;
  if (remain < 260) {
    void loadHomeShortsMore();
  }
}

function onHomeShortsStripScroll(e) {
  stopCardHoverPreview();
  maybeLoadMoreHomeShortsByElement(e?.target);
}

function onHomeShortsStripWheel(e) {
  const el = homeShortsStripRef.value;
  if (!el) return;
  e.stopPropagation();
  stopCardHoverPreview();
  const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
  el.scrollLeft += delta;
  maybeLoadMoreHomeShortsByElement(el);
}

/** 首页 Shorts 条：接近列表末尾时预拉下一页（与横向条共用 loadHomeShortsMore） */
const HOME_SHORTS_FEED_PREFETCH_THRESHOLD = 3;

function maybePrefetchHomeShortsForFeed() {
  if (feedScope.value !== "home") {
    return;
  }
  if (activeMenuKey.value !== "shorts") {
    return;
  }
  const list = homeShortsRows.value;
  const i = shortsFeedIndex.value;
  if (!list.length) {
    return;
  }
  const remainToEnd = list.length - 1 - i;
  if (remainToEnd < HOME_SHORTS_FEED_PREFETCH_THRESHOLD) {
    void loadHomeShortsMore();
  }
}

/**
 * 从首页 Shorts 条进入全屏短视频流：只使用已加载的 homeShortsRows，不立即请求接口；
 * 划到接近末尾时再分页（maybePrefetchHomeShortsForFeed）。
 */
function openHomeShortVideoInFeed(card, index) {
  if (activeMenuKey.value === "home") {
    saveHomeFeedSnapshot();
  }
  closeShortPlayer();
  if (showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  showProfileCenter.value = false;
  showStudio.value = false;
  uploadModalOpen.value = false;
  mediaPreviewOpen.value = false;

  feedScope.value = "home";
  const idx = homeShortsRows.value.findIndex((c) => c && card && String(c.id) === String(card.id));
  shortsFeedIndex.value = idx >= 0 ? idx : Math.max(0, Number(index) || 0);
  shortsFeedAutoplayPending.value = true;

  activeMenuKey.value = "shorts";
  const shortsMenu = primaryMenus.find((m) => m.key === "shorts");
  activeMenuLabel.value = shortsMenu?.label ?? "短视频";
  try {
    history.pushState({ myyoutubeMenu: "shorts" }, "", window.location.href);
  } catch {
    /* ignore */
  }

  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
    maybePrefetchHomeShortsForFeed();
  });
}

function onHomeShortCardClick(card, index) {
  openHomeShortVideoInFeed(card, index);
}

async function loadHistoryShortsPage({ reset = false } = {}) {
  if (!isAuthed.value) return;
  if (historyShortLoading.value) return;
  historyShortLoading.value = true;
  try {
    const targetPage = reset ? 1 : historyShortPageNo.value;
    const pageSize = historyShortExpanded.value ? 25 : 5;
    const res = await fetchShortHistoryPage({
      pageNo: targetPage,
      pageSize,
      userId: currentUserId.value || undefined,
    });
    const nextList = Array.isArray(res.list) ? res.list : [];
    historyShortRows.value = nextList;
    historyShortPages.value = Number(res.pages || 0);
    if (reset) {
      historyShortPageNo.value = 1;
    }
  } catch {
    historyShortRows.value = [];
    historyShortPageNo.value = 1;
    historyShortPages.value = 0;
  } finally {
    historyShortLoading.value = false;
  }
}

async function loadHistoryLongPage({ reset = false } = {}) {
  if (!isAuthed.value) return;
  if (historyLongLoading.value) return;
  historyLongLoading.value = true;
  try {
    const targetPage = reset ? 1 : historyLongPageNo.value;
    const pageSize = historyLongExpanded.value ? 9 : 3;
    const res = await fetchLongHistoryPage({
      pageNo: targetPage,
      pageSize,
      userId: currentUserId.value || undefined,
    });
    const nextList = Array.isArray(res.list) ? res.list : [];
    historyLongRows.value = nextList;
    historyLongPages.value = Number(res.pages || 0);
    if (reset) {
      historyLongPageNo.value = 1;
    }
  } catch {
    historyLongRows.value = [];
    historyLongPageNo.value = 1;
    historyLongPages.value = 0;
  }
  historyLongLoading.value = false;
}

async function toggleHistoryLongExpanded() {
  historyLongExpanded.value = !historyLongExpanded.value;
  historyLongPageNo.value = 1;
  await loadHistoryLongPage({ reset: true });
}

async function toggleHistoryShortExpanded() {
  historyShortExpanded.value = !historyShortExpanded.value;
  historyShortPageNo.value = 1;
  await loadHistoryShortsPage({ reset: true });
}

async function onHistoryLongPrevPage() {
  if (historyLongPageNo.value <= 1 || historyLongLoading.value) return;
  historyLongPageNo.value -= 1;
  await loadHistoryLongPage();
}

async function onHistoryLongNextPage() {
  if (historyLongPageNo.value >= (historyLongPages.value || 1) || historyLongLoading.value) return;
  historyLongPageNo.value += 1;
  await loadHistoryLongPage();
}

async function onHistoryShortPrevPage() {
  if (historyShortPageNo.value <= 1 || historyShortLoading.value) return;
  historyShortPageNo.value -= 1;
  await loadHistoryShortsPage();
}

async function onHistoryShortNextPage() {
  if (historyShortPageNo.value >= (historyShortPages.value || 1) || historyShortLoading.value) return;
  historyShortPageNo.value += 1;
  await loadHistoryShortsPage();
}

function onHistoryShortCardClick(card, index) {
  if (!card) return;
  openHistoryFeedFullscreen(historyShortRows.value, index, { forceVideoType: "SHORT_VIDEO" });
}

function onHistoryLongCardClick(card, index) {
  if (!card) return;
  openHistoryFeedFullscreen(historyLongRows.value, index, { forceVideoType: "LONG_VIDEO" });
}

function openHistoryFeedFullscreen(sourceList, index, { forceVideoType = "" } = {}) {
  const source = Array.isArray(sourceList) ? sourceList : [];
  const playableQueue = source
    .map((x) => {
      const url = resolveVideoUrl(x) || pickFallbackVideoUrl(x?.id || x?.title || "");
      return {
        ...x,
        videoType: forceVideoType || x?.videoType || "",
        videoUrl: url,
      };
    })
    .filter((x) => x.videoUrl);
  if (!playableQueue.length) return;
  feedScope.value = "profile";
  profileFeedQueue.value = playableQueue;
  shortsFeedIndex.value = Math.min(Math.max(0, Number(index) || 0), playableQueue.length - 1);
  shortsFeedAutoplayPending.value = true;
  profileFeedOpen.value = true;
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

async function loadHomeLongMore() {
  if (homeLongLoadLock.value || homeLongLoadingMore.value) return;
  if (!homeLongHasMore.value) return;
  if (activeMenuKey.value !== "home" || showVideoDetail.value || showProfileCenter.value || showStudio.value) return;
  homeLongLoadLock.value = true;
  homeLongLoadingMore.value = true;
  try {
    const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === homeCategoryKey.value);
    const nextPage = homeLongPageNo.value + 1;
    const res = await fetchVideoListPage({
      pageNo: nextPage,
      pageSize: 24,
      category: chip?.code || undefined,
      sort: homeSortKey.value,
      videoType: "LONG_VIDEO",
    });
    const nextList = Array.isArray(res.list) ? res.list : [];
    if (nextList.length) {
      cards.value = [...cards.value, ...nextList];
    }
    homeLongPages.value = res.pages ?? homeLongPages.value;
    homeLongPageNo.value = nextPage;
  } catch {
    // 静默失败，不影响当前浏览
  } finally {
    homeLongLoadingMore.value = false;
    homeLongLoadLock.value = false;
    if (activeMenuKey.value === "home") {
      saveHomeFeedSnapshot();
    }
  }
}

function maybeLoadMoreHomeLongByWindow() {
  if (activeMenuKey.value !== "home") return;
  if (loading.value || homeLongLoadingMore.value) return;
  const doc = document.documentElement;
  const remain = doc.scrollHeight - window.scrollY - window.innerHeight;
  if (remain < 280) {
    void loadHomeLongMore();
  }
}

function onWindowScroll() {
  if (activePreviewCardId.value) {
    stopCardHoverPreview();
  }
  maybeLoadMoreHomeLongByWindow();
}

function onContentScroll(e) {
  if (activePreviewCardId.value) {
    stopCardHoverPreview();
  }
  if (activeMenuKey.value !== "home" || loading.value || homeLongLoadingMore.value) return;
  const el = e?.target;
  if (!el) return;
  const remain = el.scrollHeight - el.scrollTop - el.clientHeight;
  if (remain < 260) {
    void loadHomeLongMore();
  }
}

async function loadModuleCards(menu, { fallback = true } = {}) {
  loading.value = true;
  try {
    if (menu.key === "home") {
      const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === homeCategoryKey.value);
      const [longRes, shortRes] = await Promise.all([
        fetchVideoListPage({
          pageNo: 1,
          pageSize: 24,
          category: chip?.code || undefined,
          sort: homeSortKey.value,
          videoType: "LONG_VIDEO",
        }),
        fetchShortsFeedPage({
          pageNo: 1,
          pageSize: 18,
          category: chip?.code || undefined,
        }),
      ]);
      cards.value = longRes.list.length ? longRes.list : getMockCards(menu.label, menu.key);
      homeLongPageNo.value = 1;
      homeLongPages.value = longRes.pages ?? 0;
      homeShortsRows.value = shortRes.list.length ? shortRes.list : getShortsLocalFallbackCards();
      homeShortsPageNo.value = 1;
      homeShortsPages.value = shortRes.pages ?? 0;
      const sortLabel = homeSortOptions.find((x) => x.value === homeSortKey.value)?.label || "排序";
      pageHint.value = `首页 · 长视频(/videos/list) + 短视频(/videos/shorts/feed) · ${chip?.label ?? "全部"} · ${sortLabel}`;
      nextTick(() => {
        maybeLoadMoreHomeLongByWindow();
      });
      saveHomeFeedSnapshot();
      return;
    }
    if (menu.key === "history") {
      historyLongPageNo.value = 1;
      historyShortPageNo.value = 1;
      await Promise.all([loadHistoryLongPage({ reset: true }), loadHistoryShortsPage({ reset: true })]);
      cards.value = [];
      pageHint.value = "历史播放记录 · 长视频与短视频（分页）";
      return;
    }
    homeShortsRows.value = [];
    homeShortsPageNo.value = 1;
    homeShortsPages.value = 0;
    homeLongPageNo.value = 1;
    homeLongPages.value = 0;
    const list =
      menu.key === "shorts"
        ? (await fetchShortsFeedPage({ pageNo: 1, pageSize: 24 })).list
        : await fetchVideosByModule(menu.key);
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
      homeLongPageNo.value = 1;
      homeLongPages.value = 1;
      homeShortsRows.value = getShortsLocalFallbackCards();
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 1;
      saveHomeFeedSnapshot();
    } else {
      if (menu.key === "history") {
        historyLongRows.value = [];
        historyLongPageNo.value = 1;
        historyLongPages.value = 0;
        historyShortRows.value = [];
        historyShortPageNo.value = 1;
        historyShortPages.value = 0;
        cards.value = [];
      } else {
        cards.value = menu.key === "shorts" ? getShortsLocalFallbackCards() : getMockCards(menu.label, menu.key);
      }
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
    }
    pageHint.value = `接口暂不可用，已回退到本地示例数据。错误信息：${error.message}`;
  } finally {
    loading.value = false;
  }
}

async function setActiveMenu(menu) {
  const prevMenuKey = activeMenuKey.value;
  const prevShowProfile = showProfileCenter.value;
  if (prevMenuKey === "shorts" && menu.key !== "shorts") {
    void flushShortsHistoryQueue();
  }
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
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
  if (menu.key !== "home") {
    try {
      history.pushState({ myyoutubeMenu: menu.key }, "", window.location.href);
    } catch {
      /* ignore */
    }
  }
  if (menu.key === "shorts") {
    feedScope.value = "shorts";
    shortsFeedIndex.value = 0;
    shortsFeedAutoplayPending.value = true;
  }

  if (menu.key === "home") {
    if (prevMenuKey === "home" && prevShowProfile) {
      if (restoreHomeFeedSnapshot()) {
        loading.value = false;
        nextTick(() => {
          maybeLoadMoreHomeLongByWindow();
        });
        return;
      }
      await loadModuleCards(menu);
      return;
    }
    if (prevMenuKey === "home" && !prevShowProfile) {
      await loadModuleCards(menu);
      return;
    }
    if (homeFeedSnapshot.value && restoreHomeFeedSnapshot()) {
      loading.value = false;
      nextTick(() => {
        maybeLoadMoreHomeLongByWindow();
      });
      return;
    }
    await loadModuleCards(menu);
    return;
  }

  if (prevMenuKey === "home") {
    saveHomeFeedSnapshot();
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
    pushToast("请输入关键词后再搜索", "info");
    return;
  }

  loading.value = true;
  try {
    const list = await searchVideos(keyword);
    cards.value = list.length ? list : getMockCards(`搜索：${keyword}`, "search");
    activeMenuLabel.value = `搜索结果：${keyword}`;
    pageHint.value = `搜索接口请求成功，关键词：「${keyword}」。`;
    pushToast(`搜索完成：${keyword}`, "success");
  } catch (error) {
    cards.value = getMockCards(`搜索：${keyword}`, "search");
    pageHint.value = `搜索接口失败，已展示示例数据。错误信息：${error.message}`;
    pushToast(`搜索失败：${error.message || "请稍后重试"}`, "error");
  } finally {
    loading.value = false;
  }
}

function onCreate() {
  if (!isAuthed.value) {
    pageHint.value = "请先登录后再使用创作中心。";
    pushToast("请先登录后再使用创作中心", "info");
    return;
  }
  if (showStudio.value) {
    uploadModalOpen.value = true;
    return;
  }
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
  if (activeMenuKey.value === "shorts") {
    void flushShortsHistoryQueue();
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

function selectHomeSort(sortValue) {
  homeSortKey.value = sortValue;
  if (activeMenuKey.value === "home") {
    loadModuleCards(primaryMenus[0]);
  }
}

function formatStudioDate(row) {
  const raw = row?.createTime ?? row?.createAt ?? row?.createdAt ?? row?.publishTime;
  if (!raw) return "—";
  if (typeof raw === "string") {
    if (raw.includes("T")) {
      return raw.replace("T", " ").slice(0, 19);
    }
    return raw.length > 19 ? raw.slice(0, 19) : raw;
  }
  try {
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return "—";
    const yyyy = d.getFullYear();
    const MM = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    return `${yyyy}-${MM}-${dd} ${hh}:${mm}`;
  } catch {
    return "—";
  }
}

function formatStudioVisibility(row) {
  const v = row?.visibility?.code ?? row?.visibility?.name ?? row?.visibility ?? "PUBLIC";
  const s = String(v).toUpperCase();
  if (s.includes("PRIVATE")) return "私有";
  if (s.includes("UNLISTED")) return "不公开";
  return "公开";
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
    const res = await fetchMyVideos({
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
  uploadMsg.value = "";
  uploadProgress.value = 0;
  uploadTagEditorOpen.value = false;
  uploadTagDraft.value = "";
  uploadModalOpen.value = true;
}

function closeUploadModal() {
  if (uploadSubmitting.value) {
    return;
  }
  uploadTagEditorOpen.value = false;
  uploadTagDraft.value = "";
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

function openUploadTagEditor() {
  uploadTagEditorOpen.value = true;
  uploadTagDraft.value = "";
}

function appendUploadTag() {
  const tag = String(uploadTagDraft.value || "")
    .replace(/[，,\s]+/g, " ")
    .trim();
  if (!tag) return;
  const normalized = tag.replace(/\s+/g, "");
  const existing = uploadTagList.value.map((x) => x.toLowerCase());
  if (!existing.includes(normalized.toLowerCase())) {
    uploadForm.value.tags = [...uploadTagList.value, normalized].join(",");
  }
  uploadTagDraft.value = "";
  uploadTagEditorOpen.value = false;
}

function removeUploadTag(tag) {
  uploadForm.value.tags = uploadTagList.value.filter((x) => x !== tag).join(",");
}

function onUploadInputOrDrop(e) {
  const f = pickUploadFile(e);
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!f) {
    return;
  }
  uploadPickedFile.value = f;
  uploadMsg.value = `已选择：${f.name}`;
  void readVideoDuration(f);
}

function onTopbarAvatarError(evt) {
  const el = evt?.target;
  if (el) {
    el.src = profileAvatar;
  }
}

function applyAppFavicon() {
  if (!appIco) return;
  const head = document.head || document.getElementsByTagName("head")[0];
  if (!head) return;
  let link = document.querySelector("link[rel='icon']");
  if (!link) {
    link = document.createElement("link");
    link.setAttribute("rel", "icon");
    head.appendChild(link);
  }
  link.setAttribute("type", "image/x-icon");
  link.setAttribute("href", appIco);
}


function onNotify() {
  pageHint.value = "点击了通知按钮：后续可展示消息列表和未读状态。";
  pushToast("通知功能开发中", "info");
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
  void loadProfileWorks();
}

async function loadProfileWorks() {
  profileWorksFilter.value = "ALL";
  const uid = profileTargetUserId.value ?? currentUserId.value;
  if (!uid) {
    profileWorksRows.value = [];
    profileWorkVideoUrlCache.value = {};
    profileWorkVideoUrlLoading.value = {};
    profileWorkDetailCache.value = {};
    return;
  }
  profileWorksLoading.value = true;
  profileWorkVideoUrlCache.value = {};
  profileWorkVideoUrlLoading.value = {};
  profileWorkDetailCache.value = {};
  try {
    const isSelf = profileTargetUserId.value == null;
    const worksRes = isSelf
      ? await fetchMyVideos({ pageNo: 1, pageSize: 24 })
      : await fetchAuthorVideos(uid, { pageNo: 1, pageSize: 24 });
    const map = new Map();
    const fallbackNick = stripUserIdLikeName(profileUser.value?.nickName || profileUser.value?.nickname);
    for (const item of worksRes?.list || []) {
      if (!item?.id) continue;
      const row = { ...item };
      row.authorNickname =
        stripUserIdLikeName(row.authorNickname) ||
        stripUserIdLikeName(row.author) ||
        fallbackNick ||
        row.authorNickname ||
        row.author;
      map.set(String(item.id), row);
    }
    profileWorksRows.value = Array.from(map.values());
  } catch {
    profileWorksRows.value = [];
  } finally {
    profileWorksLoading.value = false;
  }
}

function openMediaPreview(type) {
  clearPendingCropUpload();
  mediaPreviewType.value = type;
  const crop = type === "bg" ? cropRectBg.value : cropRectAvatar.value;
  cropRectDraft.value = { ...crop };
  cropNaturalSize.value = { w: 0, h: 0 };
  mediaPreviewOpen.value = true;
}

function closeMediaPreview() {
  if (isViewingSelf.value && !pendingCropUploadFile.value) {
    applyDraftCrop();
  }
  clearPendingCropUpload();
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

function clearPendingCropUpload() {
  pendingCropUploadFile.value = null;
  pendingCropUploadType.value = null;
  if (pendingCropUploadUrl.value) {
    try {
      URL.revokeObjectURL(pendingCropUploadUrl.value);
    } catch {
      /* ignore */
    }
  }
  pendingCropUploadUrl.value = "";
}

function beginCropUpload(type, file) {
  clearPendingCropUpload();
  pendingCropUploadFile.value = file;
  pendingCropUploadType.value = type;
  pendingCropUploadUrl.value = URL.createObjectURL(file);
  mediaPreviewType.value = type;
  cropRectDraft.value = { L: 0, T: 0, W: 1, H: 1 };
  mediaPreviewOpen.value = true;
}

function imageFromUrl(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("图片解码失败"));
    img.src = url;
  });
}

async function buildCroppedUploadFile(sourceUrl, type, rect, fallbackName) {
  const img = await imageFromUrl(sourceUrl);
  const nw = img.naturalWidth || 0;
  const nh = img.naturalHeight || 0;
  if (nw <= 0 || nh <= 0) {
    throw new Error("无法读取图片尺寸");
  }
  const r = normalizeRect(rect);
  const sx = Math.round(r.L * nw);
  const sy = Math.round(r.T * nh);
  const sw = Math.max(1, Math.round(r.W * nw));
  const sh = Math.max(1, Math.round(r.H * nh));
  const outW = type === "avatar" ? 640 : 1600;
  const outH = type === "avatar" ? 640 : 500;
  const canvas = document.createElement("canvas");
  canvas.width = outW;
  canvas.height = outH;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    throw new Error("浏览器不支持画布裁剪");
  }
  ctx.drawImage(img, sx, sy, sw, sh, 0, 0, outW, outH);
  const blob = await new Promise((resolve, reject) => {
    canvas.toBlob((b) => {
      if (b) resolve(b);
      else reject(new Error("导出裁剪图片失败"));
    }, "image/jpeg", 0.92);
  });
  const safeName = String(fallbackName || `${type}-${Date.now()}.jpg`).replace(/\.[^.]+$/, "");
  return new File([blob], `${safeName}-crop.jpg`, { type: "image/jpeg" });
}

async function submitCropUploadFromPreview() {
  if (!pendingCropUploadFile.value || !pendingCropUploadType.value) {
    return;
  }
  profileUploadBusy.value = true;
  profileMsg.value = "";
  try {
    const rect = normalizeRect({ ...cropRectDraft.value });
    const cropped = await buildCroppedUploadFile(
      pendingCropUploadUrl.value,
      pendingCropUploadType.value,
      rect,
      pendingCropUploadFile.value?.name
    );
    if (pendingCropUploadType.value === "avatar") {
      const url = await updateUserAvatar(cropped);
      if (profileUser.value) {
        profileUser.value = normalizeUserProfile({ ...profileUser.value, avatarUrl: url });
      }
      cropRectAvatar.value = { L: 0, T: 0, W: 1, H: 1 };
      profileMsg.value = "头像已裁剪并上传";
    } else {
      const url = await updateUserBgImage(cropped);
      if (profileUser.value) {
        profileUser.value = normalizeUserProfile({ ...profileUser.value, bgImage: url });
      }
      cropRectBg.value = { L: 0, T: 0, W: 1, H: 1 };
      profileMsg.value = "背景图已裁剪并上传";
    }
    clearPendingCropUpload();
    mediaPreviewOpen.value = false;
  } catch (error) {
    profileMsg.value = `裁剪上传失败：${error.message}`;
  } finally {
    profileUploadBusy.value = false;
  }
}

async function onAvatarFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) {
    return;
  }
  beginCropUpload("avatar", file);
  profileMsg.value = "请先裁剪头像后再上传";
}

async function onBgFileChange(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) {
    return;
  }
  beginCropUpload("bg", file);
  profileMsg.value = "请先裁剪背景图后再上传";
}

function onProfile() {
  if (activeMenuKey.value === "home") {
    saveHomeFeedSnapshot();
  }
  if (activeMenuKey.value === "shorts") {
    void flushShortsHistoryQueue();
  }
  showStudio.value = false;
  uploadModalOpen.value = false;
  if (showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  profileTargetUserId.value = null;
  showProfileCenter.value = true;
  try {
    history.pushState({ myyoutubeProfile: "self" }, "", window.location.href);
  } catch {
    /* ignore */
  }
  activeMenuLabel.value = "个人中心";
  pageHint.value = "这里展示用户主页、作品和数据概览。";
  loadProfileUser();
}

/** 进入他人主页（可从视频作者、关注列表等调用 userId；此处提供顶部「Ta」演示入口） */
function openOtherUserProfile(userId) {
  if (activeMenuKey.value === "home") {
    saveHomeFeedSnapshot();
  }
  if (activeMenuKey.value === "shorts") {
    void flushShortsHistoryQueue();
  }
  profileTargetUserId.value = userId;
  showProfileCenter.value = true;
  try {
    history.pushState({ myyoutubeProfile: String(userId) }, "", window.location.href);
  } catch {
    /* ignore */
  }
  activeMenuLabel.value = "个人中心";
  pageHint.value = "正在查看他人主页。";
  loadProfileUser();
}

function onAuthorAvatarClick(event, authorId) {
  event?.stopPropagation?.();
  if (authorId == null || authorId === "") {
    return;
  }
  const current = currentUserId.value != null ? String(currentUserId.value) : "";
  if (current && String(authorId) === current) {
    onProfile();
    return;
  }
  openOtherUserProfile(authorId);
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
  const id = item?.id != null ? String(item.id) : "";
  if (id && profileWorkVideoUrlCache.value[id]) {
    return profileWorkVideoUrlCache.value[id];
  }
  return item?.videoUrl || item?.playUrl || item?.url || "";
}

function resolveVideoUrlWithFallback(item, seed = "") {
  return resolveVideoUrl(item) || pickFallbackVideoUrl(seed || item?.id || item?.title || "");
}

function videoCardPlayable(card) {
  return Boolean(resolveVideoUrl(card));
}

function sanitizeUploadFilename(name) {
  const base = String(name || "video.mp4").replace(/[^a-zA-Z0-9._-]/g, "_");
  return base || "video.mp4";
}

function buildUploadObjectKey(sts, file) {
  const dir = String(sts?.dir || "uploads/video/anonymous/");
  const safeDir = dir.endsWith("/") ? dir : `${dir}/`;
  const safeFile = sanitizeUploadFilename(file?.name);
  return `${safeDir}${Date.now()}-${Math.random().toString(16).slice(2, 10)}-${safeFile}`;
}

function tryParseJsonLike(input) {
  if (typeof input !== "string") return input;
  const text = input.trim();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}

function pickStsField(obj, keys) {
  for (const k of keys) {
    const v = obj?.[k];
    if (v !== undefined && v !== null && String(v).trim() !== "") return v;
  }
  return "";
}

function deepCollectObjects(input, out = []) {
  const node = tryParseJsonLike(input);
  if (!node) return out;
  if (Array.isArray(node)) {
    node.forEach((it) => deepCollectObjects(it, out));
    return out;
  }
  if (typeof node !== "object") return out;
  out.push(node);
  Object.values(node).forEach((v) => deepCollectObjects(v, out));
  return out;
}

function normalizeStsPayload(raw) {
  const r0 = tryParseJsonLike(raw);
  const x0 = r0?.data ?? r0?.result ?? r0 ?? {};
  const objects = deepCollectObjects(x0, []);
  let finalObj = {};
  for (const obj of objects) {
    const accessKeyId = pickStsField(obj, ["accessKeyId", "AccessKeyId", "accessKeyID", "ak", "access_key_id"]);
    const accessKeySecret = pickStsField(obj, ["accessKeySecret", "AccessKeySecret", "sk", "access_key_secret"]);
    const securityToken = pickStsField(obj, ["securityToken", "SecurityToken", "stsToken", "token"]);
    const bucket = pickStsField(obj, ["bucket", "bucketName", "Bucket", "bucket_name"]);
    const region = pickStsField(obj, ["region", "Region", "regionId"]);
    if (accessKeyId || accessKeySecret || securityToken || bucket || region) {
      finalObj = obj;
      break;
    }
  }
  if (!Object.keys(finalObj).length && r0 && typeof r0 === "object") {
    finalObj = r0;
  }
  return {
    accessKeyId: pickStsField(finalObj, ["accessKeyId", "AccessKeyId", "accessKeyID", "ak", "access_key_id"]),
    accessKeySecret: pickStsField(finalObj, ["accessKeySecret", "AccessKeySecret", "sk", "access_key_secret"]),
    securityToken: pickStsField(finalObj, ["securityToken", "SecurityToken", "stsToken", "token"]),
    bucket: pickStsField(finalObj, ["bucket", "bucketName", "Bucket", "bucket_name"]),
    endpoint: pickStsField(finalObj, ["endpoint", "EndPoint", "host"]),
    region: pickStsField(finalObj, ["region", "Region", "regionId"]),
    dir: pickStsField(finalObj, ["dir", "uploadDir", "prefix"]) || "uploads/video/anonymous/",
    expiration: pickStsField(finalObj, ["expiration", "Expiration"]),
    __rawKeys: Object.keys(finalObj),
    __rawType: Array.isArray(r0) ? "array" : typeof r0,
  };
}

function createOssClient(sts) {
  const regionRaw = String(sts?.region || "").trim();
  const region = regionRaw.startsWith("oss-") ? regionRaw : `oss-${regionRaw}`;
  return new OSS({
    region,
    accessKeyId: sts.accessKeyId,
    accessKeySecret: sts.accessKeySecret,
    stsToken: sts.securityToken,
    bucket: sts.bucket,
    secure: true,
    /** 分片上传时单片请求易超时，适当拉长（毫秒） */
    timeout: 120000,
  });
}

function readVideoDuration(file) {
  return new Promise((resolve) => {
    try {
      const el = document.createElement("video");
      const objectUrl = URL.createObjectURL(file);
      el.preload = "metadata";
      el.src = objectUrl;
      el.onloadedmetadata = () => {
        const sec = Number.isFinite(el.duration) ? Math.max(1, Math.ceil(el.duration)) : 0;
        uploadPickedDuration.value = sec;
        URL.revokeObjectURL(objectUrl);
        resolve(sec);
      };
      el.onerror = () => {
        uploadPickedDuration.value = 0;
        URL.revokeObjectURL(objectUrl);
        resolve(0);
      };
    } catch {
      uploadPickedDuration.value = 0;
      resolve(0);
    }
  });
}

async function submitUploadVideo() {
  if (uploadSubmitting.value) {
    return;
  }
  const file = uploadPickedFile.value;
  if (!file) {
    uploadMsg.value = "请先选择视频文件";
    pushToast("请先选择视频文件", "info");
    return;
  }
  const title = String(uploadForm.value.title || "").trim();
  if (!title) {
    uploadMsg.value = "请填写视频标题";
    pushToast("请填写视频标题", "info");
    return;
  }
  uploadSubmitting.value = true;
  uploadMsg.value = "";
  uploadProgress.value = 0;
  try {
    const rawSts = await getOssSts();
    const sts = normalizeStsPayload(rawSts);
    if (!sts.accessKeyId || !sts.accessKeySecret || !sts.securityToken || !sts.bucket || !sts.region) {
      throw new Error(
        `STS 凭证不完整（缺少 accessKeyId/accessKeySecret/securityToken/bucket/region）。响应类型：${sts.__rawType || "unknown"}；已收到字段：${(sts.__rawKeys || []).join(",") || "无"}`,
      );
    }
    const client = createOssClient(sts);
    const objectKey = buildUploadObjectKey(sts, file);
    /** 大文件单次 put 易 Connect timeout，改用 SDK 分片上传 */
    await client.multipartUpload(objectKey, file, {
      parallel: 4,
      partSize: 4 * 1024 * 1024,
      progress: (p) => {
        uploadProgress.value = Math.max(0, Math.min(100, Math.round((p || 0) * 100)));
      },
    });
    uploadProgress.value = 100;

    const duration = uploadPickedDuration.value || (await readVideoDuration(file));
    uploadMsg.value = "正在发布视频…";
    await publishVideo({
      title,
      description: String(uploadForm.value.description || "").trim(),
      videoUrl: objectKey,
      duration: Number(duration || 1),
      category: uploadForm.value.category,
      tags: uploadTagList.value.join(","),
      visibility: uploadForm.value.visibility,
      allowComment: Number(uploadForm.value.allowComment ? 1 : 0),
    });

    uploadMsg.value = "上传成功，等待审核…";
    pushToast("上传成功，等待审核…", "success");
    pageHint.value = `已发布：${title}`;
    uploadModalOpen.value = false;
    uploadPickedFile.value = null;
    uploadPickedDuration.value = 0;
    uploadForm.value = {
      title: "",
      description: "",
      category: "LIFE",
      visibility: "PUBLIC",
      tags: "",
      allowComment: 1,
    };
    uploadTagEditorOpen.value = false;
    uploadTagDraft.value = "";
    if (showStudio.value) {
      void loadStudioVideos();
    }
    if (showProfileCenter.value) {
      void loadProfileWorks();
    }
  } catch (e) {
    uploadMsg.value = `发布失败：${e.message || e}`;
    pushToast(`发布失败：${e.message || e}`, "error");
  } finally {
    uploadSubmitting.value = false;
    uploadProgress.value = 0;
  }
}

const unifiedFeedList = computed(() => {
  if (feedScope.value === "profile") {
    return profileFeedQueue.value;
  }
  if (feedScope.value === "home") {
    return homeShortsRows.value;
  }
  return cards.value;
});

const shortsFeedCurrent = computed(() => {
  const list = unifiedFeedList.value;
  if (!list.length) {
    return null;
  }
  const i = Math.min(Math.max(0, shortsFeedIndex.value), list.length - 1);
  return list[i];
});

const shortsFeedVideoSrc = computed(() => resolveVideoUrl(shortsFeedCurrent.value));

const shortsFeedAuthorName = computed(() => {
  const c = shortsFeedCurrent.value;
  const nick = stripUserIdLikeName(c?.authorNickname) || stripUserIdLikeName(c?.author);
  if (nick) return nick;
  if (feedScope.value === "profile") {
    const p = stripUserIdLikeName(profileUser.value?.nickName || profileUser.value?.nickname);
    if (p) return p;
  }
  return "创作者";
});

const shortsFeedMetaTimeText = computed(() => formatRelativeTimeText(shortsFeedCurrent.value?.createTime || ""));
const shortsFeedTitleText = computed(() => shortsFeedCurrent.value?.title || "未命名主题");
const shortsFeedDescText = computed(
  () => shortsFeedCurrent.value?.description || shortsFeedCurrent.value?.title || ""
);
const shortsFeedTagText = computed(() => {
  const tags = parseTagsToList(shortsFeedCurrent.value?.tags || "");
  return tags.map((t) => `#${t}`).join(" ");
});

/** 仅认作者相关字段；列表里的 avatarUrl 多为封面，不能当作作者头像回退 */
const shortsFeedAuthorAvatar = computed(() => {
  const c = shortsFeedCurrent.value;
  if (!c) {
    return profileAvatar;
  }
  const fromAuthor =
    sanitizeImageUrl(c.authorAvatarUrl ?? "") || sanitizeImageUrl(c.authorAvatar ?? "");
  if (fromAuthor) {
    return fromAuthor;
  }
  return profileAvatar;
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

function enqueueShortsHistoryByVideoId(videoIdLike) {
  if (!isAuthed.value) return;
  const videoId = safeSnowflakeId(videoIdLike, "");
  if (!videoId) return;
  if (shortsHistorySeenSet.has(videoId) || shortsHistoryQueueSet.has(videoId)) return;
  shortsHistoryQueueSet.add(videoId);
  shortsHistoryQueue.push(videoId);
  if (shortsHistoryQueue.length >= SHORTS_HISTORY_BATCH_SIZE) {
    void flushShortsHistoryQueue();
  }
}

async function sendShortsHistoryBatch(videoIds, { keepalive = false } = {}) {
  if (!Array.isArray(videoIds) || !videoIds.length) return;
  const shortIds = videoIds
    .map((id) => safeSnowflakeId(id))
    .filter((id) => id !== "" && id != null);
  if (!shortIds.length) return;
  const payload = {
    userId: currentUserId.value || undefined,
    shortIds,
  };
  if (payload.userId != null && payload.userId !== "") {
    payload.userId = safeSnowflakeId(payload.userId);
  }
  if (keepalive) {
    const headers = { "Content-Type": "application/json" };
    if (token.value) {
      headers.Authorization = token.value;
    }
    await fetch("/videos/shorts/play", {
      method: "PUT",
      headers,
      body: JSON.stringify(payload),
      keepalive: true,
    });
    return;
  }
  await reportShortHistoryBatch(payload);
}

async function flushShortsHistoryQueue({ keepalive = false } = {}) {
  if (!shortsHistoryQueue.length || shortsHistoryFlushInFlight) return;
  shortsHistoryFlushInFlight = true;
  const batch = [...shortsHistoryQueue];
  shortsHistoryQueue = [];
  shortsHistoryQueueSet.clear();
  try {
    await sendShortsHistoryBatch(batch, { keepalive });
    batch.forEach((id) => shortsHistorySeenSet.add(id));
  } catch {
    batch.forEach((id) => {
      if (!shortsHistoryQueueSet.has(id)) {
        shortsHistoryQueueSet.add(id);
        shortsHistoryQueue.push(id);
      }
    });
  } finally {
    shortsHistoryFlushInFlight = false;
  }
}

function shortsFeedPrev() {
  const cur = shortsFeedCurrent.value;
  const dur = Number(shortsFeedDuration.value || 0);
  const now = Number(shortsFeedCurrentTime.value || 0);
  if (isLongShortsFeedVideo(cur)) {
    void flushShortsLongPlaybackResume("switch");
  }
  if (cur?.id) {
    enqueueShortsHistoryByVideoId(cur.id);
  }
  if (cur?.id && dur > 0 && now / dur < 0.95) {
    void reportBehaviorSilently(cur.id, "SKIP", now * 1000);
  }
  const n = unifiedFeedList.value.length;
  if (!n) {
    return;
  }
  shortsFeedIndex.value = (shortsFeedIndex.value - 1 + n) % n;
}

function shortsFeedNext() {
  const cur = shortsFeedCurrent.value;
  const dur = Number(shortsFeedDuration.value || 0);
  const now = Number(shortsFeedCurrentTime.value || 0);
  if (isLongShortsFeedVideo(cur)) {
    void flushShortsLongPlaybackResume("switch");
  }
  if (cur?.id) {
    enqueueShortsHistoryByVideoId(cur.id);
  }
  if (cur?.id && dur > 0 && now / dur < 0.95) {
    void reportBehaviorSilently(cur.id, "SKIP", now * 1000);
  }
  const n = unifiedFeedList.value.length;
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
  applyShortsLongPlaybackResumeIfNeeded(v);
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
  const cur = shortsFeedCurrent.value;
  const duration = Number(fg.duration);
  const current = Number(fg.currentTime || 0);
  if (cur?.id && Number.isFinite(duration) && duration > 0) {
    const idKey = String(cur.id);
    if (shortsAccVideoId.value !== idKey) {
      shortsAccVideoId.value = idKey;
      shortsPlaybackAccSec.value = 0;
      shortsLastTimeSec.value = null;
    }
    const prev = shortsLastTimeSec.value;
    shortsLastTimeSec.value = current;
    if (prev != null) {
      const delta = current - prev;
      if (delta > 0 && delta <= BEHAVIOR_MAX_PLAY_DELTA_SEC) {
        shortsPlaybackAccSec.value += delta;
      }
    }
    if (
      shortsPlaybackAccSec.value >= duration * BEHAVIOR_PLAY_THRESHOLD &&
      !hasPlayReported(cur.id)
    ) {
      markPlayReported(cur.id);
      void reportBehaviorSilently(cur.id, "PLAY", current * 1000);
    }
    if (isLongShortsFeedVideo(cur) && !fg.paused) {
      shortsLongResumeTickCount += 1;
      if (shortsLongResumeTickCount >= 30) {
        shortsLongResumeTickCount = 0;
        void flushShortsLongPlaybackResume("tick");
      }
    }
  }
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
  } else if (k === " ") {
    ev.preventDefault();
    shortsTogglePlay();
  }
}

function onDetailPageKeydown(ev) {
  if (!showVideoDetail.value || !detailPlayerRef.value) return;
  const tag = ev.target?.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable) return;
  const player = detailPlayerRef.value;
  const key = ev.key;
  if (key === " ") {
    ev.preventDefault();
    if (player.paused) player.play().catch(() => {});
    else player.pause();
    return;
  }
  if (key === "f" || key === "F") {
    ev.preventDefault();
    if (document.fullscreenElement) {
      document.exitFullscreen?.();
    } else {
      player.requestFullscreen?.().catch(() => {});
    }
    return;
  }
  if (key === "m" || key === "M") {
    ev.preventDefault();
    player.muted = !player.muted;
    return;
  }
  if (key === "ArrowLeft") {
    ev.preventDefault();
    player.currentTime = Math.max(0, Number(player.currentTime || 0) - 5);
    return;
  }
  if (key === "ArrowRight") {
    ev.preventDefault();
    const duration = Number(player.duration || 0);
    const next = Number(player.currentTime || 0) + 5;
    player.currentTime = duration > 0 ? Math.min(duration, next) : next;
  }
}

function closeTopMostOverlayByEsc() {
  if (uploadModalOpen.value) {
    uploadModalOpen.value = false;
    return true;
  }
  if (mediaPreviewOpen.value) {
    closeMediaPreview();
    return true;
  }
  if (relationTableOpen.value) {
    closeRelationTable();
    return true;
  }
  if (profileEditOpen.value) {
    closeProfileEdit();
    return true;
  }
  if (shortPlayerOpen.value) {
    closeShortPlayer();
    return true;
  }
  if (profileFeedOpen.value) {
    closeProfileFeed();
    return true;
  }
  if (showVideoDetail.value) {
    closeVideoDetail();
    return true;
  }
  if (showStudio.value) {
    closeStudio();
    return true;
  }
  if (showProfileCenter.value) {
    setActiveMenu(primaryMenus[0]);
    return true;
  }
  return false;
}

function onGlobalKeydown(ev) {
  const tag = ev.target?.tagName;
  const inEditable = tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable;

  if (ev.key === "Escape") {
    ev.preventDefault();
    closeTopMostOverlayByEsc();
    return;
  }

  if (ev.key === "/" && !inEditable && !showVideoDetail.value) {
    ev.preventDefault();
    searchInputRef.value?.focus?.();
    return;
  }

  onDetailPageKeydown(ev);
}

function onShortsFeedFgPlay() {
  shortsFeedPaused.value = false;
  enqueueShortsHistoryByVideoId(shortsFeedCurrent.value?.id);
  if (isLongShortsFeedVideo()) {
    shortsLongResumeTickCount = 0;
  }
  shortsFeedBgRef.value?.play().catch(() => {});
}

function onShortsFeedFgPause() {
  shortsFeedPaused.value = true;
  if (isLongShortsFeedVideo()) {
    void flushShortsLongPlaybackResume("pause");
  }
  shortsFeedBgRef.value?.pause();
}

function onShortsFeedFgSeeked(e) {
  shortsLastTimeSec.value = null;
  const fg = e.target;
  const bg = shortsFeedBgRef.value;
  if (bg) {
    bg.currentTime = fg.currentTime;
  }
}

function isLongShortsFeedVideo(item = shortsFeedCurrent.value) {
  const vt = item?.videoType;
  if (!vt) return false;
  const s = String(vt).toLowerCase().trim();
  return s === "long" || s === "long_video" || s === "longvideo" || s.includes("long");
}

function getShortsLongCurrentPlayTimeMs() {
  const player = shortsFeedFgRef.value;
  if (!player) return 0;
  const ms = Math.floor((Number(player.currentTime || 0) || 0) * 1000);
  return ms > 0 ? ms : 0;
}

function getShortsLongPlaybackResumePayload(playTimeMs) {
  const userId = currentUserId.value ?? profileUser.value?.id;
  const videoId = shortsFeedCurrent.value?.id;
  if (!userId || userId === "" || !videoId) return null;
  if (!Number.isFinite(Number(playTimeMs))) return null;
  return { userId, videoId, playTime: playTimeMs };
}

function applyShortsLongPlaybackResumeIfNeeded(player) {
  const cur = shortsFeedCurrent.value;
  if (!isLongShortsFeedVideo(cur)) return;
  if (!cur || !player) return;
  const vid = String(cur.id ?? "");
  if (!vid || shortsLongResumeAppliedVideoId === vid) return;
  const playbackMs = Number(cur.playbackTime ?? 0);
  if (!Number.isFinite(playbackMs) || playbackMs <= 0) {
    shortsLongResumeAppliedVideoId = vid;
    return;
  }
  const targetSec = playbackMs / 1000;
  const duration = Number(player.duration);
  if (!Number.isFinite(targetSec) || targetSec <= 0 || !Number.isFinite(duration) || duration <= 0) {
    return;
  }
  player.currentTime = Math.max(0, Math.min(targetSec, Math.max(0, duration - 1)));
  shortsLongResumeAppliedVideoId = vid;
}

async function sendShortsLongPlaybackResume(payload, { keepalive = false } = {}) {
  if (!payload) return;
  try {
    const headers = { "Content-Type": "application/json" };
    if (token.value) headers.Authorization = token.value;
    await fetch("/videos/playback/resume", {
      method: "PUT",
      headers,
      body: JSON.stringify(payload),
      keepalive,
    });
  } catch {
    /* ignore */
  }
}

function flushShortsLongPlaybackResume(reason) {
  const cur = shortsFeedCurrent.value;
  const player = shortsFeedFgRef.value;
  if (!isLongShortsFeedVideo(cur) || !player) return Promise.resolve();
  if (reason !== "pause" && player.paused) return Promise.resolve();
  const playTimeMs = getShortsLongCurrentPlayTimeMs();
  if (reason !== "pause" && reason !== "switch" && reason !== "leave" && reason !== "unload" && playTimeMs <= 0) {
    return Promise.resolve();
  }
  const payload = getShortsLongPlaybackResumePayload(playTimeMs);
  if (!payload) return Promise.resolve();
  const now = Date.now();
  if (reason === "tick" && now - shortsLongResumeLastSentAtMs < 8_000) {
    return Promise.resolve();
  }
  if (shortsLongResumeInFlight) return Promise.resolve();
  shortsLongResumeInFlight = true;
  shortsLongResumeLastSentAtMs = now;
  const keepalive = reason === "unload" || reason === "leave";
  return sendShortsLongPlaybackResume(payload, { keepalive }).finally(() => {
    shortsLongResumeInFlight = false;
  });
}

function onDetailVideoSeeked() {
  detailLastTimeSec.value = null;
}

function onShortsLikeClick() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  void reportBehaviorSilently(cur.id, "LIKE", Number(shortsFeedCurrentTime.value || 0) * 1000);
}

function onShortsDislikeClick() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  void reportBehaviorSilently(cur.id, "DISLIKE", Number(shortsFeedCurrentTime.value || 0) * 1000);
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

async function ensureProfileWorkVideoUrlById(videoId) {
  const id = videoId != null ? String(videoId) : "";
  if (!id) return "";
  if (profileWorkVideoUrlCache.value[id]) return profileWorkVideoUrlCache.value[id];
  if (profileWorkVideoUrlLoading.value[id]) return "";
  const nextLoading = { ...profileWorkVideoUrlLoading.value, [id]: true };
  profileWorkVideoUrlLoading.value = nextLoading;
  try {
    const raw = await fetchVideoDetail(id);
    const d = normalizeVideoDetail(raw);
    const url = d?.videoUrl ? String(d.videoUrl) : "";
    if (d && typeof d === "object") {
      profileWorkDetailCache.value = {
        ...profileWorkDetailCache.value,
        [id]: {
          title: d.title || "",
          description: d.description || "",
          tags: d.tags || "",
          authorNickname: d.authorNickname || "",
          authorAvatarUrl: d.authorAvatarUrl || "",
          videoUrl: url || "",
        },
      };
    }
    if (url) {
      profileWorkVideoUrlCache.value = { ...profileWorkVideoUrlCache.value, [id]: url };
      return url;
    }
    return "";
  } catch {
    return "";
  } finally {
    const m = { ...profileWorkVideoUrlLoading.value };
    delete m[id];
    profileWorkVideoUrlLoading.value = m;
  }
}

async function openProfileWorkFeed(item) {
  const source = profileWorksRows.value;
  if (!source.length) {
    pageHint.value = "该作者暂无可播放作品";
    return;
  }
  let selectedUrl = resolveVideoUrl(item);
  if (!selectedUrl && item?.id != null) {
    selectedUrl = await ensureProfileWorkVideoUrlById(item.id);
  }
  if (!selectedUrl) {
    pageHint.value = "该内容暂无视频地址";
    return;
  }
  const playableQueue = source
    .map((x) => {
      const url = resolveVideoUrl(x);
      return { ...x, videoUrl: url };
    })
    .filter((x) => x.videoUrl);
  if (!playableQueue.find((x) => String(x.id) === String(item.id))) {
    playableQueue.unshift({ ...item, videoUrl: selectedUrl });
  }
  source.forEach((x) => {
    const xid = x?.id != null ? String(x.id) : "";
    if (xid && !profileWorkVideoUrlCache.value[xid] && !profileWorkVideoUrlLoading.value[xid]) {
      void ensureProfileWorkVideoUrlById(xid);
    }
  });
  if (!playableQueue.length && selectedUrl) {
    playableQueue.push({ ...item, videoUrl: selectedUrl });
  }
  const idx = playableQueue.findIndex((x) => String(x.id) === String(item.id));
  feedScope.value = "profile";
  profileFeedQueue.value = playableQueue;
  shortsFeedIndex.value = idx >= 0 ? idx : 0;
  shortsFeedAutoplayPending.value = true;
  profileFeedOpen.value = true;
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

function closeProfileFeed() {
  void flushShortsLongPlaybackResume("leave");
  shortsFeedFgRef.value?.pause();
  shortsFeedBgRef.value?.pause();
  profileFeedOpen.value = false;
  feedScope.value = "shorts";
  profileFeedQueue.value = [];
  shortsLongResumeTickCount = 0;
  shortsLongResumeAppliedVideoId = "";
}

function onProfileFeedKeydown(ev) {
  if (ev.key === "Escape") {
    closeProfileFeed();
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
  } else if (k === " ") {
    ev.preventDefault();
    shortsTogglePlay();
  }
}

function onWorkCardClick(item) {
  void openProfileWorkFeed(item);
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

watch(unifiedFeedList, (list) => {
  if (!list.length) {
    shortsFeedIndex.value = 0;
    return;
  }
  if (shortsFeedIndex.value >= list.length) {
    shortsFeedIndex.value = 0;
  }
});

watch(profileFeedOpen, (open) => {
  if (open) {
    window.addEventListener("keydown", onProfileFeedKeydown);
  } else {
    window.removeEventListener("keydown", onProfileFeedKeydown);
  }
});

watch(activeMenuKey, (key) => {
  stopCardHoverPreview();
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
    maybePrefetchHomeShortsForFeed();
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
    pushToast("登录成功", "success");
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
    pushToast(`登录失败：${error.message || "请检查账号信息"}`, "error");
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
    pushToast(message || "注册成功，请登录", "success");
    activeAuthTab.value = "login";
    loginForm.value.username = registerForm.value.username.trim();
  } catch (error) {
    authMessage.value = `注册失败：${error.message}`;
    pushToast(`注册失败：${error.message || "请稍后重试"}`, "error");
  } finally {
    authLoading.value = false;
  }
}

function logout() {
  void flushShortsHistoryQueue();
  clearToken();
  token.value = "";
  shortsHistoryQueue = [];
  shortsHistoryQueueSet.clear();
  shortsHistorySeenSet.clear();
  homeFeedSnapshot.value = null;
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
  pushToast("已退出登录", "info");
  try {
    sessionStorage.removeItem(UI_STATE_STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

function saveUiStateToSession() {
  if (!isAuthed.value) return;
  const state = {
    activeMenuKey: activeMenuKey.value,
    activeMenuLabel: activeMenuLabel.value,
    showProfileCenter: showProfileCenter.value,
    profileTargetUserId: profileTargetUserId.value,
    showStudio: showStudio.value,
    homeCategoryKey: homeCategoryKey.value,
    homeSortKey: homeSortKey.value,
    studioSideNav: studioSideNav.value,
    studioContentTab: studioContentTab.value,
  };
  try {
    sessionStorage.setItem(UI_STATE_STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* ignore */
  }
}

function loadUiStateFromSession() {
  try {
    const raw = sessionStorage.getItem(UI_STATE_STORAGE_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw);
    if (!s || typeof s !== "object") return null;
    return s;
  } catch {
    return null;
  }
}

function onAuthExpired(event) {
  const msg = event?.detail?.reason || "登录状态已过期，请重新登录。";
  clearToken();
  token.value = "";
  showVideoDetail.value = false;
  showProfileCenter.value = false;
  showStudio.value = false;
  uploadModalOpen.value = false;
  profileFeedOpen.value = false;
  clearVideoDetailFromAddressBar();
  authMessage.value = msg;
}

function onWindowPopState() {
  if (profileFeedOpen.value) {
    closeProfileFeed();
    return;
  }
  if (showStudio.value) {
    showStudio.value = false;
    uploadModalOpen.value = false;
    studioHistoryPushed.value = false;
    void reloadFeedAfterLeavingDetail();
    return;
  }
  if (showProfileCenter.value || activeMenuKey.value !== "home") {
    showProfileCenter.value = false;
    setActiveMenu(primaryMenus[0]);
    return;
  }
  const v = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
  if (!v && showVideoDetail.value) {
    detailAutoplayMutedFallback.value = false;
    try {
      // 切走详情（浏览器返回/关闭 v 参数）：立即上报长视频续播
      void flushDetailPlaybackResume("leave");
      resetDetailPlaybackResumeState();
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

watch(
  () => [
    activeMenuKey.value,
    showProfileCenter.value,
    profileTargetUserId.value,
    showStudio.value,
    homeCategoryKey.value,
    homeSortKey.value,
    studioSideNav.value,
    studioContentTab.value,
  ],
  () => {
    saveUiStateToSession();
  }
);

onMounted(async () => {
  applyAppFavicon();
  window.addEventListener("resize", onWindowResize);
  window.addEventListener("scroll", onWindowScroll, { passive: true });
  window.addEventListener("keydown", onGlobalKeydown);
  window.addEventListener("popstate", onWindowPopState);
  window.addEventListener("pageshow", onWindowPageShow);
  window.addEventListener(getAuthExpiredEventName(), onAuthExpired);
  // 关闭/切走页面兜底：用 keepalive 尽量发送
  window.addEventListener("pagehide", () => {
    void flushShortsHistoryQueue({ keepalive: true });
    if (profileFeedOpen.value) {
      void flushShortsLongPlaybackResume("unload");
    }
    if (!showVideoDetail.value) return;
    void flushDetailPlaybackResume("unload");
  });
  window.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      void flushShortsHistoryQueue({ keepalive: true });
      if (profileFeedOpen.value) {
        void flushShortsLongPlaybackResume("unload");
      }
    }
    // 切后台时也尽量保存（只有长视频且播放中才会真正 flush）
    if (document.visibilityState !== "hidden") return;
    if (!showVideoDetail.value) return;
    void flushDetailPlaybackResume("unload");
  });

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
    const state = loadUiStateFromSession();
    if (state?.showProfileCenter) {
      profileTargetUserId.value = state.profileTargetUserId ?? null;
      showProfileCenter.value = true;
      activeMenuLabel.value = "个人中心";
      await loadProfileUser();
      return;
    }
    if (state?.showStudio) {
      showStudio.value = true;
      showProfileCenter.value = false;
      studioSideNav.value = state.studioSideNav || "info";
      studioContentTab.value = state.studioContentTab || "video";
      await loadStudioVideos();
      return;
    }
    const menuKey = state?.activeMenuKey || homeMenu.key;
    const allMenus = [...primaryMenus, ...exploreMenus];
    const menu = allMenus.find((m) => m.key === menuKey) || homeMenu;
    homeCategoryKey.value = state?.homeCategoryKey || "all";
    homeSortKey.value = state?.homeSortKey || "RECOMMENDED";
    activeMenuKey.value = menu.key;
    activeMenuLabel.value = menu.label;
    await loadModuleCards(menu);
    if (menu.key === "shorts") {
      nextTick(() => {
        shortsFeedFgRef.value?.load();
        shortsFeedBgRef.value?.load();
      });
    }
  }
});

onBeforeUnmount(() => {
  stopCardHoverPreview();
  window.removeEventListener("resize", onWindowResize);
  window.removeEventListener("scroll", onWindowScroll);
  window.removeEventListener("keydown", onGlobalKeydown);
  window.removeEventListener("popstate", onWindowPopState);
  window.removeEventListener("pageshow", onWindowPageShow);
  window.removeEventListener(getAuthExpiredEventName(), onAuthExpired);
  stopDetailPlaybackResumeTimer();
  void flushShortsHistoryQueue({ keepalive: true });
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
            ref="searchInputRef"
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

      <div v-if="toasts.length" class="toast-stack" aria-live="polite" aria-atomic="true">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="toast-item"
          :class="`toast-item--${t.type}`"
        >
          <span class="toast-item__text">{{ t.message }}</span>
          <button type="button" class="toast-item__close" aria-label="关闭提示" @click="removeToast(t.id)">
            <i class="ri-close-line"></i>
          </button>
        </div>
      </div>

      <main
        v-show="!showVideoDetail"
        class="content"
        @scroll.passive="onContentScroll"
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
                      <td>{{ formatStudioVisibility(row) }}</td>
                      <td>{{ formatStudioDate(row) }}</td>
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
            <div class="home-sort-inline">
              <label class="home-sort-label" for="home-sort-select">排序</label>
              <select
                id="home-sort-select"
                class="home-sort-select"
                :value="homeSortKey"
                @change="selectHomeSort($event.target.value)"
              >
                <option v-for="opt in homeSortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
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
                  <strong>{{ profileUser?.videoCount ?? profileWorksDisplay.length }}</strong>
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
                <div class="profile-works-toolbar">
                  <div class="profile-works-filters">
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'ALL' }"
                      @click="profileWorksFilter = 'ALL'"
                    >
                      全部
                    </button>
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'PUBLIC' }"
                      @click="profileWorksFilter = 'PUBLIC'"
                    >
                      公开
                    </button>
                    <button
                      type="button"
                      class="profile-works-filter-btn"
                      :class="{ 'profile-works-filter-btn--active': profileWorksFilter === 'PRIVATE' }"
                      :disabled="!isViewingSelf"
                      @click="profileWorksFilter = 'PRIVATE'"
                    >
                      私密
                    </button>
                  </div>
                  <span>共 {{ profileWorksDisplay.length }} 条</span>
                </div>
              </div>
              <p v-if="profileWorksLoading" class="profile-api-hint profile-api-hint--loading">正在加载作品列表…</p>
              <div v-if="profileWorksLoading" class="works-grid works-grid-profile works-grid-profile--skeleton">
                <article v-for="n in 6" :key="`work-skeleton-${n}`" class="work-card work-card--skeleton">
                  <div class="work-thumb"></div>
                  <div class="work-meta">
                    <p class="work-title"></p>
                  </div>
                </article>
              </div>
              <div class="works-grid works-grid-profile">
                <article
                  v-for="item in profileWorksDisplay"
                  :key="item.id"
                  class="work-card"
                  :class="{ 'work-card--playable': resolveVideoUrl(item) }"
                  @click="onWorkCardClick(item)"
                  @mouseenter="onCardHoverEnter(item, 'profile-work')"
                  @mouseleave="onCardHoverLeave(item, 'profile-work')"
                >
                  <div class="work-thumb">
                    <video
                      v-if="isCardPreviewPlaying(item, 'profile-work')"
                      class="work-preview-video"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else :src="item.image" :alt="item.title" />
                    <span class="work-like work-like--bottom-left"><i class="ri-heart-line"></i> {{ item.likes }}</span>
                  </div>
                  <div class="work-meta">
                    <img class="work-author-avatar" :src="item.authorAvatarUrl || profileAvatar" alt="" />
                    <div class="work-meta-main">
                      <div class="work-title-row">
                        <p class="work-title">{{ item.title }}</p>
                        <span class="work-play-stat" title="播放量">
                          <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(item.playCount) }}
                        </span>
                      </div>
                    </div>
                  </div>
                </article>
              </div>
              <div v-if="!profileWorksLoading && !profileWorksDisplay.length" class="profile-works-empty">
                <div class="profile-works-empty-icon"><i class="ri-movie-2-line"></i></div>
                <h5>暂无内容</h5>
                <p>该账号还未发布过作品哦～</p>
              </div>
          </section>
        </section>

        <div v-if="mediaPreviewOpen" class="modal-overlay modal-overlay--light" @click.self="closeMediaPreview">
          <div class="modal-card media-preview-card media-preview-card--light" role="dialog" aria-modal="true" @click.stop>
            <h3 class="media-preview-title media-preview-title--light">
              {{ mediaPreviewType === "avatar" ? "头像" : "背景图" }}
            </h3>
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
                    v-if="isViewingSelf && pendingCropUploadFile"
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
                  @click="pendingCropUploadFile ? submitCropUploadFromPreview() : pickMediaFromPreview()"
                >
                  {{
                    profileUploadBusy
                      ? "处理中…"
                      : pendingCropUploadFile
                        ? "裁剪并上传"
                        : "更换图片"
                  }}
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

        <section
          v-if="profileFeedOpen || (!showProfileCenter && activeMenuKey === 'shorts')"
          :class="profileFeedOpen ? 'profile-feed-overlay' : 'shorts-feed'"
        >
          <button
            v-if="profileFeedOpen"
            type="button"
            class="profile-feed-close"
            aria-label="关闭作品连播"
            @click="closeProfileFeed"
          >
            <i class="ri-close-line"></i>
          </button>
          <div v-if="loading && !profileFeedOpen" class="shorts-feed-loading">正在加载短视频…</div>
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
                      <p class="shorts-feed-user">
                        @{{ shortsFeedAuthorName }}
                        <span v-if="shortsFeedMetaTimeText" class="shorts-feed-time-dot"> · {{ shortsFeedMetaTimeText }}</span>
                      </p>
                      <p class="shorts-feed-topic">{{ shortsFeedTitleText }}</p>
                      <p class="shorts-feed-desc">
                        {{ shortsFeedDescText || "暂无描述" }}
                        <span v-if="shortsFeedTagText" class="shorts-feed-tags">{{ shortsFeedTagText }}</span>
                      </p>
                    </div>
                    <div class="shorts-feed-action-col">
                      <div class="shorts-feed-author-block">
                        <div class="shorts-feed-author-avatar-wrap">
                          <img
                            class="shorts-feed-author-avatar"
                            :src="shortsFeedAuthorAvatar"
                            alt=""
                            role="button"
                            title="查看作者主页"
                            @click.stop="onAuthorAvatarClick($event, shortsFeedCurrent?.authorId)"
                            @error="(e) => { e.target.src = profileAvatar; }"
                          />
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
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="点赞"
                          aria-label="点赞"
                          @click="onShortsLikeClick"
                        >
                          <i class="ri-heart-fill"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.likeCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button type="button" class="shorts-feed-action-btn" title="评论" aria-label="评论">
                          <i class="ri-chat-3-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.commentCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="收藏"
                          aria-label="收藏"
                        >
                          <i class="ri-star-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.favoriteCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="不感兴趣"
                          aria-label="不感兴趣"
                          @click="onShortsDislikeClick"
                        >
                          <i class="ri-thumb-down-line"></i>
                        </button>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button type="button" class="shorts-feed-action-btn" title="分享" aria-label="分享">
                          <i class="ri-share-forward-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.shareCount ?? 0 }}</span>
                      </div>
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

        <template v-if="!showProfileCenter && activeMenuKey !== 'shorts'">
          <section v-if="activeMenuKey === 'history'" class="history-shorts-page">
            <div class="history-shorts-head">
              <h3><i class="ri-history-line"></i> 历史播放记录</h3>
            </div>

            <section class="history-section">
              <div class="history-section-head">
                <h4>长视频历史记录</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="historyLongPageNo <= 1" @click="onHistoryLongPrevPage">
                    上一页
                  </button>
                  <span>{{ historyLongPageNo }} / {{ Math.max(1, historyLongPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="historyLongPageNo >= (historyLongPages || 1)"
                    @click="onHistoryLongNextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="historyLongLoading" class="shorts-feed-loading">正在加载长视频历史记录…</div>
              <div v-else-if="!historyLongRows.length" class="shorts-feed-empty">暂无长视频历史记录</div>
              <div v-else class="home-shorts-strip history-shorts-strip">
                <article
                  v-for="(card, index) in historyLongRows"
                  :key="`history-long-${card.id}-${index}`"
                  class="video-card history-long-card"
                  :class="{ 'video-card--playable': videoCardPlayable(card) }"
                  @click="onHistoryLongCardClick(card, index)"
                  @mouseenter="onCardHoverEnter(card, 'history-long')"
                  @mouseleave="onCardHoverLeave(card, 'history-long')"
                >
                  <video
                    v-if="isCardPreviewPlaying(card, 'history-long')"
                    class="video-cover video-cover--preview"
                    :src="activePreviewVideoUrl"
                    muted
                    autoplay
                    loop
                    playsinline
                    preload="metadata"
                  ></video>
                  <img v-else class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  <div class="video-info">
                    <img
                      class="video-card-author-avatar"
                      :src="cardAuthorAvatar(card, index)"
                      alt=""
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                    <div class="video-info-text">
                      <strong>{{ card.title }}</strong>
                      <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "—" }}</span>
                    </div>
                  </div>
                </article>
              </div>
              <div class="history-section-foot">
                <button type="button" class="history-expand-btn" @click="toggleHistoryLongExpanded">
                  <i class="ri-arrow-down-s-line"></i>{{ historyLongExpanded ? "收起" : "展开" }}
                </button>
              </div>
            </section>

            <section class="history-section">
              <div class="history-section-head">
                <h4>短视频历史记录</h4>
                <div class="history-pager">
                  <button type="button" class="history-layout-btn" :disabled="historyShortPageNo <= 1" @click="onHistoryShortPrevPage">
                    上一页
                  </button>
                  <span>{{ historyShortPageNo }} / {{ Math.max(1, historyShortPages || 1) }}</span>
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="historyShortPageNo >= (historyShortPages || 1)"
                    @click="onHistoryShortNextPage"
                  >
                    下一页
                  </button>
                </div>
              </div>
              <div v-if="historyShortLoading" class="shorts-feed-loading">正在加载短视频历史记录…</div>
              <div v-else-if="!historyShortRows.length" class="shorts-feed-empty">暂无短视频历史记录</div>
              <div v-else class="home-shorts-strip history-shorts-strip">
                <article
                  v-for="(card, index) in historyShortRows"
                  :key="`history-short-${card.id}-${index}`"
                  class="home-short-card"
                  @click="onHistoryShortCardClick(card, index)"
                  @mouseenter="onCardHoverEnter(card, 'history-short')"
                  @mouseleave="onCardHoverLeave(card, 'history-short')"
                >
                  <div class="home-short-media">
                    <video
                      v-if="isCardPreviewPlaying(card, 'history-short')"
                      class="home-short-cover home-short-cover--video video-cover--preview"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else class="home-short-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  </div>
                  <div class="home-short-card-footer">
                    <div class="home-short-card-main">
                      <p class="home-short-title">{{ card.title }}</p>
                      <span class="home-short-author">{{ cardAuthorLabel(card) }}</span>
                    </div>
                    <div class="home-short-stats">
                      <span class="home-short-stat" title="播放量">
                        <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.playCount) }}
                      </span>
                    </div>
                  </div>
                </article>
              </div>
              <div class="history-section-foot">
                <button type="button" class="history-expand-btn" @click="toggleHistoryShortExpanded">
                  <i class="ri-arrow-down-s-line"></i>{{ historyShortExpanded ? "收起" : "展开" }}
                </button>
              </div>
            </section>
          </section>

          <section v-else-if="activeMenuKey === 'home'" class="home-mixed-feed">
            <section class="card-grid">
              <article v-if="loading" v-for="n in 8" :key="`skeleton-home-a-${n}`" class="video-card video-card--skeleton">
                <div class="video-cover video-cover-loading"></div>
                <div class="video-info">
                  <div class="video-card-author-avatar"></div>
                  <div class="video-info-text">
                    <strong></strong>
                    <span></span>
                  </div>
                </div>
              </article>
              <article
                v-if="!loading"
                v-for="(card, index) in homeLongHeadCards"
                :key="`home-long-head-${card.id}`"
                class="video-card"
                :class="{ 'video-card--playable': videoCardPlayable(card) }"
                @click="onVideoCardClick(card)"
                @mouseenter="onCardHoverEnter(card, 'home-head')"
                @mouseleave="onCardHoverLeave(card, 'home-head')"
              >
                <video
                  v-if="isCardPreviewPlaying(card, 'home-head')"
                  class="video-cover video-cover--preview"
                  :src="activePreviewVideoUrl"
                  muted
                  autoplay
                  loop
                  playsinline
                  preload="metadata"
                ></video>
                <img v-else class="video-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                <div class="video-info">
                  <img
                    class="video-card-author-avatar"
                    :src="cardAuthorAvatar(card, index)"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="onAuthorAvatarClick($event, card.authorId)"
                    @error="(e) => (e.target.src = profileAvatar)"
                  />
                  <div class="video-info-text">
                    <strong>{{ card.title }}</strong>
                    <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
                  </div>
                </div>
              </article>
            </section>

            <section v-if="!loading && homeShortsRows.length" class="home-shorts-block">
              <div class="home-shorts-head">
                <h3><i class="ri-video-line"></i> Shorts</h3>
              </div>
              <div
                ref="homeShortsStripRef"
                class="home-shorts-strip"
                @wheel.prevent="onHomeShortsStripWheel"
                @scroll="onHomeShortsStripScroll"
              >
                <article
                  v-for="(card, index) in homeShortsRows"
                  :key="`home-short-${card.id}-${index}`"
                  class="home-short-card"
                  @click="onHomeShortCardClick(card, index)"
                  @mouseenter="onCardHoverEnter(card, 'home-shorts')"
                  @mouseleave="onCardHoverLeave(card, 'home-shorts')"
                >
                  <div class="home-short-media">
                    <video
                      v-if="isCardPreviewPlaying(card, 'home-shorts')"
                      class="home-short-cover home-short-cover--video video-cover--preview"
                      :src="activePreviewVideoUrl"
                      muted
                      autoplay
                      loop
                      playsinline
                      preload="metadata"
                    ></video>
                    <img v-else class="home-short-cover" :src="cardCoverSrc(card, index)" :alt="card.title" />
                  </div>
                  <div class="home-short-card-footer">
                    <div class="home-short-card-main">
                      <p class="home-short-title">{{ card.title }}</p>
                      <span class="home-short-author">{{ cardAuthorLabel(card) }}</span>
                    </div>
                    <div class="home-short-stats" aria-label="播放与点赞">
                      <span class="home-short-stat" title="播放量">
                        <i class="ri-play-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.playCount) }}
                      </span>
                      <span class="home-short-stat" title="点赞">
                        <i class="ri-heart-line" aria-hidden="true"></i>{{ formatHomeShortStat(card.likeCount) }}
                      </span>
                    </div>
                  </div>
                </article>
                <div v-if="homeShortsLoadingMore" class="home-shorts-loading-more">加载中…</div>
              </div>
            </section>

            <section class="card-grid">
              <article
                v-if="!loading"
                v-for="(card, index) in homeLongTailCards"
                :key="`home-long-tail-${card.id}`"
                class="video-card"
                :class="{ 'video-card--playable': videoCardPlayable(card) }"
                @click="onVideoCardClick(card)"
                @mouseenter="onCardHoverEnter(card, 'home-tail')"
                @mouseleave="onCardHoverLeave(card, 'home-tail')"
              >
                <video
                  v-if="isCardPreviewPlaying(card, 'home-tail')"
                  class="video-cover video-cover--preview"
                  :src="activePreviewVideoUrl"
                  muted
                  autoplay
                  loop
                  playsinline
                  preload="metadata"
                ></video>
                <img v-else class="video-cover" :src="cardCoverSrc(card, index + homeLongHeadCards.length)" :alt="card.title" />
                <div class="video-info">
                  <img
                    class="video-card-author-avatar"
                    :src="cardAuthorAvatar(card, index + homeLongHeadCards.length)"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="onAuthorAvatarClick($event, card.authorId)"
                    @error="(e) => (e.target.src = profileAvatar)"
                  />
                  <div class="video-info-text">
                    <strong>{{ card.title }}</strong>
                    <span>{{ cardAuthorLabel(card) }} · {{ card.viewsText || "1.2万次观看" }}</span>
                  </div>
                </div>
              </article>
            </section>
            <section v-if="homeLongLoadingMore" class="card-grid card-grid--loading-more">
              <article v-for="n in 5" :key="`skeleton-home-more-${n}`" class="video-card video-card--skeleton">
                <div class="video-cover video-cover-loading"></div>
                <div class="video-info">
                  <div class="video-card-author-avatar"></div>
                  <div class="video-info-text">
                    <strong></strong>
                    <span></span>
                  </div>
                </div>
              </article>
            </section>
          </section>

          <section v-else class="card-grid">
            <article v-if="loading" v-for="n in 8" :key="`skeleton-${n}`" class="video-card video-card--skeleton">
              <div class="video-cover video-cover-loading"></div>
              <div class="video-info">
                <div class="video-card-author-avatar"></div>
                <div class="video-info-text">
                  <strong></strong>
                  <span></span>
                </div>
              </div>
            </article>

            <article
              v-if="!loading"
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
                  role="button"
                  title="查看作者主页"
                  @click.stop="onAuthorAvatarClick($event, card.authorId)"
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
                  @loadedmetadata="() => { applyDetailPlaybackResumeIfNeeded(); void tryDetailVideoAutoplay(); }"
                  @canplaythrough="() => void tryDetailVideoAutoplay()"
                  @pause="onDetailVideoPause"
                  @play="onDetailVideoPlay"
                  @timeupdate="onDetailVideoTimeUpdate"
                  @seeked="onDetailVideoSeeked"
                  @ended="onDetailVideoEnded"
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
                      role="button"
                      title="查看作者主页"
                      @click="onAuthorAvatarClick($event, videoDetail.authorId)"
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
                  <button type="button" class="video-detail-action-pill" @click="onDetailLikeClick">
                    <i class="ri-thumb-up-line"></i> {{ videoDetail.likeCount ?? 0 }}
                  </button>
                  <button type="button" class="video-detail-action-pill" @click="onDetailDislikeClick">
                    <i class="ri-thumb-down-line"></i> 不感兴趣
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
        <form class="upload-modal" @submit.prevent="submitUploadVideo" @click.stop>
          <div class="upload-modal-head">
            <h3>上传视频</h3>
            <button
              type="button"
              class="upload-modal-close"
              aria-label="关闭"
              :disabled="uploadSubmitting"
              @click="closeUploadModal"
            >
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
            <button type="button" class="upload-modal-select" :disabled="uploadSubmitting" @click="triggerUploadPick">
              选择文件
            </button>
            <p v-if="uploadPickedFile" class="upload-picked-file">已选：{{ uploadPickedFile.name }}</p>
            <p v-if="uploadPickedDuration" class="upload-progress-text">时长约 {{ uploadPickedDuration }} 秒</p>
            <p v-if="uploadSubmitting" class="upload-progress-text">上传进度：{{ uploadProgress }}%</p>
          </div>

          <div class="upload-meta-grid">
            <label class="upload-field">
              <span>标题</span>
              <input v-model="uploadForm.title" type="text" maxlength="120" required placeholder="请输入视频标题" />
            </label>
            <label class="upload-field">
              <span>分类</span>
              <select v-model="uploadForm.category">
                <option v-for="c in uploadCategoryOptions" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
            </label>
            <label class="upload-field upload-field--full">
              <span>简介</span>
              <textarea v-model="uploadForm.description" rows="3" maxlength="500" placeholder="请输入视频简介（可选）"></textarea>
            </label>
            <label class="upload-field">
              <span>可见性</span>
              <select v-model="uploadForm.visibility">
                <option v-for="v in uploadVisibilityOptions" :key="v.value" :value="v.value">{{ v.label }}</option>
              </select>
            </label>
            <label class="upload-field">
              <span>标签</span>
              <div class="upload-tags-box">
                <div v-if="uploadTagList.length" class="upload-tags-list">
                  <button
                    v-for="tag in uploadTagList"
                    :key="tag"
                    type="button"
                    class="upload-tag-chip"
                    :disabled="uploadSubmitting"
                    @click="removeUploadTag(tag)"
                  >
                    {{ tag }} <i class="ri-close-line"></i>
                  </button>
                </div>
                <div v-if="uploadTagEditorOpen" class="upload-tag-editor">
                  <input
                    v-model="uploadTagDraft"
                    type="text"
                    maxlength="20"
                    placeholder="输入一个标签后确认"
                    :disabled="uploadSubmitting"
                    @keyup.enter.prevent="appendUploadTag"
                  />
                  <button type="button" class="upload-tag-btn" :disabled="uploadSubmitting" @click="appendUploadTag">确认</button>
                </div>
                <button
                  v-else
                  type="button"
                  class="upload-tag-btn"
                  :disabled="uploadSubmitting"
                  @click="openUploadTagEditor"
                >
                  + 添加标签
                </button>
              </div>
            </label>
            <label class="upload-field upload-field--full upload-switch">
              <input v-model="uploadForm.allowComment" type="checkbox" :true-value="1" :false-value="0" />
              <span>允许评论</span>
            </label>
          </div>

          <p v-if="uploadMsg" class="upload-modal-msg">{{ uploadMsg }}</p>
          <p class="upload-modal-foot">
            上传即表示你同意平台服务条款与社区准则。请勿侵犯他人版权或隐私。
          </p>
          <div class="upload-modal-actions">
            <button type="button" class="upload-action upload-action--ghost" :disabled="uploadSubmitting" @click="closeUploadModal">
              取消
            </button>
            <button type="submit" class="upload-action upload-action--primary" :disabled="uploadSubmitting">
              {{ uploadSubmitting ? "发布中…" : "上传并发布" }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
