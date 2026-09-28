<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useRoute, useRouter } from "vue-router";
import { menuKeyForPath, menuPathFor } from "./router";
import { useAuthStore } from "./stores/authStore";
import { useSearchStore, registerSearchExpandHooks, registerSearchExpandRefs, SEARCH_PAGE_SIZE, SEARCH_UI } from "./stores/searchStore";
import { useHistoryStore } from "./stores/historyStore";
import { useLikedStore, LIKED_PAGE_SIZE } from "./stores/likedStore";
import { useHomeStore, registerHomeHooks, homeSortOptions, HOME_LONG_HEAD_COUNT_DESKTOP, HOME_LONG_HEAD_COUNT_MOBILE } from "./stores/homeStore";
import { useShortsStore } from "./stores/shortsStore";
import { useProfileStore, registerProfileHooks } from "./stores/profileStore";
import { useDetailStore, registerDetailHooks } from "./stores/detailStore";
import { useCommentPanelStore } from "./stores/commentPanelStore";
import { useCommentStore } from "./stores/commentStore";
import { useFollowStore } from "./stores/followStore";
import { useBehaviorStore } from "./stores/behaviorStore";
import { useStudioStore, registerStudioHooks } from "./stores/studioStore";
import { useHoverPreviewStore, cardPreviewKey } from "./stores/hoverPreviewStore";
import { registerShortsFeedPlayerRefs, registerShortsHooks } from "./stores/shortsStore";
import { registerLikedHooks } from "./stores/likedStore";
import {
  resolveVideoUrl,
  videoCardPlayable,
  cardAuthorAvatar,
  cardCoverSrc,
  formatHomeShortStat,
  formatWorkLikeText,
  isLongVideoItem,
  parseSearchTagsArray,
  formatSearchRelTime,
  formatShortsTime,
} from "./utils/videoCard.js";
import {
  formatCommentTime as formatDetailCommentTime,
  insertGlyphIntoCommentDraft,
  isBlockedComment,
} from "./utils/comment.js";
import { getApiBaseUrl, getAuthExpiredEventName, getToken } from "./api/http";
import { reconnectNotifyWebSocketIfNeeded, startNotifyWebSocket, stopNotifyWebSocket } from "./utils/notifyWebSocket";
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
  fetchVideoListPage,
  fetchOtherUserVideos,
  fetchShortsFeedPage,
  fetchFollowVideoFeedPage,
  fetchShortHistoryPage,
  fetchLongHistoryPage,
  fetchVideoDetail,
  normalizeVideoDetail,
  publishVideo,
  mergePicturesToVideo,
  getMergePictureJobStatus,
  reportShortHistoryBatch,
  deleteMyVideo,
} from "./api/videoApi";
import {
  fetchSearchHot,
  fetchSearchPrompt,
  fetchSearchTag,
  fetchSearchUser,
  fetchSearchVideo,
} from "./api/searchApi";
import { STORAGE_BIZ_WORK } from "./api/otherApi";
import { uploadFileWithPresign } from "./utils/presignUpload.js";
import { VIDEO_CATEGORY_CHIPS } from "./constants/videoCategories";
import {
  likeVideo as likeVideoApi,
  likeComment as likeCommentApi,
  cancelLikeComment as cancelLikeCommentApi,
  cancelLikeVideo as cancelLikeVideoApi,
  fetchLikedVideosPage,
} from "./api/likeApi";
import { followAuthor, unfollowAuthor } from "./api/followApi";
import {
  fetchNotificationPage,
  markNotificationRead,
  deleteNotification,
  fetchUnreadNotificationCount,
  NOTIFICATION_TYPE_LABELS,
} from "./api/notificationApi";
import {
  sanitizeImageUrl,
  stripUserIdLikeName,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
  DEFAULT_PROFILE_BG_URL as profileBanner,
} from "./utils/userProfile";
import { parseDigitSnowflakeId, safeSnowflakeId } from "./utils/snowflakeId.js";
/** 短视频无接口数据时的本地 fallback（static 目录下四个 mp4） */
import appIco from "../static/狗.png?url";
const MusicView = defineAsyncComponent(() => import("./views/MusicView.vue"));
const SearchView = defineAsyncComponent(() => import("./views/SearchView.vue"));
const HistoryView = defineAsyncComponent(() => import("./views/HistoryView.vue"));
const ShortsView = defineAsyncComponent(() => import("./views/ShortsView.vue"));
const HomeView = defineAsyncComponent(() => import("./views/HomeView.vue"));
const VideoDetailView = defineAsyncComponent(() => import("./views/VideoDetailView.vue"));
const LikedView = defineAsyncComponent(() => import("./views/LikedView.vue"));
import MusicPlayerAudioHost from "./components/MusicPlayerAudioHost.vue";
import MusicMiniPlayer from "./components/MusicMiniPlayer.vue";
const ChatView = defineAsyncComponent(() => import("./views/ChatView.vue"));
const StudioView = defineAsyncComponent(() => import("./views/StudioView.vue"));
const ProfileCenterView = defineAsyncComponent(() => import("./views/ProfileCenterView.vue"));
import ChatFloatingRail from "./components/chat/ChatFloatingRail.vue";
import ChatEmojiPicker from "./components/chat/ChatEmojiPicker.vue";
import ChatForwardVideoModal from "./components/chat/ChatForwardVideoModal.vue";
import CommentReportModal from "./components/CommentReportModal.vue";
import SlideshowMusicPicker from "./components/SlideshowMusicPicker.vue";
import LoginLoadingCubes from "./components/LoginLoadingCubes.vue";
import { musicLyricsFullscreen } from "./stores/musicPlayerStore.js";
import { registerGlobalToast } from "./utils/toast.js";
import { playNotificationSound } from "./utils/uiSounds.js";
import { registerChatNavigator, navigateToChat } from "./utils/chatNavigator.js";
import {
  setChatUserContext,
  resetChatWhenLogout,
  tryIngestChatWebSocket,
  initChatWhenAuthed,
  openConversation as openChatConversation,
  startSingleChat,
  getChatSelfUserId,
  chatActiveConversationId,
  chatTotalUnread,
  refreshConversationList,
} from "./stores/chatStore.js";

const primaryMenus = [
  { key: "home", label: "首页", icon: "ri-home-4-line" },
  { key: "shorts", label: "短视频", icon: "ri-video-line" },
  { key: "subscribed", label: "关注", icon: "ri-star-line" },
  { key: "history", label: "历史播放记录", icon: "ri-history-line" },
  { key: "liked", label: "赞过的视频", icon: "ri-thumb-up-line" },
];

const exploreMenus = [
  { key: "music", label: "音乐", icon: "ri-music-2-line" },
  { key: "chat", label: "聊天", icon: "ri-chat-3-line" },
];

const activeMenuKey = ref("home");
const activeMenuLabel = ref("首页");
const route = useRoute();
const router = useRouter();
const pageHint = ref(`欢迎来到 THYME。请选择左侧模块查看内容。API 基础地址：${getApiBaseUrl()}`);
/** 搜索域状态在 pinia searchStore，同名解构保持既有引用零改动；拆 SearchView 时随之搬出 */
const searchStore = useSearchStore();
// 列表加载后由 store 通知 App 挂自动播放观察器（依赖本组件的 DOM 引用）
searchStore.registerLoadedHook(setupSearchVideoAutoplay);

const {
  searchKeyword,
  searchActive,
  searchTab,
  searchSubmittedKeyword,
  searchInputFocused,
  searchDropdownOpen,
  searchPromptRows,
  searchHotRows,
  searchPromptLoading,
  searchHotLoading,
  searchVideoRows,
  searchVideoPageNo,
  searchVideoPages,
  searchVideoLoading,
  searchVideoLoadingMore,
  searchVideoSort,
  searchUserRows,
  searchUserPageNo,
  searchUserPages,
  searchUserLoading,
  searchUserLoadingMore,
  searchTagRows,
  searchTagPageNo,
  searchTagPages,
  searchTagLoading,
  searchTagLoadingMore,
  searchAllUserStrip,
  searchResultError,
  searchExpandScrubMax,
  searchExpandScrubDisplayValue,
  searchExpandDurationReady,
  searchExpandLikedDisplay,
  searchExpandIsSelf,
  searchExpandIsFollowed,
  searchExpandOpen,
  searchExpandCard,
  searchExpandIndex,
  searchFeedProgressMap,
  searchFeedLikeBusyId,
  searchFeedPausedMap,
  searchFeedListPrefs,
  searchFeedMainVideoById,
  searchFeedMediaWrapById,
  searchExpandCurrentTime,
  searchExpandDuration,
  searchExpandPaused,
  searchExpandMuted,
  searchExpandVolume,
  searchExpandIsPortrait,
} = storeToRefs(searchStore);
const searchInputRef = ref(null);
const searchFeedScrollRef = ref(null);
const contentScrollRef = ref(null);
const searchExpandPlayerRef = ref(null);
const searchExpandBlurRef = ref(null);
const searchExpandStageRef = ref(null);

function dedupeStringsMax10(arr) {
  const seen = new Set();
  const out = [];
  for (const s of arr) {
    const x = String(s).trim();
    if (!x || seen.has(x)) continue;
    seen.add(x);
    out.push(x);
    if (out.length >= 10) break;
  }
  return out;
}

function highlightSuggestParts(line) {
  const kw = searchKeyword.value.trim();
  const t = String(line);
  if (!kw) return [{ t, hl: false }];
  const tL = t.toLowerCase();
  const kL = kw.toLowerCase();
  if (tL.startsWith(kL)) {
    return [
      { t: t.slice(0, kw.length), hl: true },
      { t: t.slice(kw.length), hl: false },
    ];
  }
  return [{ t, hl: false }];
}

const searchDropdownVisible = computed(() => {
  if (!searchInputFocused.value || !searchDropdownOpen.value) return false;
  return (
    searchPromptLoading.value ||
    searchPromptRows.value.length > 0 ||
    searchHotLoading.value ||
    searchHotRows.value.length > 0
  );
});

function teardownSearchVideoObserver() {
  try {
    searchVideoObserver?.disconnect();
  } catch {
    /* ignore */
  }
  searchVideoObserver = null;
}

function setupSearchVideoAutoplay() {
  teardownSearchVideoObserver();
  const root = contentScrollRef.value;
  searchVideoObserver = new IntersectionObserver(
    (entries) => {
      let best = null;
      for (const en of entries) {
        if (!en.isIntersecting) {
          en.target.querySelectorAll?.(".search-feed-video")?.forEach?.((v) => {
            try {
              v.pause();
            } catch {
              /* ignore */
            }
          });
          continue;
        }
        if (!best || en.intersectionRatio > best.intersectionRatio) {
          best = en;
        }
      }
      if (!best?.target) {
        return;
      }
      root?.querySelectorAll?.("[data-search-v-slide]")?.forEach?.((slide) => {
        const vids = slide.querySelectorAll(".search-feed-video");
        if (slide === best.target) {
          vids.forEach((v) => v.play().catch(() => {}));
        } else {
          vids.forEach((v) => {
            try {
              v.pause();
            } catch {
              /* ignore */
            }
          });
        }
      });
    },
    { root: root || null, rootMargin: "-28% 0px -28% 0px", threshold: [0, 0.35] }
  );
  nextTick(() => {
    const r = searchFeedScrollRef.value;
    if (!r) return;
    r.querySelectorAll("[data-search-v-slide]").forEach((el) => searchVideoObserver?.observe(el));
  });
}

function loadSearchHotIfNeeded() {
  return searchStore.loadHotIfNeeded();
}

function loadSearchVideoPage({ reset }) {
  return searchStore.loadVideoPage({ reset });
}

function loadSearchUserStrip({ reset }) {
  return searchStore.loadUserStrip({ reset });
}

function loadSearchUserPage({ reset }) {
  return searchStore.loadUserPage({ reset });
}

function loadSearchTagPage({ reset }) {
  return searchStore.loadTagPage({ reset });
}

function loadSearchTabResults({ reset }) {
  return searchStore.loadTabResults({ reset });
}

function selectSearchTab(key) {
  searchStore.selectTab(key);
}

function onSearchInputFocus() {
  searchInputFocused.value = true;
  searchDropdownOpen.value = true;
  if (searchSuggestBlurTimer) {
    clearTimeout(searchSuggestBlurTimer);
    searchSuggestBlurTimer = null;
  }
  void loadSearchHotIfNeeded();
}

function onSearchInputBlur() {
  searchSuggestBlurTimer = setTimeout(() => {
    searchInputFocused.value = false;
    searchDropdownOpen.value = false;
  }, 160);
}

function selectSearchSuggestion(text) {
  const t = String(text).trim();
  if (!t) return;
  searchKeyword.value = t;
  searchDropdownOpen.value = false;
  void onSearch();
}

function clearSearchKeyword() {
  searchKeyword.value = "";
  nextTick(() => {
    searchInputRef.value?.focus?.();
  });
}

watch(
  () => searchKeyword.value,
  (q) => {
    if (searchPromptDebounceTimer) {
      clearTimeout(searchPromptDebounceTimer);
      searchPromptDebounceTimer = null;
    }
    if (!searchInputFocused.value) return;
    const kw = String(q).trim();
    if (!kw) {
      searchPromptRows.value = [];
      return;
    }
    searchPromptDebounceTimer = setTimeout(async () => {
      searchPromptLoading.value = true;
      try {
        const raw = await fetchSearchPrompt(kw);
        searchPromptRows.value = dedupeStringsMax10(Array.isArray(raw) ? raw : []);
      } catch {
        pushToast(SEARCH_UI.promptError, "error");
        searchPromptRows.value = [];
      } finally {
        searchPromptLoading.value = false;
      }
    }, 220);
  }
);

watch(searchVideoSort, () => {
  if (!searchActive.value) return;
  if (searchTab.value === "all" || searchTab.value === "video") {
    void loadSearchVideoPage({ reset: true });
  }
});

const searchVerticalLoading = computed(() => {
  if (searchTab.value === "tag") return searchTagLoading.value;
  return searchVideoLoading.value;
});

const searchVerticalLoadingMore = computed(() => {
  if (searchTab.value === "tag") return searchTagLoadingMore.value;
  return searchVideoLoadingMore.value;
});

/** 解析为可点击标签（不含 # 前缀，便于搜索） */
function onSearchTagNavigate(tag) {
  const t = String(tag ?? "")
    .replace(/^#/, "")
    .trim();
  if (!t) return;
  if (searchExpandOpen.value) {
    closeSearchExpand();
  }
  closeShortsCommentPanel();
  uploadModalOpen.value = false;
  showStudio.value = false;
  if (shortPlayerOpen.value) {
    closeShortPlayer();
  }
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
  if (showVideoDetail.value) {
    closeVideoDetail();
  }
  searchKeyword.value = t;
  searchSubmittedKeyword.value = t;
  searchActive.value = true;
  searchTab.value = "tag";
  searchResultError.value = "";
  activeMenuLabel.value = `标签：${t}`;
  pageHint.value = `按标签搜索：「${t}」`;
  void loadSearchTabResults({ reset: true });
}

function onSearchFeedForwardClick(card, ev) {
  ev?.stopPropagation?.();
  openForwardVideoModal(card);
}

function onSearchFeedMainVideoClick(card, index) {
  openSearchExpand(card, index);
}

async function onSearchFeedLikeClick(card, ev) {
  ev?.stopPropagation?.();
  if (!card?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再点赞", "info");
    return;
  }
  const vid = parseVideoIdForApi(card.id);
  if (vid == null) return;
  const busyKey = String(card.id);
  if (searchFeedLikeBusyId.value === busyKey) return;
  searchFeedLikeBusyId.value = busyKey;
  try {
    const wasLiked = Boolean(card.isLike);
    if (wasLiked) {
      await cancelLikeVideoApi(vid);
      patchFeedVideoById(card.id, { isLike: false, likeCount: Math.max(0, Number(card.likeCount ?? 0) - 1) });
    } else {
      await likeVideoApi(vid);
      patchFeedVideoById(card.id, {
        isLike: true,
        likeCount: Number(card.likeCount ?? 0) + 1,
      });
    }
    const raw = await fetchVideoDetail(vid);
    const merged = normalizeVideoDetail(raw);
    if (merged && merged.likeCount != null) {
      patchFeedVideoById(card.id, { likeCount: merged.likeCount });
    }
    void reportBehaviorSilently(card.id, "LIKE", 0);
  } catch (error) {
    pushToast(error.message || "点赞失败", "error");
  } finally {
    searchFeedLikeBusyId.value = null;
  }
}

const sidebarCollapsed = ref(false);
const exploreExpanded = ref(true);
const { loading, cards, homeShortsRows, homeShortsPageNo, homeShortsPages, homeShortsLoadingMore, homeLongPageNo, homeLongPages, homeLongLoadingMore, homeCategoryKey, homeSortKey } = storeToRefs(useHomeStore());
const viewportWidth = ref(typeof window !== "undefined" ? window.innerWidth : 1440);
const homeShortsStripRef = ref(null);
const homeLongHasMore = computed(() => homeLongPageNo.value < (homeLongPages.value || 0));
const homeLongLoadLock = ref(false);
/** 离开首页或打开个人中心前保存，回到首页时可无请求恢复，减少列表跳动 */
const homeFeedSnapshot = ref(null);
const authLoading = ref(false);
/** 登录成功后全屏 LOADING 立方体动画，与首屏数据加载并行 */
const showLoginLoadingOverlay = ref(false);
/** 登录立方体动画播完（最后一格 llc-translate-z 结束）时 resolve */
let loginCubesAnimResolve = null;
function onLoginCubesAnimFinished() {
  loginCubesAnimResolve?.();
  loginCubesAnimResolve = null;
}
const authMessage = ref("");
const activeAuthTab = ref("login");
const authStore = useAuthStore();
const { token, userId: currentUserId, selfProfile: selfUserProfile } = storeToRefs(authStore);
/** 创作中心域状态在 pinia studioStore */
const hoverPreview = useHoverPreviewStore();
const {
  hoveringId: hoveringPreviewCardId,
  activeId: activePreviewCardId,
  videoUrl: activePreviewVideoUrl,
} = storeToRefs(hoverPreview);

function stopCardHoverPreview() {
  hoverPreview.stop();
}
function onCardHoverEnter(card, listKey = "") {
  hoverPreview.enter(card, listKey);
}
function onCardHoverLeave(card, listKey = "") {
  hoverPreview.leave(card, listKey);
}
function isCardPreviewPlaying(card, listKey = "") {
  return hoverPreview.activeId === cardPreviewKey(card, listKey);
}

const studioStore = useStudioStore();
const {
  showStudio: studioShow,
  studioSideNav,
  studioContentTab,
  studioPageNo,
  studioPageSize,
  studioRows,
  studioTotal,
  studioPages,
  studioLoading,
  studioMsg,
  studioRowMenuOpenId,
} = storeToRefs(studioStore);
let activeMenuSwitchSeq = 0;

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
/** 个人主页域状态在 pinia profileStore；同名解构保持既有引用零改动 */
const profileStore = useProfileStore();
const {
  open: showProfileCenter,
  targetUserId: profileTargetUserId,
  user: profileUser,
  targetFollowed: profileTargetFollowed,
  worksRows: profileWorksRows,
  /** 关注列表同时供「关注」页侧栏与粉丝/关注弹窗使用 */
  followingRows,
  followingLoading,
  followingHasMore,
  mediaPreviewOpen,
  isViewingSelf,
} = storeToRefs(profileStore);
const loadFollowingNextPage = () => profileStore.loadRelationNextPage("following");

/** 主页入口：供顶栏、头像与桥接消费；打开动作与状态都在 profileStore */
const openProfile = () => profileStore.openSelf();
const openOtherUserProfile = (userId) => profileStore.openOther(userId);
const onAuthorAvatarClick = (event, authorId) => {
  event?.stopPropagation?.();
  profileStore.openByAuthorId(authorId);
};
const onSidebarOpenMyProfile = () => {
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  profileStore.openSelf();
};
/** 创作中心（全屏主区，模拟 YouTube Studio） */
const showStudio = studioShow;
/** 进入创作中心时 pushState，便于浏览器后退与「返回」同步 */
const studioHistoryPushed = ref(false);
/** 顶栏通知下拉 */
const notifyPanelOpen = ref(false);
const notifyUnreadCount = ref(0);
const notifyList = ref([]);
const notifyLoading = ref(false);
const notifyPageNo = ref(1);
const notifyPages = ref(0);
const notifyTotal = ref(0);
const notifyErrorMsg = ref("");
const NOTIFY_PAGE_SIZE = 15;
const uploadModalOpen = ref(false);
/** 上传弹窗：整段视频 或 多图幻灯片（对象存储合成后再 publishVideo） */
const uploadMode = ref("video");
const MAX_SLIDESHOW_IMAGES = 50;
const MERGE_POLL_INTERVAL_MS = 2000;
const MERGE_POLL_MAX_ATTEMPTS = 200;
const uploadSlideshowImages = ref([]);
const uploadSlideshowAudioFile = ref(null);
const uploadFileInputRef = ref(null);
const uploadSlideshowImagesInputRef = ref(null);
const uploadSlideshowAudioInputRef = ref(null);
const uploadMusicPickerOpen = ref(false);
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
/** 视频详情域状态在 pinia detailStore；App 只需判断「是否在详情」与读卡片数据 */
const detailStore = useDetailStore();
const commentStore = useCommentStore();
const {
  open: showVideoDetail,
  video: videoDetail,
  loading: detailLoading,
} = storeToRefs(detailStore);

/** 详情域动作：App 的菜单/弹窗/地址栏入口仍要调，保留同名薄封装 */
const openVideoDetail = (card, opts) => detailStore.openDetail(card, opts);
const closeVideoDetail = () => detailStore.closeDetail();
const restoreVideoDetailFromUrl = (vidRaw) => detailStore.restoreFromUrl(vidRaw);
const clearVideoDetailFromAddressBar = () => detailStore.clearFromAddressBar();
const hideDetailBufferingOverlay = () => detailStore.hideBufferingOverlay();
/** 短视频播放器域状态在 pinia shortsStore（含历史/赞过/主页/关注共用的全屏队列），同名解构零引用改动 */
const shortsStore = useShortsStore();
const {
  feedScope,
  profileFeedQueue,
  likedFeedQueue,
  followFeedQueue,
  followSubscribedFilterAuthorId,
  followAuthorFeedQueue,
  followAuthorVideoPageNo,
  followAuthorVideoHasMore,
  followAuthorLoadingMore,
  followVideoPageNo,
  followVideoHasMore,
  followVideoLoadingMore,
  profileFeedOpen,
  /** 队列推导出的当前条与「当前评论目标」，播放器与评论侧栏共用 */
  unifiedFeedList,
  shortsFeedCurrent,
  activeCommentVideo,
  activeCommentVideoAllowsComment,
  /** 当前条的展示值：模板与桥直接消费，推导逻辑在 store */
  shortsFeedVideoSrc,
  shortsFeedLikedDisplay,
  shortsScrubMax,
  shortsScrubDisplayValue,
  shortsDurationReady,
  shortsFeedAuthorName,
  shortsFeedMetaTimeText,
  shortsFeedTitleText,
  shortsFeedDescText,
  shortsFeedAuthorAvatar,
  shortsIsFollowedCurrent,
  shortsIsSelfCurrent,
  shortsFeedIndex,
  shortsFeedIsPortrait,
  shortsFeedDuration,
  shortsFeedCurrentTime,
  shortsFeedVolume,
  shortsFeedMuted,
  shortsFeedPaused,
  shortsFeedAutoplayPending,
  shortsFeedVideoBuffering,
  shortsPanelTopComments,
  shortsPanelCommentsLoading,
  shortsPanelCommentDraft,
  shortsPanelCommentSubmitting,
  shortsPanelReplyTarget,
  shortsPanelThreadByRootId,
  shortsPanelExpandedRoots,
  shortsLikeBusy,
  selfVideoActionOpen,
  shortsPlaybackAccSec,
  shortsLastTimeSec,
  shortsAccVideoId,
} = storeToRefs(shortsStore);
/** 评论面板开关在 commentPanelStore：短视频侧栏 / 搜索放大层 / 搜索列表三个入口互斥 */
const {
  shortsCommentPanelOpen,
  searchExpandCommentOpen,
  searchListCommentOpen,
  searchListCommentCard,
} = storeToRefs(useCommentPanelStore());
/** 播放缓冲遮罩：实现已移入 shortsStore，这里保留同名薄封装供桥与生命周期调用 */
const scheduleShortsBufferingOverlay = () => shortsStore.scheduleShortsBufferingOverlay();
const hideShortsBufferingOverlay = () => shortsStore.hideShortsBufferingOverlay();

/**
 * 短视频播放控制：实现已移入 shortsStore（DOM ref 仍由本组件持有并注册），
 * 这里保留同名薄封装，使桥、生命周期与详情/主页等域调用点零改动。
 */
const shortsFeedPrev = () => shortsStore.shortsFeedPrev();
const shortsFeedNext = () => shortsStore.shortsFeedNext();
const onShortsFeedFgLoadStart = () => shortsStore.onShortsFeedFgLoadStart();
const onShortsFeedFgCanPlay = () => shortsStore.onShortsFeedFgCanPlay();
const onShortsFeedFgMediaError = () => shortsStore.onShortsFeedFgMediaError();
const onShortsFeedFgLoadedMetadata = (e) => shortsStore.onShortsFeedFgLoadedMetadata(e);
const onShortsFeedFgTimeUpdate = (e) => shortsStore.onShortsFeedFgTimeUpdate(e);
const onShortsFeedFgPlay = () => shortsStore.onShortsFeedFgPlay();
const onShortsFeedFgPause = () => shortsStore.onShortsFeedFgPause();
const onShortsFeedFgDurationChange = (e) => shortsStore.onShortsFeedFgDurationChange(e);
const onShortsFeedFgSeeked = (e) => shortsStore.onShortsFeedFgSeeked(e);
const onShortsFeedFgEnded = () => shortsStore.onShortsFeedFgEnded();
const onShortsScrubInput = (e) => shortsStore.onShortsScrubInput(e);
const onShortsVolumeInput = (e) => shortsStore.onShortsVolumeInput(e);
const shortsToggleMute = () => shortsStore.shortsToggleMute();
const shortsTogglePlay = () => shortsStore.shortsTogglePlay();
const shortsRequestPip = () => shortsStore.shortsRequestPip();
const shortsRequestFullscreen = () => shortsStore.shortsRequestFullscreen();
const onShortsFeedKeydown = (ev) => shortsStore.onShortsFeedKeydown(ev);
const onShortsFeedWheel = (e) => shortsStore.onShortsFeedWheel(e);
const onShortsFeedTouchStart = (ev) => shortsStore.onShortsFeedTouchStart(ev);
const onShortsFeedTouchEnd = (ev) => shortsStore.onShortsFeedTouchEnd(ev);
const pauseShortsFeedVideos = (opts) => shortsStore.pauseShortsFeedVideos(opts);
const restoreShortsFeedForegroundMuteFromState = () =>
  shortsStore.restoreShortsFeedForegroundMuteFromState();
const resetShortsFeedProgressUiForSwitch = () => shortsStore.resetShortsFeedProgressUiForSwitch();
const isLongShortsFeedVideo = (item) => shortsStore.isLongShortsFeedVideo(item);
const flushShortsLongPlaybackResume = (reason) => shortsStore.flushShortsLongPlaybackResume(reason);

/** 短视频互动：点赞/不感兴趣/关注与自视频三点菜单的实现已移入 shortsStore */
const onShortsLikeClick = () => shortsStore.onShortsLikeClick();
const onShortsDislikeClick = () => shortsStore.onShortsDislikeClick();
const toggleShortsFollow = () => shortsStore.toggleShortsFollow();
const toggleSelfVideoActionMenu = () => shortsStore.toggleSelfVideoActionMenu();
const closeSelfVideoActionMenu = () => shortsStore.closeSelfVideoActionMenu();
const toggleCurrentSelfVideoVisibility = () => shortsStore.toggleCurrentSelfVideoVisibility();
const toggleCurrentSelfVideoAllowComment = () => shortsStore.toggleCurrentSelfVideoAllowComment();
/** 地址栏 ?v= 与 sessionStorage 恢复详情页（刷新不丢） */
const VIDEO_URL_PARAM = "v";

/** 短视频全屏播放（纵向视频两侧模糊填充） */
const shortPlayerOpen = ref(false);
const shortPlayerVideoSrc = ref("");
const shortPlayerTitle = ref("");
const shortPlayerIsPortrait = ref(false);
const shortFgVideoRef = ref(null);
const shortBgVideoRef = ref(null);
/** 短视频模块内嵌 feed（默认沉浸播放，非弹窗）：DOM 引用与播放令牌留在本地 */
const shortsFeedFgRef = ref(null);
const shortsFeedBgRef = ref(null);
const shortsFeedCenterRef = ref(null);
let shortsFeedPlayToken = 0;
const shortsPanelCommentInputRef = ref(null);
const {
  historyLongRows,
  historyLongLoading,
  historyLongPageNo,
  historyLongPages,
  historyLongExpanded,
  historyShortRows,
  historyShortLoading,
  historyShortPageNo,
  historyShortPages,
  historyShortExpanded,
} = storeToRefs(useHistoryStore());
const SHORTS_HISTORY_BATCH_SIZE = 10;
let shortsHistoryQueue = [];
const shortsHistoryQueueSet = new Set();
const shortsHistorySeenSet = new Set();
let shortsHistoryFlushInFlight = false;
/** 按作者 ID 覆盖关注状态（乐观更新后优先于接口返回的 isFollow），状态在 pinia followStore */
const { overrideByAuthorId: followStateOverrideByAuthorId } = storeToRefs(useFollowStore());
const behaviorStore = useBehaviorStore();
const reportBehaviorSilently = (videoIdLike, event, watchDurationMs = 0) =>
  behaviorStore.report(videoIdLike, event, watchDurationMs);
const UI_STATE_STORAGE_KEY = "myyoutube_ui_state_v1";

/** 仅音乐歌词「全屏」时隐藏右侧会话条；普通音乐页仍显示 */
const chatRailSuppress = computed(
  () =>
    searchExpandOpen.value ||
    profileFeedOpen.value ||
    shortPlayerOpen.value ||
    showVideoDetail.value ||
    musicLyricsFullscreen.value,
);

/** 赞过的视频列表（GET /like/{userId}/list），状态在 pinia likedStore */
const {
  likedRows,
  likedPageNo,
  likedPages,
  likedTotal,
  likedLoading,
  likedLoadingMore,
} = storeToRefs(useLikedStore());
const selfVideoDeleteConfirmOpen = ref(false);
/** 删除确认弹窗对应的视频 id（创作中心 / 短视频流共用） */
const pendingDeleteVideoId = ref(null);
/** 创作中心表格行「更多」菜单 */
const toasts = ref([]);
let toastSeed = 0;

/** 首页 Shorts 卡片：播放量 / 点赞展示 */

const likedDisplay = computed(() => {
  return likedRows.value.map((item) => ({
    id: item.id,
    title: item.title || "未命名视频",
    image: item.coverUrl || item.avatarUrl || profileBanner,
    likes: formatWorkLikeText(item),
    playCount: item.playCount,
    videoUrl: resolveVideoUrl(item),
    authorNickname: item.authorNickname || item.author || "",
    description: item.description || "",
    tags: item.tags || "",
    authorAvatarUrl: sanitizeImageUrl(item.authorAvatarUrl || item.authorAvatar || "") || profileAvatar,
    videoType: item.videoType,
    ...item,
  }));
});

const likedHasMore = computed(() => likedPageNo.value < (likedPages.value || 0));

const uploadTagList = computed(() =>
  String(uploadForm.value.tags || "")
    .split(",")
    .map((x) => x.trim())
    .filter((x) => x)
);

/** 幻灯片成片总时长（秒），与后端规则一致：每图约 2s，不足 6s 时按 6s 均摊 */
const slideshowBgmTargetSec = computed(() => {
  const n = uploadSlideshowImages.value.length;
  if (n <= 0) return 0;
  let perImage = 2;
  if (n * perImage < 6) perImage = 6 / n;
  return Math.round(perImage * n);
});

watch(slideshowBgmTargetSec, (sec, oldSec) => {
  if (oldSec === undefined || oldSec <= 0) return;
  if (sec === oldSec) return;
  if (uploadSlideshowAudioFile.value) {
    uploadSlideshowAudioFile.value = null;
    pushToast("图片张数已变更，请重新选择背景音乐", "info");
  }
});

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

function pauseAllDomVideos() {
  try {
    if (document.pictureInPictureElement) {
      document.exitPictureInPicture().catch(() => {});
    }
  } catch {
    /* ignore */
  }
  try {
    document.querySelectorAll("video").forEach((v) => {
      try {
        v.pause();
      } catch {
        /* ignore */
      }
    });
  } catch {
    /* ignore */
  }
}

/**
 * 从 WS 原始 JSON 文本中提取长整型字段，避免 JSON.parse 把雪花 id 变成 Number 丢精度。
 */
function extractWsJsonDigitString(rawText, field) {
  if (typeof rawText !== "string" || !field) return null;
  const esc = String(field).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  let m = rawText.match(new RegExp(`"${esc}"\\s*:\\s*(\\d+)`));
  if (m && m[1]) return m[1];
  m = rawText.match(new RegExp(`"${esc}"\\s*:\\s*"(\\d+)"`));
  return m && m[1] ? m[1] : null;
}

/** 通知主键 id（与后端 NotificationForJson.id 一致），从原文提取避免大整数精度问题 */
function pickNotifyWsNotificationId(rawText, parsed) {
  const fromText =
    extractWsJsonDigitString(rawText, "notificationId") ||
    extractWsJsonDigitString(rawText, "notification_id") ||
    extractWsJsonDigitString(rawText, "id");
  if (fromText && /^\d+$/.test(fromText)) return fromText;
  const o = parsed && typeof parsed === "object" ? parsed : {};
  const candidates = [o.notificationId, o.notification_id, o.id];
  for (const c of candidates) {
    if (c == null || c === "") continue;
    if (typeof c === "string") {
      const t = c.trim();
      if (/^\d+$/.test(t)) return t;
    }
    if (typeof c === "number" && Number.isFinite(c)) {
      const n = Math.trunc(c);
      if (Number.isSafeInteger(n)) return String(n);
    }
  }
  return null;
}

function pickNotifyWsVideoId(rawText, parsed) {
  const fromText =
    extractWsJsonDigitString(rawText, "videoId") ||
    extractWsJsonDigitString(rawText, "video_id");
  if (fromText && /^\d+$/.test(fromText)) return fromText;
  const o = parsed && typeof parsed === "object" ? parsed : {};
  const candidates = [o.videoId, o.video_id];
  for (const c of candidates) {
    if (c == null || c === "") continue;
    if (typeof c === "string") {
      const t = c.trim();
      if (/^\d+$/.test(t)) return t;
    }
    if (typeof c === "number" && Number.isFinite(c)) {
      const n = Math.trunc(c);
      if (Number.isSafeInteger(n)) return String(n);
    }
  }
  return null;
}

/** 聊天类 WS 通知：会话 id 多为 Mongo 字符串，不能用仅数字的 extractWsJsonDigitString */
function pickNotifyWsConversationId(rawText, parsed) {
  if (typeof rawText === "string") {
    const m = rawText.match(/"conversationId"\s*:\s*"([^"]*)"/);
    if (m && m[1] != null && String(m[1]).trim()) return String(m[1]).trim();
  }
  const o = parsed && typeof parsed === "object" ? parsed : {};
  const v = o.conversationId ?? o.conversation_id;
  if (v == null || v === "") return null;
  const s = String(v).trim();
  return s || null;
}

function pickNotifyWsContentFromObject(o) {
  if (!o || typeof o !== "object" || Array.isArray(o)) return "";
  const keys = ["content", "text", "message", "body", "commentContent", "comment_content", "preview", "snippet"];
  for (const k of keys) {
    if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
    const v = o[k];
    if (v == null) continue;
    if (typeof v === "string") {
      const t = v.trim();
      if (t) return t;
    }
    if (typeof v === "number" && Number.isFinite(v)) {
      const t = String(v).trim();
      if (t) return t;
    }
    if (typeof v === "boolean") {
      return v ? "true" : "";
    }
  }
  return "";
}

function pickNotifyWsContent(parsed) {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return "";
  let s = pickNotifyWsContentFromObject(parsed);
  if (s) return s;
  const nestKeys = ["data", "payload", "notification", "msg"];
  for (const nk of nestKeys) {
    const inner = parsed[nk];
    if (inner && typeof inner === "object" && !Array.isArray(inner)) {
      s = pickNotifyWsContentFromObject(inner);
      if (s) return s;
    }
  }
  return "";
}

function pickNotifyWsAvatarUrl(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const keys = [
    "senderAvatarUrl",
    "sender_avatar_url",
    "senderAvatar",
    "avatarUrl",
    "conversationAvatarUrl",
    "conversation_avatar_url",
  ];
  for (const k of keys) {
    const v = parsed[k];
    if (typeof v === "string" && v.trim()) return v.trim();
  }
  return "";
}

function pickNotifyWsNickname(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const keys = [
    "senderNickname",
    "sender_nickname",
    "nickname",
    "senderName",
    "conversationName",
    "conversation_name",
  ];
  for (const k of keys) {
    const v = parsed[k];
    if (typeof v === "string" && v.trim()) return v.trim();
  }
  return "";
}

function pickNotifyWsTypeCode(parsed) {
  if (!parsed || typeof parsed !== "object") return "";
  const v = parsed.type;
  if (typeof v === "string" && v.trim()) return v.trim();
  if (v && typeof v === "object" && typeof v.code === "string") return v.code.trim();
  if (v && typeof v === "object" && typeof v.name === "string") return v.name.trim();
  return "";
}

/** 与 chatStore previewFromSendWs 对齐：WebSocket 下发的 contentType 可能是枚举名或对象 */
function previewChatIncomingWsBody(o) {
  if (!o || typeof o !== "object") return "[消息]";
  let ct = o.contentType;
  if (ct && typeof ct === "object") {
    ct = ct.name ?? ct.code ?? ct;
  }
  ct = String(ct || "").toUpperCase();
  if (ct === "TEXT") return String(o.content || "").slice(0, 80);
  if (ct === "IMAGE") return "[图片]";
  if (ct === "FILE") return "[文件]";
  if (ct === "SYSTEM") return "[系统消息]";
  return "[消息]";
}

/** 他人发来新消息且当前未打开该会话时，与评论通知同款的实时毛玻璃 Toast */
function maybePushChatIncomingLiveToast(parsed) {
  // chatStore 的 self 依赖 setChatUserContext；其 watch 晚于 notify WS 时 getChatSelfUserId 会为空，用 currentUserId 兜底
  const selfId = String(getChatSelfUserId() ?? currentUserId.value ?? "").trim();
  const sender = String(parsed?.senderId ?? "").trim();
  if (!selfId || !sender || sender === selfId) return;
  const conv = String(parsed?.conversationId || "").trim();
  if (!conv) return;
  if (chatActiveConversationId.value === conv) return;
  const nick = pickNotifyWsNickname(parsed);
  const title = nick ? `${nick} · 新消息` : "新消息";
  const content = previewChatIncomingWsBody(parsed);
  const avatarUrl = typeof parsed?.avatarUrl === "string" ? parsed.avatarUrl.trim() : "";
  pushNotifyLiveToast({
    avatarUrl,
    title,
    content,
    videoId: null,
    notificationId: null,
    conversationId: conv,
  });
}

/** 实时通知（WebSocket）：毛玻璃卡片，含头像与正文，可点进视频 / 标记已读 / 打开聊天 */
function pushNotifyLiveToast({
  avatarUrl,
  title,
  content,
  videoId,
  notificationId,
  conversationId,
  notifyKind = null,
}) {
  const ttl = String(title || "通知").trim() || "通知";
  const body = String(content || "").trim();
  toastSeed += 1;
  const id = `toast-${Date.now()}-${toastSeed}`;
  const vidRaw = videoId != null && videoId !== "" ? String(videoId).trim() : null;
  const nidRaw =
    notificationId != null && notificationId !== "" ? String(notificationId).trim() : null;
  const convRaw = conversationId != null && conversationId !== "" ? String(conversationId).trim() : null;
  const next = [
    ...toasts.value,
    {
      id,
      type: "live-notify",
      notify: {
        avatarUrl: String(avatarUrl || "").trim(),
        title: ttl,
        content: body,
        videoId: vidRaw && /^\d+$/.test(String(vidRaw)) ? String(vidRaw) : null,
        notificationId: nidRaw && /^\d+$/.test(String(nidRaw)) ? String(nidRaw) : null,
        conversationId: convRaw || null,
        notifyKind: notifyKind || null,
      },
    },
  ];
  toasts.value = next.slice(-3);
  playNotificationSound();
  window.setTimeout(() => removeToast(id), 6500);
}

function removeToast(id) {
  toasts.value = toasts.value.filter((t) => t.id !== id);
}

async function onNotifyToastClick(t) {
  const n = t?.notify;
  if (!n) return;
  if (String(n.notifyKind || "") === "CHAT_NOTICE") {
    removeToast(t.id);
    pushToast(String(n.content || "该群聊已解散").trim() || "该群聊已解散", "info");
    return;
  }
  const hasConv = n.conversationId != null && String(n.conversationId).trim() !== "";
  const hasVid = n.videoId != null && n.videoId !== "";
  const hasNid = n.notificationId != null && n.notificationId !== "";
  if (!hasVid && !hasNid && !hasConv) return;

  if (hasConv) {
    removeToast(t.id);
    navigateToChat(String(n.conversationId).trim());
    return;
  }

  if (hasNid) {
    try {
      await markNotificationRead(n.notificationId);
      await refreshNotifyUnreadCount();
      if (notifyPanelOpen.value) {
        void loadNotifyListPage(true);
      }
      const nid = n.notificationId;
      notifyList.value = notifyList.value.map((r) =>
        String(r.id) === String(nid) ? { ...r, isRead: true } : r
      );
    } catch (e) {
      pushToast(e?.message || "标记已读失败", "error");
    }
  }

  if (hasVid) {
    const vid = parseVideoIdForApi(n.videoId);
    if (vid != null) {
      removeToast(t.id);
      await openVideoDetail({ id: vid });
      return;
    }
  }

  if (hasNid) {
    removeToast(t.id);
  }
}

/** 创作中心侧栏等：始终展示当前登录用户昵称 */
const selfDisplayNickname = computed(() => {
  const u = selfUserProfile.value;
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

/** 顶栏「我」头像：始终为当前登录用户，不随他人主页 profileUser 变化 */
const topbarAvatarSrc = computed(() => authStore.selfAvatarUrl);

function mapToFollowPlayableRows(list) {
  return (Array.isArray(list) ? list : [])
    .map((x) => {
      const url =
        resolveVideoUrl(x) || resolveVideoUrlForListItem(x.id, x) || String(x?.videoUrl || "").trim();
      return { ...x, videoUrl: url || x.videoUrl, videoType: x.videoType || "SHORT_VIDEO" };
    })
    .filter((x) => Boolean(String(resolveVideoUrl(x) || x.videoUrl || "").trim()));
}

let followFeedLoadSeq = 0;
let followAuthorLoadSeq = 0;

async function loadSubscribedFollowVideos({ reset = false } = {}) {
  if (!isAuthed.value) return;
  const seq = ++followFeedLoadSeq;
  if (followVideoLoadingMore.value && !reset) return;
  if (!reset && !followVideoHasMore.value) return;
  followVideoLoadingMore.value = true;
  try {
    const pageNo = reset ? 1 : followVideoPageNo.value;
    const pageSize = 12;
    const res = await fetchFollowVideoFeedPage({ pageNo, pageSize });
    if (seq !== followFeedLoadSeq) return;
    if (followSubscribedFilterAuthorId.value != null && followSubscribedFilterAuthorId.value !== "") {
      return;
    }
    const list = Array.isArray(res.list) ? res.list : [];
    const playable = mapToFollowPlayableRows(list);
    const pages = Number(res.pages || 0);
    if (reset) {
      followFeedQueue.value = playable;
      cards.value = list;
      followVideoPageNo.value = pageNo + 1;
    } else {
      const existing = new Set(followFeedQueue.value.map((x) => String(x.id)));
      let merged = followFeedQueue.value;
      for (const p of playable) {
        if (!existing.has(String(p.id))) {
          merged = [...merged, p];
          existing.add(String(p.id));
        }
      }
      followFeedQueue.value = merged;
      followVideoPageNo.value = pageNo + 1;
    }
    followVideoHasMore.value = list.length >= pageSize && (pages <= 0 || pageNo < pages);
    if (list.length && !playable.length) {
      pushToast("接口返回了视频但未解析到播放地址，请检查对象存储签名或 videoUrl 字段", "info");
    }
  } catch (e) {
    if (seq !== followFeedLoadSeq) return;
    if (followSubscribedFilterAuthorId.value != null && followSubscribedFilterAuthorId.value !== "") {
      return;
    }
    if (reset) {
      followFeedQueue.value = [];
      cards.value = [];
    }
    followVideoHasMore.value = false;
    pushToast(e?.message || "加载关注视频失败", "error");
  } finally {
    if (seq === followFeedLoadSeq) {
      followVideoLoadingMore.value = false;
    }
  }
}

async function loadSubscribedAuthorVideos(authorId, { reset = false } = {}) {
  const aid = authorId != null && authorId !== "" ? String(authorId) : "";
  if (!aid || !isAuthed.value) return;
  const seq = ++followAuthorLoadSeq;
  if (followAuthorLoadingMore.value && !reset) return;
  if (!reset && !followAuthorVideoHasMore.value) return;
  followAuthorLoadingMore.value = true;
  try {
    const pageNo = reset ? 1 : followAuthorVideoPageNo.value;
    const pageSize = 12;
    const res = await fetchOtherUserVideos(aid, { pageNo, pageSize });
    if (seq !== followAuthorLoadSeq) return;
    if (String(followSubscribedFilterAuthorId.value || "") !== aid) return;
    const list = Array.isArray(res.list) ? res.list : [];
    const playable = mapToFollowPlayableRows(list);
    const pages = Number(res.pages || 0);
    if (reset) {
      followAuthorFeedQueue.value = playable;
      followAuthorVideoPageNo.value = pageNo + 1;
    } else {
      const existing = new Set(followAuthorFeedQueue.value.map((x) => String(x.id)));
      let merged = followAuthorFeedQueue.value;
      for (const p of playable) {
        if (!existing.has(String(p.id))) {
          merged = [...merged, p];
          existing.add(String(p.id));
        }
      }
      followAuthorFeedQueue.value = merged;
      followAuthorVideoPageNo.value = pageNo + 1;
    }
    followAuthorVideoHasMore.value = list.length >= pageSize && (pages <= 0 || pageNo < pages);
    if (list.length && !playable.length) {
      pushToast("该作者的作品暂无可用播放地址", "info");
    }
  } catch (e) {
    if (seq !== followAuthorLoadSeq) return;
    if (String(followSubscribedFilterAuthorId.value || "") !== aid) return;
    if (reset) {
      followAuthorFeedQueue.value = [];
    }
    followAuthorVideoHasMore.value = false;
    pushToast(e?.message || "加载作者视频失败", "error");
  } finally {
    if (seq === followAuthorLoadSeq) {
      followAuthorLoadingMore.value = false;
    }
  }
}

async function selectFollowSidebarAll() {
  const hadFilter = followSubscribedFilterAuthorId.value != null && followSubscribedFilterAuthorId.value !== "";
  followAuthorLoadSeq += 1;
  resetShortsFeedProgressUiForSwitch();
  followSubscribedFilterAuthorId.value = null;
  followAuthorFeedQueue.value = [];
  followAuthorVideoPageNo.value = 1;
  followAuthorVideoHasMore.value = false;
  shortsFeedIndex.value = 0;
  shortsFeedPlayToken += 1;
  shortsFeedAutoplayPending.value = true;
  if (hadFilter || followFeedQueue.value.length === 0) {
    await loadSubscribedFollowVideos({ reset: true });
  }
  nextTick(() => {
    if (followSubscribedFilterAuthorId.value != null && followSubscribedFilterAuthorId.value !== "") return;
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

async function selectFollowSidebarAuthor(user) {
  const id = user?.id ?? user?.followeeId;
  if (id == null || id === "") return;
  if (String(followSubscribedFilterAuthorId.value) === String(id)) {
    return;
  }
  followFeedLoadSeq += 1;
  resetShortsFeedProgressUiForSwitch();
  followSubscribedFilterAuthorId.value = id;
  followAuthorFeedQueue.value = [];
  followAuthorVideoPageNo.value = 1;
  followAuthorVideoHasMore.value = true;
  shortsFeedIndex.value = 0;
  shortsFeedPlayToken += 1;
  shortsFeedAutoplayPending.value = true;
  await loadSubscribedAuthorVideos(id, { reset: true });
  nextTick(() => {
    if (String(followSubscribedFilterAuthorId.value || "") !== String(id)) return;
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

function maybePrefetchFollowFeed() {
  if (feedScope.value !== "follow" || activeMenuKey.value !== "subscribed") {
    return;
  }
  const aid = followSubscribedFilterAuthorId.value;
  const list = aid != null && aid !== "" ? followAuthorFeedQueue.value : followFeedQueue.value;
  const i = shortsFeedIndex.value;
  if (!list.length) {
    return;
  }
  const remainToEnd = list.length - 1 - i;
  if (remainToEnd >= HOME_SHORTS_FEED_PREFETCH_THRESHOLD) {
    return;
  }
  if (aid != null && aid !== "") {
    void loadSubscribedAuthorVideos(aid, { reset: false });
  } else {
    void loadSubscribedFollowVideos({ reset: false });
  }
}

/** 仅当 id 为纯数字字符串时可用于 /videos/detail/{id}，全程用字符串避免精度丢失 */
function parseVideoIdForApi(id) {
  return parseDigitSnowflakeId(id);
}

/** 仅短视频写入 /videos/shorts/play；长视频在竖屏流里走续播，不得混入 shortIds */
function shouldRecordShortsWatchHistory(item) {
  return item != null && !isLongVideoItem(item);
}

/** 刷新 / 往返缓存后 DOM 就绪较晚，交给详情 store 多补几轮自动播放 */
function onWindowPageShow() {
  if (isAuthed.value) {
    reconnectNotifyWebSocketIfNeeded();
  }
  if (!showVideoDetail.value || !videoDetail.value?.videoUrl) {
    return;
  }
  detailStore.rescheduleAutoplay();
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

function applyMenuKeyIfKnown(key) {
  if (key == null || key === "") {
    return;
  }
  const menu = [...primaryMenus, ...exploreMenus].find((m) => m.key === key);
  if (!menu) {
    return;
  }
  activeMenuKey.value = menu.key;
  activeMenuLabel.value = menu.label;
}

/** 浏览器前进/后退、刷新：以 URL 为准同步菜单状态 */
watch(
  () => route.path,
  (p) => {
    const key = menuKeyForPath(p);
    if (key) {
      applyMenuKeyIfKnown(key);
    }
  },
  { immediate: true }
);

function captureProfileReturnContext() {
  if (showVideoDetail.value && videoDetail.value?.id != null) {
    return {
      kind: "detail",
      videoId: String(videoDetail.value.id),
      menuKey: activeMenuKey.value,
    };
  }
  const cur = shortsFeedCurrent.value;
  const curVid = cur?.id ?? cur?.videoId;
  /** 含：短视频/关注主 feed，以及历史、赞过、个人作品等全屏 overlay（此时 menu 可能仍是 history/liked） */
  const inUnifiedShortsPlayback =
    activeMenuKey.value === "shorts" ||
    activeMenuKey.value === "subscribed" ||
    profileFeedOpen.value;
  if (
    inUnifiedShortsPlayback &&
    unifiedFeedList.value.length &&
    cur &&
    curVid != null &&
    String(curVid) !== ""
  ) {
    const list = unifiedFeedList.value;
    return {
      kind: "shortsFeed",
      menuKey: activeMenuKey.value,
      feedState: {
        feedScope: feedScope.value,
        profileFeedOpen: profileFeedOpen.value,
        shortsFeedIndex: shortsFeedIndex.value,
        shortsCommentPanelOpen: shortsCommentPanelOpen.value,
        followSubscribedFilterAuthorId: followSubscribedFilterAuthorId.value,
        queueCopy: list.map((row) => ({ ...row })),
      },
    };
  }
  return null;
}

async function restoreProfileReturnContext(ctx) {
  if (!ctx) {
    return;
  }
  if (ctx.kind === "detail") {
    applyMenuKeyIfKnown(ctx.menuKey);
    await restoreVideoDetailFromUrl(ctx.videoId);
    return;
  }
  if (ctx.kind !== "shortsFeed") {
    return;
  }
  const st = ctx.feedState;
  applyMenuKeyIfKnown(ctx.menuKey);
  followSubscribedFilterAuthorId.value = st.followSubscribedFilterAuthorId;
  feedScope.value = st.feedScope;
  const copy = Array.isArray(st.queueCopy) ? st.queueCopy.map((x) => ({ ...x })) : [];
  if (st.feedScope === "profile") {
    profileFeedQueue.value = copy;
  } else if (st.feedScope === "liked") {
    likedFeedQueue.value = copy;
  } else if (st.feedScope === "follow") {
    const aid = st.followSubscribedFilterAuthorId;
    if (aid != null && aid !== "") {
      followAuthorFeedQueue.value = copy;
    } else {
      followFeedQueue.value = copy;
    }
  } else if (st.feedScope === "home") {
    homeShortsRows.value = copy;
  } else {
    cards.value = copy;
  }
  profileFeedOpen.value = st.profileFeedOpen;
  const maxI = Math.max(0, copy.length - 1);
  shortsFeedIndex.value = Math.min(Math.max(0, st.shortsFeedIndex), maxI);
  shortsCommentPanelOpen.value = st.shortsCommentPanelOpen;
  shortsFeedAutoplayPending.value = true;
  await nextTick();
  try {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  } catch {
    /* ignore */
  }
  profileStore.clearReturnContext();
}

/** 离开详情 / 关闭创作中心后刷新列表，避免主区空白 */
async function reloadFeedAfterLeavingDetail() {
  if (showProfileCenter.value || showStudio.value) {
    return;
  }
  const menu = getMenuByActiveKey();
  await loadModuleCards(menu);
  if (menu.key === "shorts" || menu.key === "subscribed") {
    nextTick(() => {
      shortsFeedFgRef.value?.load();
      shortsFeedBgRef.value?.load();
    });
  }
}

function onShortsPanelCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(shortsPanelCommentDraft, shortsPanelCommentInputRef, glyph);
}

function onShortsPanelCommentUserClick(ev, userId) {
  if (userId == null || userId === "") {
    return;
  }
  onAuthorAvatarClick(ev, userId);
}

/** 短视频评论面板：状态与请求已移入 shortsStore，这里保留同名薄封装供桥调用 */
const resetShortsPanelCommentsState = () => shortsStore.resetShortsPanelCommentsState();
const toggleShortsCommentPanel = () => shortsStore.toggleShortsCommentPanel();
const closeShortsCommentPanel = () => shortsStore.closeShortsCommentPanel();
const loadShortsPanelTopComments = (reset, opts) => shortsStore.loadShortsPanelTopComments(reset, opts);
const shortsPanelCommentHasMore = () => shortsStore.shortsPanelCommentHasMore();
const loadMoreShortsPanelComments = () => shortsStore.loadMoreShortsPanelComments();
const toggleShortsPanelThread = (rootId) => shortsStore.toggleShortsPanelThread(rootId);
const setShortsPanelReplyTarget = (row) => shortsStore.setShortsPanelReplyTarget(row);
const submitShortsPanelComment = () => shortsStore.submitShortsPanelComment();
const onDeleteShortsPanelComment = (c) => shortsStore.onDeleteShortsPanelComment(c);
const commentDisplayNickname = (userId, nickname) => shortsStore.commentDisplayNickname(userId, nickname);
const isCommentLikeBusy = (id) => shortsStore.isCommentLikeBusy(id);
const submitCommentLike = (row) => shortsStore.submitCommentLike(row);
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
  if (route.path !== "/shorts") {
    void router.push("/shorts");
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
  if (searchActive.value) {
    const el = e?.target;
    if (!el) return;
    if (searchScrollLoadTimer) {
      clearTimeout(searchScrollLoadTimer);
      searchScrollLoadTimer = null;
    }
    searchScrollLoadTimer = setTimeout(() => {
      searchScrollLoadTimer = null;
      const rootEl = contentScrollRef.value;
      if (!rootEl) return;
      const remain = rootEl.scrollHeight - rootEl.scrollTop - rootEl.clientHeight;
      if (remain >= 320) return;
      const tab = searchTab.value;
      if (tab === "all" || tab === "video") {
        void loadSearchVideoPage({ reset: false });
      } else if (tab === "user") {
        void loadSearchUserPage({ reset: false });
      } else if (tab === "tag") {
        void loadSearchTagPage({ reset: false });
      }
    }, 200);
    return;
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
      cards.value = longRes.list.length ? longRes.list : [];
      homeLongPageNo.value = 1;
      homeLongPages.value = longRes.pages ?? 0;
      homeShortsRows.value = shortRes.list.length ? shortRes.list : [];
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
    if (menu.key === "liked") {
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
      homeLongPageNo.value = 1;
      homeLongPages.value = 0;
      cards.value = [];
      if (!currentUserId.value) {
        likedRows.value = [];
        likedPageNo.value = 1;
        likedPages.value = 0;
        likedTotal.value = 0;
        pageHint.value = "请先登录后查看「赞过的视频」。";
        return;
      }
      await loadLikedPage({ reset: true });
      pageHint.value = `赞过的视频 · 共 ${likedTotal.value} 条（GET /like/{userId}/list）`;
      return;
    }
    if (menu.key === "subscribed") {
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
      homeLongPageNo.value = 1;
      homeLongPages.value = 0;
      followSubscribedFilterAuthorId.value = null;
      followAuthorFeedQueue.value = [];
      followAuthorVideoPageNo.value = 1;
      followAuthorVideoHasMore.value = false;
      followVideoPageNo.value = 1;
      followVideoHasMore.value = true;
      cards.value = [];
      if (!isAuthed.value) {
        followFeedQueue.value = [];
        pageHint.value = "请先登录后查看「关注」视频流。";
        return;
      }
      feedScope.value = "follow";
      shortsFeedIndex.value = 0;
      shortsFeedAutoplayPending.value = true;
      await profileStore.loadRelationPage("following", { reset: true });
      await loadSubscribedFollowVideos({ reset: true });
      pageHint.value = "关注 · 左侧为关注列表，右侧为关注作者的短视频推荐（GET /videos/follow/list）";
      return;
    }
    if (menu.key === "music") {
      loading.value = false;
      cards.value = [];
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
      homeLongPageNo.value = 1;
      homeLongPages.value = 0;
      pageHint.value = "音乐 · 本地播放与全屏歌词（前端演示）";
      return;
    }
    if (menu.key === "chat") {
      loading.value = false;
      cards.value = [];
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
      homeLongPageNo.value = 1;
      homeLongPages.value = 0;
      pageHint.value = "聊天 · 会话与消息（对接后端 /chat/*）";
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
      cards.value = list.length ? list : [];
    } else {
      cards.value = list.length ? list : [];
    }
    pageHint.value = `你正在查看「${menu.label}」模块。当前数据来源：Spring Boot 接口。`;
  } catch (error) {
    if (!fallback) {
      throw error;
    }
    if (menu.key === "home") {
      cards.value = [];
      homeLongPageNo.value = 1;
      homeLongPages.value = 0;
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
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
      } else if (menu.key === "liked") {
        likedRows.value = [];
        likedPageNo.value = 1;
        likedPages.value = 0;
        likedTotal.value = 0;
        cards.value = [];
      } else if (menu.key === "subscribed") {
        followFeedQueue.value = [];
        followAuthorFeedQueue.value = [];
        cards.value = [];
      } else {
        cards.value = [];
      }
      homeShortsRows.value = [];
      homeShortsPageNo.value = 1;
      homeShortsPages.value = 0;
    }
    pageHint.value = `接口暂不可用，当前显示为空数据。错误信息：${error.message}`;
  } finally {
    loading.value = false;
  }
}

async function setActiveMenu(menu) {
  const switchSeq = ++activeMenuSwitchSeq;
  searchActive.value = false;
  teardownSearchVideoObserver();
  const prevMenuKey = activeMenuKey.value;
  const prevShowProfile = showProfileCenter.value;
  const switchingShortsTabs =
    prevMenuKey !== menu.key &&
    (prevMenuKey === "shorts" || prevMenuKey === "subscribed") &&
    (menu.key === "shorts" || menu.key === "subscribed");
  if (switchingShortsTabs) {
    shortsFeedPlayToken += 1;
    pauseShortsFeedVideos({ muteForeground: true });
    shortsFeedPaused.value = true;
    resetShortsFeedProgressUiForSwitch();
    try {
      const fg = shortsFeedFgRef.value;
      const bg = shortsFeedBgRef.value;
      if (fg) {
        fg.pause();
        fg.currentTime = 0;
      }
      if (bg) {
        bg.pause();
        bg.currentTime = 0;
      }
    } catch {
      /* ignore */
    }
  }
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
  profileStore.clearReturnContext();
  mediaPreviewOpen.value = false;
  activeMenuKey.value = menu.key;
  activeMenuLabel.value = menu.label;
  const targetPath = menuPathFor(menu.key);
  if (route.path !== targetPath) {
    void router.push(targetPath);
  }
  if (menu.key === "shorts") {
    shortsFeedPlayToken += 1;
    feedScope.value = "shorts";
    shortsFeedIndex.value = 0;
    shortsFeedAutoplayPending.value = true;
  }
  if (menu.key === "subscribed") {
    shortsFeedPlayToken += 1;
    feedScope.value = "follow";
    followSubscribedFilterAuthorId.value = null;
    followAuthorFeedQueue.value = [];
    followAuthorVideoPageNo.value = 1;
    followAuthorVideoHasMore.value = false;
    followVideoPageNo.value = 1;
    followVideoHasMore.value = true;
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
  if (switchSeq !== activeMenuSwitchSeq) {
    return;
  }
  if (menu.key === "shorts" || menu.key === "subscribed") {
    nextTick(() => {
      if (switchSeq !== activeMenuSwitchSeq) return;
      if (activeMenuKey.value !== menu.key) return;
      shortsFeedFgRef.value?.load();
      shortsFeedBgRef.value?.load();
    });
  }
}

async function onSearch() {
  const keyword = searchKeyword.value.trim();
  if (!keyword) {
    pushToast(SEARCH_UI.emptyKeyword, "info");
    return;
  }
  // 创作中心/上传弹窗会挡住 v-else 下的搜索区，开始搜索时先退出以便结果可见
  uploadModalOpen.value = false;
  showStudio.value = false;
  searchDropdownOpen.value = false;
  searchSubmittedKeyword.value = keyword;
  searchActive.value = true;
  searchResultError.value = "";
  activeMenuLabel.value = `搜索：${keyword}`;
  pageHint.value = `搜索：「${keyword}」`;
  await loadSearchTabResults({ reset: true });
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
  if (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed") {
    pauseShortsFeedVideos({ muteForeground: true });
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

function openUploadModal() {
  uploadMsg.value = "";
  uploadProgress.value = 0;
  uploadMode.value = "video";
  uploadSlideshowImages.value = [];
  uploadSlideshowAudioFile.value = null;
  uploadMusicPickerOpen.value = false;
  uploadPickedFile.value = null;
  uploadPickedDuration.value = 0;
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
  uploadSlideshowImages.value = [];
  uploadSlideshowAudioFile.value = null;
  uploadMusicPickerOpen.value = false;
  uploadModalOpen.value = false;
}

function setUploadMode(mode) {
  if (uploadSubmitting.value) return;
  const m = mode === "slideshow" ? "slideshow" : "video";
  uploadMode.value = m;
  uploadMsg.value = "";
  uploadMusicPickerOpen.value = false;
  if (m === "video") {
    uploadSlideshowImages.value = [];
    uploadSlideshowAudioFile.value = null;
  } else {
    uploadPickedFile.value = null;
    uploadPickedDuration.value = 0;
  }
}

function triggerSlideshowImagesPick() {
  uploadSlideshowImagesInputRef.value?.click();
}

function triggerSlideshowAudioPick() {
  uploadSlideshowAudioInputRef.value?.click();
}

function openSlideshowMusicPicker() {
  if (uploadSubmitting.value) return;
  if (!uploadSlideshowImages.value.length) {
    pushToast("请先添加图片，再选择背景音乐", "info");
    return;
  }
  uploadMusicPickerOpen.value = true;
}

function onSlideshowCloudMusicPicked(file) {
  uploadSlideshowAudioFile.value = file;
  uploadMusicPickerOpen.value = false;
  uploadMsg.value = `已选背景音乐：${file.name}`;
}

function onSlideshowImagesInputChange(e) {
  const raw = Array.from(e?.target?.files || []).filter((f) => String(f?.type || "").startsWith("image/"));
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!raw.length) return;
  const merged = [...uploadSlideshowImages.value, ...raw].slice(0, MAX_SLIDESHOW_IMAGES);
  uploadSlideshowImages.value = merged;
  uploadMsg.value = `已选 ${merged.length} 张图片（最多 ${MAX_SLIDESHOW_IMAGES} 张）`;
}

function onSlideshowAudioInputChange(e) {
  const f = e?.target?.files?.[0];
  if (e?.target && "value" in e.target) {
    e.target.value = "";
  }
  if (!f) return;
  if (!String(f.type || "").startsWith("audio/") && !/\.(mp3|m4a|aac|wav|flac|ogg)$/i.test(f.name || "")) {
    uploadMsg.value = "请选择音频文件";
    return;
  }
  uploadSlideshowAudioFile.value = f;
  uploadMsg.value = `已选背景音乐：${f.name}`;
}

function removeSlideshowImage(idx) {
  const next = uploadSlideshowImages.value.filter((_, i) => i !== idx);
  uploadSlideshowImages.value = next;
}

function clearSlideshowImages() {
  uploadSlideshowImages.value = [];
  uploadMsg.value = "";
}

function clearSlideshowAudio() {
  uploadSlideshowAudioFile.value = null;
}

function onUploadModalDrop(e) {
  if (uploadSubmitting.value) return;
  if (uploadMode.value === "slideshow") {
    const files = Array.from(e?.dataTransfer?.files || []).filter((f) => String(f?.type || "").startsWith("image/"));
    if (!files.length) return;
    const merged = [...uploadSlideshowImages.value, ...files].slice(0, MAX_SLIDESHOW_IMAGES);
    uploadSlideshowImages.value = merged;
    uploadMsg.value = `已添加 ${files.length} 张图片，共 ${merged.length} 张`;
    return;
  }
  onUploadInputOrDrop(e);
}

async function pollMergePictureJob(jobId) {
  const id = String(jobId || "").trim();
  if (!id) throw new Error("缺少合成任务 ID");
  for (let i = 0; i < MERGE_POLL_MAX_ATTEMPTS; i++) {
    const st = await getMergePictureJobStatus(id);
    if (!st || typeof st !== "object") {
      throw new Error("任务状态异常");
    }
    if (st.status === "DONE") {
      if (!st.videoObjectKey) {
        throw new Error("合成成功但未返回视频地址");
      }
      return st;
    }
    if (st.status === "FAIL") {
      throw new Error(st.errorMessage || "幻灯片合成失败");
    }
    uploadProgress.value = Math.min(92, 72 + Math.floor(i / 4));
    await new Promise((r) => setTimeout(r, MERGE_POLL_INTERVAL_MS));
  }
  throw new Error("合成超时，请稍后重试或联系管理员");
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
  link.setAttribute("type", "image/png");
  link.setAttribute("href", appIco);
}

/** 与轮询配合：未读数基准，避免 WS 已推过仍重复提示 */
let notifyUnreadPollBaseline = -1;
let notifyUnreadPollTimer = null;

async function refreshNotifyUnreadCount() {
  if (!getToken()) {
    notifyUnreadCount.value = 0;
    notifyUnreadPollBaseline = -1;
    return;
  }
  try {
    const c = await fetchUnreadNotificationCount();
    notifyUnreadCount.value = c;
    notifyUnreadPollBaseline = c;
  } catch {
    /* ignore */
  }
}

function startNotifyUnreadPoll() {
  stopNotifyUnreadPoll();
  notifyUnreadPollTimer = window.setInterval(async () => {
    if (!getToken()) return;
    try {
      const c = await fetchUnreadNotificationCount();
      if (notifyUnreadPollBaseline >= 0 && c > notifyUnreadPollBaseline) {
        pushToast("您有新的通知，请点击顶部铃铛查看", "info");
      }
      notifyUnreadCount.value = c;
      notifyUnreadPollBaseline = c;
      reconnectNotifyWebSocketIfNeeded();
    } catch {
      /* ignore */
    }
  }, 22000);
}

function stopNotifyUnreadPoll() {
  if (notifyUnreadPollTimer != null) {
    window.clearInterval(notifyUnreadPollTimer);
    notifyUnreadPollTimer = null;
  }
  notifyUnreadPollBaseline = -1;
}

async function loadNotifyListPage(reset) {
  if (!getToken()) return;
  notifyLoading.value = true;
  if (reset) {
    notifyErrorMsg.value = "";
  }
  const page = reset ? 1 : notifyPageNo.value;
  if (reset) {
    notifyPageNo.value = 1;
  }
  try {
    const { total, pages, list } = await fetchNotificationPage({
      pageNo: page,
      pageSize: NOTIFY_PAGE_SIZE,
    });
    notifyPages.value = pages;
    notifyTotal.value = total;
    if (reset) {
      notifyList.value = list;
    } else {
      notifyList.value = [...notifyList.value, ...list];
    }
  } catch (e) {
    notifyErrorMsg.value = e?.message || "加载通知失败";
  } finally {
    notifyLoading.value = false;
  }
}

function toggleNotifyPanel() {
  notifyPanelOpen.value = !notifyPanelOpen.value;
  if (notifyPanelOpen.value) {
    pageHint.value = "通知";
    void refreshNotifyUnreadCount();
    void loadNotifyListPage(true);
  }
}

function closeNotifyPanel() {
  notifyPanelOpen.value = false;
}

async function loadMoreNotifications() {
  if (notifyLoading.value) return;
  if (notifyPageNo.value >= notifyPages.value) return;
  notifyPageNo.value += 1;
  await loadNotifyListPage(false);
}

function formatNotifyTime(t) {
  if (!t) return "";
  try {
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return String(t);
    return d.toLocaleString();
  } catch {
    return String(t);
  }
}

async function onNotifyItemClick(item) {
  if (!item?.id) return;
  try {
    await markNotificationRead(item.id);
    await refreshNotifyUnreadCount();
  } catch (e) {
    pushToast(e?.message || "标记已读失败", "error");
    return;
  }
  const nid = item.id;
  notifyList.value = notifyList.value.map((r) => (r.id === nid ? { ...r, isRead: true } : r));
  if (String(item.type || "").toUpperCase() === "CHAT_NOTICE") {
    closeNotifyPanel();
    pushToast(String(item.content || "该群聊已解散").trim() || "该群聊已解散", "info");
    return;
  }
  const vid = item.videoId != null ? parseVideoIdForApi(item.videoId) : null;
  if (vid == null) {
    pushToast("暂无法跳转到视频（可能评论已删除）", "info");
    return;
  }
  closeNotifyPanel();
  await openVideoDetail({ id: vid });
}

async function onNotifyDelete(item) {
  if (!item?.id) return;
  try {
    await deleteNotification(item.id);
    notifyList.value = notifyList.value.filter((r) => r.id !== item.id);
    notifyTotal.value = Math.max(0, notifyTotal.value - 1);
    const ps = Math.max(1, Math.ceil(notifyTotal.value / NOTIFY_PAGE_SIZE) || 1);
    notifyPages.value = ps;
    await refreshNotifyUnreadCount();
  } catch (e) {
    pushToast(e?.message || "删除失败", "error");
  }
}

function onNotifySocketMessage(data) {
  const raw = typeof data === "string" ? data : String(data);
  let parsed = null;
  try {
    parsed = JSON.parse(raw);
  } catch {
    /* 非 JSON：走下方 Toast */
  }
  if (parsed && typeof parsed === "object") {
    const chatKind = tryIngestChatWebSocket(parsed);
    if (chatKind) {
      if (chatKind === "send") {
        maybePushChatIncomingLiveToast(parsed);
      }
      return;
    }
  }
  void refreshNotifyUnreadCount();
  if (notifyPanelOpen.value) {
    void loadNotifyListPage(true);
  }
  try {
    const o = parsed && typeof parsed === "object" ? parsed : JSON.parse(raw);
    const nick = pickNotifyWsNickname(o);
    const typeCode = pickNotifyWsTypeCode(o);
    const label = NOTIFICATION_TYPE_LABELS[typeCode] || typeCode || "新通知";
    const title = nick ? `${nick} · ${label}` : label;
    let content = pickNotifyWsContent(o);
    if (!String(content || "").trim()) {
      content = NOTIFICATION_TYPE_LABELS[typeCode] || "";
    }
    const avatarUrl = pickNotifyWsAvatarUrl(o);
    const videoId = pickNotifyWsVideoId(raw, o);
    const notificationId = pickNotifyWsNotificationId(raw, o);
    const typeUpper = String(typeCode || "").toUpperCase();
    const isChatNotice = typeUpper === "CHAT_NOTICE";
    const conversationId = isChatNotice ? null : pickNotifyWsConversationId(raw, o);
    pushNotifyLiveToast({
      avatarUrl,
      title,
      content,
      videoId,
      notificationId,
      conversationId,
      notifyKind: isChatNotice ? "CHAT_NOTICE" : null,
    });
    if (isChatNotice) {
      const dissolvedConvId = pickNotifyWsConversationId(raw, o);
      if (dissolvedConvId && String(chatActiveConversationId.value || "") === String(dissolvedConvId)) {
        chatActiveConversationId.value = null;
      }
      void refreshConversationList(true);
    }
  } catch {
    pushToast(raw.slice(0, 120), "info");
  }
}

/** 搜索「用户」Tab：整行进入主页（排除关注按钮） */
function pickSearchUserSnowflakeId(u) {
  const raw = u?.id;
  if (raw == null || raw === "") return null;
  if (typeof raw === "bigint") return raw.toString();
  const s = String(raw).trim();
  if (/^\d+$/.test(s)) return s;
  return parseDigitSnowflakeId(raw);
}

function onSearchUserRowActivate(u, ev) {
  if (ev?.target?.closest?.(".search-user-follow-pill")) {
    return;
  }
  const id = pickSearchUserSnowflakeId(u);
  if (!id || !/^\d+$/.test(id)) {
    return;
  }
  onAuthorAvatarClick(ev, id);
}

/** 仅从列表项取播放地址，不再请求 /videos/detail */

/** 在作品列表 / 关注流 / 赞列表间按 id 查找已有行，避免重复请求详情 */
function resolveVideoUrlForListItem(videoId, hintItem) {
  const id = videoId != null ? String(videoId) : "";
  if (!id) return "";
  const direct = resolveVideoUrl(hintItem);
  if (direct) return direct;
  const fromProfile = profileWorksRows.value.find((x) => String(x.id) === id);
  if (fromProfile && resolveVideoUrl(fromProfile)) return resolveVideoUrl(fromProfile);
  const fromCards = cards.value.find((x) => String(x.id) === id);
  if (fromCards && resolveVideoUrl(fromCards)) return resolveVideoUrl(fromCards);
  const fromLiked = likedRows.value.find((x) => String(x.id) === id);
  if (fromLiked && resolveVideoUrl(fromLiked)) return resolveVideoUrl(fromLiked);
  return "";
}

/** 点赞接口返回后同步更新各列表中的 likeCount */
function patchFeedVideoById(videoId, patch) {
  const id = String(videoId ?? "");
  if (!id || !patch || typeof patch !== "object") return;
  const apply = (arrRef) => {
    const arr = arrRef.value;
    const i = arr.findIndex((x) => x && String(x.id) === id);
    if (i < 0) return;
    const next = [...arr];
    next[i] = { ...next[i], ...patch };
    arrRef.value = next;
  };
  apply(homeShortsRows);
  apply(cards);
  apply(profileFeedQueue);
  apply(likedFeedQueue);
  apply(likedRows);
  apply(profileWorksRows);
  apply(followFeedQueue);
  apply(followAuthorFeedQueue);
  apply(studioRows);
  apply(searchVideoRows);
  apply(searchTagRows);
  if (searchExpandCard.value && String(searchExpandCard.value.id) === id) {
    searchExpandCard.value = { ...searchExpandCard.value, ...patch };
  }
  if (searchListCommentCard.value && String(searchListCommentCard.value.id) === id) {
    searchListCommentCard.value = { ...searchListCommentCard.value, ...patch };
  }
}

/** 同一作者下所有视频条目的 isFollow（与列表数据一致，不请求详情） */
function patchIsFollowForAuthor(authorId, isFollow) {
  const aid = String(authorId ?? "");
  if (!aid) return;
  const patchRow = (row) =>
    row && String(row.authorId) === aid ? { ...row, isFollow } : row;
  const patchArr = (arrRef) => {
    const arr = arrRef.value;
    if (!Array.isArray(arr) || !arr.length) return;
    arrRef.value = arr.map(patchRow);
  };
  patchArr(homeShortsRows);
  patchArr(cards);
  patchArr(profileWorksRows);
  patchArr(profileFeedQueue);
  patchArr(likedFeedQueue);
  patchArr(likedRows);
  patchArr(followFeedQueue);
  patchArr(followAuthorFeedQueue);
  patchArr(searchVideoRows);
  patchArr(searchTagRows);
  const patchUserByUserId = (row) =>
    row && String(row.id) === aid ? { ...row, isFollow } : row;
  if (Array.isArray(searchUserRows.value) && searchUserRows.value.length) {
    searchUserRows.value = searchUserRows.value.map(patchUserByUserId);
  }
  if (Array.isArray(searchAllUserStrip.value) && searchAllUserStrip.value.length) {
    searchAllUserStrip.value = searchAllUserStrip.value.map(patchUserByUserId);
  }
  if (searchExpandCard.value && String(searchExpandCard.value.authorId) === aid) {
    searchExpandCard.value = { ...searchExpandCard.value, isFollow };
  }
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
  const title = String(uploadForm.value.title || "").trim();
  if (!title) {
    uploadMsg.value = "请填写视频标题";
    pushToast("请填写视频标题", "info");
    return;
  }
  const isSlideshow = uploadMode.value === "slideshow";
  if (isSlideshow) {
    if (!uploadSlideshowImages.value.length) {
      uploadMsg.value = "请至少选择一张图片";
      pushToast("请至少选择一张图片", "info");
      return;
    }
  } else if (!uploadPickedFile.value) {
    uploadMsg.value = "请先选择视频文件";
    pushToast("请先选择视频文件", "info");
    return;
  }

  uploadSubmitting.value = true;
  uploadMsg.value = "";
  uploadProgress.value = 0;
  try {
    const publishBodyBase = {
      title,
      description: String(uploadForm.value.description || "").trim(),
      category: uploadForm.value.category,
      tags: uploadTagList.value.join(","),
      visibility: uploadForm.value.visibility,
      allowComment: Number(uploadForm.value.allowComment ? 1 : 0),
    };

    if (isSlideshow) {
      const imgs = uploadSlideshowImages.value;
      const audio = uploadSlideshowAudioFile.value;
      const totalParts = imgs.length + (audio ? 1 : 0);
      const objectKeys = [];
      for (let i = 0; i < imgs.length; i++) {
        uploadMsg.value = `正在上传图片 ${i + 1}/${imgs.length}…`;
        const { objectKey } = await uploadFileWithPresign(imgs[i], STORAGE_BIZ_WORK, (p) => {
          const base = (i + (p || 0)) / totalParts;
          uploadProgress.value = Math.max(0, Math.min(68, Math.round(base * 68)));
        });
        objectKeys.push(objectKey);
      }
      let audioObjectKey;
      if (audio) {
        uploadMsg.value = "正在上传音频…";
        const r = await uploadFileWithPresign(audio, STORAGE_BIZ_WORK, (p) => {
          const base = (imgs.length + (p || 0)) / totalParts;
          uploadProgress.value = Math.max(0, Math.min(68, Math.round(base * 68)));
        });
        audioObjectKey = r.objectKey;
      }
      uploadProgress.value = 70;
      uploadMsg.value = "已提交合成任务，请稍候…";
      const jobId = await mergePicturesToVideo({
        objectKeys,
        ...(audioObjectKey ? { audioObjectKey } : {}),
      });
      const merged = await pollMergePictureJob(jobId);
      uploadProgress.value = 94;
      uploadMsg.value = "正在发布视频…";
      await publishVideo({
        ...publishBodyBase,
        videoUrl: merged.videoObjectKey,
        duration: Number(merged.durationSeconds || 1),
      });
    } else {
      const file = uploadPickedFile.value;
      const { objectKey } = await uploadFileWithPresign(file, STORAGE_BIZ_WORK, (p) => {
        uploadProgress.value = Math.max(0, Math.min(100, Math.round((p || 0) * 100)));
      });
      uploadProgress.value = 100;
      const duration = uploadPickedDuration.value || (await readVideoDuration(file));
      uploadMsg.value = "正在发布视频…";
      await publishVideo({
        ...publishBodyBase,
        videoUrl: objectKey,
        duration: Number(duration || 1),
      });
    }

    uploadProgress.value = 100;
    uploadMsg.value = "上传成功，等待审核…";
    pushToast("上传成功，等待审核…", "success");
    pageHint.value = `已发布：${title}`;
    uploadModalOpen.value = false;
    uploadPickedFile.value = null;
    uploadPickedDuration.value = 0;
    uploadMode.value = "video";
    uploadSlideshowImages.value = [];
    uploadSlideshowAudioFile.value = null;
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
      void profileStore.loadWorks();
    }
  } catch (e) {
    uploadMsg.value = `发布失败：${e.message || e}`;
    pushToast(`发布失败：${e.message || e}`, "error");
  } finally {
    uploadSubmitting.value = false;
    uploadProgress.value = 0;
  }
}

/** 评论侧栏：短视频流、搜索全屏放大或搜索列表共用同一套列表与接口 */

watch(
  () => shortsFeedVideoSrc.value,
  (src) => {
    if (!src) {
      hideShortsBufferingOverlay();
    }
  }
);

watch(
  () => [
    shortsCommentPanelOpen.value,
    searchExpandCommentOpen.value,
    searchListCommentOpen.value,
    String(activeCommentVideo.value?.id ?? ""),
  ],
  ([openS, openE, openList, idStr]) => {
    const open = openS || openE || openList;
    if (!open) {
      resetShortsPanelCommentsState();
      return;
    }
    if (!idStr) {
      return;
    }
    resetShortsPanelCommentsState();
    void loadShortsPanelTopComments(true);
  },
);

function requestDeleteCurrentSelfVideo() {
  if (!shortsIsSelfCurrent.value) {
    pushToast("只能删除自己的视频", "info");
    return;
  }
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  closeSelfVideoActionMenu();
  pendingDeleteVideoId.value = String(cur.id);
  selfVideoDeleteConfirmOpen.value = true;
}

function cancelDeleteCurrentSelfVideo() {
  selfVideoDeleteConfirmOpen.value = false;
  pendingDeleteVideoId.value = null;
}

function removeVideoFromAllClientLists(targetId) {
  const id = String(targetId);
  profileWorksRows.value = profileWorksRows.value.filter((x) => String(x.id) !== id);
  profileFeedQueue.value = profileFeedQueue.value.filter((x) => String(x.id) !== id);
  cards.value = cards.value.filter((x) => String(x.id) !== id);
  homeShortsRows.value = homeShortsRows.value.filter((x) => String(x.id) !== id);
  likedRows.value = likedRows.value.filter((x) => String(x.id) !== id);
  likedFeedQueue.value = likedFeedQueue.value.filter((x) => String(x.id) !== id);
  studioRows.value = studioRows.value.filter((x) => String(x.id) !== id);
  followFeedQueue.value = followFeedQueue.value.filter((x) => String(x.id) !== id);
  followAuthorFeedQueue.value = followAuthorFeedQueue.value.filter((x) => String(x.id) !== id);
}

async function confirmDeleteCurrentSelfVideo() {
  const rawId = pendingDeleteVideoId.value;
  if (!rawId) {
    selfVideoDeleteConfirmOpen.value = false;
    return;
  }
  try {
    await deleteMyVideo(rawId);
  } catch (e) {
    pushToast(e?.message || "删除失败", "error");
    return;
  }
  selfVideoDeleteConfirmOpen.value = false;
  pendingDeleteVideoId.value = null;
  const targetId = String(rawId);
  removeVideoFromAllClientLists(targetId);

  if (feedScope.value === "profile" && !profileFeedQueue.value.length) {
    closeProfileFeed();
    pushToast("已删除该视频", "success");
    return;
  }
  if (feedScope.value === "liked" && !likedFeedQueue.value.length) {
    closeProfileFeed();
    pushToast("已删除该视频", "success");
    return;
  }
  const list = unifiedFeedList.value;
  if (shortsFeedIndex.value >= list.length) {
    shortsFeedIndex.value = Math.max(0, list.length - 1);
  }
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
  pushToast("已删除该视频", "success");
}

function onStudioRowRequestDelete(row) {
  if (!row?.id) return;
  pendingDeleteVideoId.value = String(row.id);
  selfVideoDeleteConfirmOpen.value = true;
  closeStudioRowMenu();
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
  if (activeMenuKey.value === "subscribed") {
    void openFollowFeedFromCard(card);
    return;
  }
  openVideoDetail(card);
}

function openFollowFeedFromCard(item) {
  const source = Array.isArray(cards.value) ? cards.value : [];
  if (!source.length) {
    pageHint.value = "暂无关注视频";
    return;
  }
  const selectedUrl = resolveVideoUrl(item) || resolveVideoUrlForListItem(item.id, item);
  if (!selectedUrl) {
    pageHint.value = "该内容暂无视频地址";
    return;
  }
  const playableQueue = source
    .map((x) => {
      const url = resolveVideoUrl(x) || resolveVideoUrlForListItem(x.id, x);
      return { ...x, videoUrl: url, videoType: "SHORT_VIDEO" };
    })
    .filter((x) => x.videoUrl);
  if (!playableQueue.find((x) => String(x.id) === String(item.id))) {
    playableQueue.unshift({ ...item, videoUrl: selectedUrl, videoType: "SHORT_VIDEO" });
  }
  if (!playableQueue.length && selectedUrl) {
    playableQueue.push({ ...item, videoUrl: selectedUrl, videoType: "SHORT_VIDEO" });
  }
  const idx = playableQueue.findIndex((x) => String(x.id) === String(item.id));
  feedScope.value = "follow";
  followFeedQueue.value = playableQueue;
  shortsFeedIndex.value = idx >= 0 ? idx : 0;
  shortsFeedAutoplayPending.value = true;
  profileFeedOpen.value = activeMenuKey.value !== "subscribed";
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

function enqueueShortsHistoryByVideoId(videoIdLike, feedItem) {
  if (!isAuthed.value) return;
  if (!shouldRecordShortsWatchHistory(feedItem)) return;
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
    const authTok = getToken();
    if (authTok) {
      headers.Authorization = authTok;
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

function closeTopMostOverlayByEsc() {
  if (uploadModalOpen.value) {
    uploadModalOpen.value = false;
    return true;
  }
  if (mediaPreviewOpen.value) {
    profileStore.closeMediaPreview();
    return true;
  }
  if (profileStore.relationOpen) {
    profileStore.closeRelationTable();
    return true;
  }
  if (profileStore.editOpen) {
    profileStore.closeEdit();
    return true;
  }
  if (shortPlayerOpen.value) {
    closeShortPlayer();
    return true;
  }
  if (shortsCommentPanelOpen.value || searchExpandCommentOpen.value || searchListCommentOpen.value) {
    closeShortsCommentPanel();
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

  detailStore.handleKeydown(ev);
}

async function openProfileWorkFeed(item) {
  const source = profileWorksRows.value;
  if (!source.length) {
    pageHint.value = "该作者暂无可播放作品";
    return;
  }
  resetShortsFeedProgressUiForSwitch();
  const selectedUrl = resolveVideoUrl(item) || resolveVideoUrlForListItem(item.id, item);
  if (!selectedUrl) {
    pageHint.value = "该内容暂无视频地址";
    return;
  }
  const viewAuthorId =
    profileUser.value?.id != null && profileUser.value.id !== ""
      ? profileUser.value.id
      : currentUserId.value != null
        ? currentUserId.value
        : null;
  const viewAvatar =
    sanitizeImageUrl(profileUser.value?.avatarUrl || "") ||
    (isViewingSelf.value ? sanitizeImageUrl(selfUserProfile.value?.avatarUrl || "") : "") ||
    "";
  const viewNick =
    stripUserIdLikeName(profileUser.value?.nickName || profileUser.value?.nickname || "") ||
    (isViewingSelf.value
      ? stripUserIdLikeName(selfUserProfile.value?.nickName || selfUserProfile.value?.nickname || "")
      : "") ||
    "";
  const playableQueue = source
    .map((x) => {
      const url = resolveVideoUrl(x) || resolveVideoUrlForListItem(x.id, x);
      const authorId = x.authorId ?? x.userId ?? viewAuthorId;
      const authorAvatarUrl = x.authorAvatarUrl || x.authorAvatar || viewAvatar;
      const authorNickname = x.authorNickname || x.author || viewNick;
      return { ...x, videoUrl: url, authorId, authorAvatarUrl, authorNickname };
    })
    .filter((x) => x.videoUrl);
  if (!playableQueue.find((x) => String(x.id) === String(item.id))) {
    playableQueue.unshift({ ...item, videoUrl: selectedUrl });
  }
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

async function openLikedWorkFeed(item) {
  const source = likedRows.value;
  if (!source.length) {
    pageHint.value = "暂无赞过的视频";
    return;
  }
  const selectedUrl = resolveVideoUrl(item) || resolveVideoUrlForListItem(item.id, item);
  if (!selectedUrl) {
    pageHint.value = "该内容暂无视频地址";
    return;
  }
  const playableQueue = source
    .map((x) => {
      const url = resolveVideoUrl(x) || resolveVideoUrlForListItem(x.id, x);
      return { ...x, videoUrl: url };
    })
    .filter((x) => x.videoUrl);
  if (!playableQueue.find((x) => String(x.id) === String(item.id))) {
    playableQueue.unshift({ ...item, videoUrl: selectedUrl });
  }
  if (!playableQueue.length && selectedUrl) {
    playableQueue.push({ ...item, videoUrl: selectedUrl });
  }
  const idx = playableQueue.findIndex((x) => String(x.id) === String(item.id));
  feedScope.value = "liked";
  likedFeedQueue.value = playableQueue;
  shortsFeedIndex.value = idx >= 0 ? idx : 0;
  shortsFeedAutoplayPending.value = true;
  profileFeedOpen.value = true;
  nextTick(() => {
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
  });
}

function onLikedWorkClick(item) {
  void openLikedWorkFeed(item);
}

function closeProfileFeed() {
  void flushShortsLongPlaybackResume("leave");
  pauseShortsFeedVideos({ muteForeground: true });
  try {
    const fg = shortsFeedFgRef.value;
    const bg = shortsFeedBgRef.value;
    if (fg) {
      fg.removeAttribute("src");
      fg.load();
    }
    if (bg) {
      bg.removeAttribute("src");
      bg.load();
    }
  } catch {
    /* ignore */
  }
  selfVideoActionOpen.value = false;
  selfVideoDeleteConfirmOpen.value = false;
  pendingDeleteVideoId.value = null;
  shortsCommentPanelOpen.value = false;
  profileFeedOpen.value = false;
  feedScope.value = "shorts";
  profileFeedQueue.value = [];
  likedFeedQueue.value = [];
  followFeedQueue.value = [];
  shortsStore.resetShortsLongResumeState();
}

function onProfileFeedKeydown(ev) {
  if (ev.key === "Escape") {
    if (shortsCommentPanelOpen.value) {
      closeShortsCommentPanel();
      return;
    }
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

/**
 * 短视频全屏：仅在「videoId + scope」真正变化时同步爱心初始状态。
 * 注意：不能用数组作为 watch 源，否则列表对象 patch 后也会触发，导致把刚点的红心重置掉。
 */
watch(
  () => `${feedScope.value}:${String(shortsFeedCurrent.value?.id ?? "")}`,
  () => {
    selfVideoActionOpen.value = false;
  }
);

watch(showStudio, (open) => {
  if (!open) {
    closeStudioRowMenu();
  }
});

watch(activeMenuKey, (key) => {
  stopCardHoverPreview();
  if (key !== "shorts" && key !== "subscribed") {
    pauseShortsFeedVideos({ muteForeground: true });
  }
});

watch(
  () => !showProfileCenter.value && (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed"),
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
  shortsFeedPlayToken += 1;
  resetShortsFeedProgressUiForSwitch();
  const tokenAtSwitch = shortsFeedPlayToken;
  nextTick(() => {
    const inMainShorts =
      activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed";
    if (!inMainShorts && !profileFeedOpen.value) return;
    if (tokenAtSwitch !== shortsFeedPlayToken) return;
    shortsFeedFgRef.value?.load();
    shortsFeedBgRef.value?.load();
    maybePrefetchHomeShortsForFeed();
    maybePrefetchFollowFeed();
  });
});

watch(showVideoDetail, (open) => {
  if (open) {
    pauseShortsFeedVideos({ muteForeground: true });
  } else {
    nextTick(() => {
      restoreShortsFeedForegroundMuteFromState();
    });
  }
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
    authStore.signIn(responseToken);
    void refreshNotifyUnreadCount();
    authMessage.value = "登录成功。";
    pushToast("登录成功", "success");
    const homeMenu = primaryMenus[0];
    activeMenuLabel.value = "首页";

    showLoginLoadingOverlay.value = true;
    await nextTick();

    const loadAfterLogin = async () => {
      await profileStore.loadUser();
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
    };
    let cubesAnimSettled = false;
    let cubesFallbackTimer = null;
    const cubesAnimDone = new Promise((resolve) => {
      const finishCubes = () => {
        if (cubesAnimSettled) return;
        cubesAnimSettled = true;
        if (cubesFallbackTimer != null) {
          clearTimeout(cubesFallbackTimer);
          cubesFallbackTimer = null;
        }
        resolve();
        loginCubesAnimResolve = null;
      };
      loginCubesAnimResolve = finishCubes;
      /** 动画事件异常时兜底，避免永久卡住（正常约 6s 内结束） */
      cubesFallbackTimer = setTimeout(finishCubes, 16000);
    });
    const loadAfterLoginPromise = loadAfterLogin();
    /** 最后一格扫光结束即撤层；数据继续在后台加载 */
    await cubesAnimDone;
    showLoginLoadingOverlay.value = false;
    await loadAfterLoginPromise;
  } catch (error) {
    authMessage.value = `登录失败：${error.message}`;
    pushToast(`登录失败：${error.message || "请检查账号信息"}`, "error");
  } finally {
    authLoading.value = false;
    showLoginLoadingOverlay.value = false;
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
  authStore.signOut();
  shortsHistoryQueue = [];
  shortsHistoryQueueSet.clear();
  shortsHistorySeenSet.clear();
  homeFeedSnapshot.value = null;
  cards.value = [];
  profileStore.close();
  showStudio.value = false;
  studioHistoryPushed.value = false;
  uploadModalOpen.value = false;
  clearVideoDetailFromAddressBar();
  showVideoDetail.value = false;
  videoDetail.value = null;
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
  authStore.signOut();
  hideDetailBufferingOverlay();
  showVideoDetail.value = false;
  profileStore.close();
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
    const vParam = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
    const hadProfile = showProfileCenter.value;
    const returnCtx = profileStore.readReturnContext();
    showProfileCenter.value = false;
    profileTargetUserId.value = null;
    if (hadProfile && returnCtx?.kind === "shortsFeed") {
      profileStore.clearReturnContext();
      void restoreProfileReturnContext(returnCtx);
      return;
    }
    const vidRestore = vParam || (returnCtx?.kind === "detail" ? returnCtx.videoId : null);
    if (hadProfile && vidRestore) {
      if (returnCtx?.menuKey) {
        applyMenuKeyIfKnown(returnCtx.menuKey);
      }
      profileStore.clearReturnContext();
      void restoreVideoDetailFromUrl(vidRestore);
      return;
    }
    profileStore.clearReturnContext();
    if (hadProfile) {
      const ui = loadUiStateFromSession();
      if (ui?.activeMenuKey) {
        applyMenuKeyIfKnown(ui.activeMenuKey);
        if (ui.homeCategoryKey) {
          homeCategoryKey.value = ui.homeCategoryKey;
        }
        if (ui.homeSortKey) {
          homeSortKey.value = ui.homeSortKey;
        }
        return;
      }
    }
    setActiveMenu(primaryMenus[0]);
    return;
  }
  const v = new URLSearchParams(window.location.search).get(VIDEO_URL_PARAM);
  if (!v && showVideoDetail.value) {
    detailStore.closeByHistory();
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
        detailStore.scheduleAutoplayRetries();
        setTimeout(() => detailStore.scheduleAutoplayRetries(), 500);
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

/** 离开聊天全屏页后仍保留 chatActiveConversationId 会导致 WS 新消息 Toast 被误判为「正在看该会话」；切走菜单时取消选中 */
watch(activeMenuKey, (key, prevKey) => {
  if (key !== "chat") {
    chatActiveConversationId.value = null;
  }
  const prev = String(prevKey || "");
  const next = String(key || "");
  const switchingBetweenShortsTabs =
    (prev === "shorts" || prev === "subscribed") && (next === "shorts" || next === "subscribed");
  if (!switchingBetweenShortsTabs) {
    pauseAllDomVideos();
  }
});

watch(
  isAuthed,
  async (authed) => {
    if (authed) {
      await refreshNotifyUnreadCount();
      startNotifyWebSocket({
        onMessage: onNotifySocketMessage,
      });
      startNotifyUnreadPoll();
    } else {
      stopNotifyUnreadPoll();
      stopNotifyWebSocket();
      hideShortsBufferingOverlay();
      closeNotifyPanel();
      notifyUnreadCount.value = 0;
      notifyList.value = [];
      notifyPages.value = 0;
      notifyTotal.value = 0;
      notifyErrorMsg.value = "";
    }
  },
  { immediate: true }
);

watch(
  () => [isAuthed.value, currentUserId.value, selfUserProfile.value],
  () => {
    if (isAuthed.value && currentUserId.value != null) {
      const u = selfUserProfile.value;
      setChatUserContext({
        userId: currentUserId.value,
        nickName: u?.nickName || u?.nickname || "",
        avatarUrl: u?.avatarUrl ? sanitizeImageUrl(u.avatarUrl) : "",
      });
      void initChatWhenAuthed();
    } else {
      resetChatWhenLogout();
    }
  },
  { immediate: true }
);

/** SearchView 残余桥: 跨列表同步/点赞系统/进全屏与主页仍依赖 App 级状态 */
provide("searchBridge", {
  onSearchTagNavigate,
  onSearchUserRowActivate,
  searchUserRowIsSelf,
  searchUserRowIsFollowed,
  toggleSearchUserFollow,
  onAuthorAvatarClick,
  onSearchFeedForwardClick,
  onSearchFeedMainVideoClick,
  onSearchFeedLikeClick,
});

/** HistoryView/LikedView 残余桥: 进他人主页与赞过连播依赖 App 级 overlay/列表查找 */
provide("cardsBridge", {
  onAuthorAvatarClick,
  onLikedWorkClick,
});

/** 搜索页文案与分页常量已移至 stores/searchStore.js */

let searchSuggestBlurTimer = null;
let searchPromptDebounceTimer = null;
let searchVideoObserver = null;
let searchScrollLoadTimer = null;

/** 搜索列表进度条：节流 timeupdate，避免每帧整表重绘卡死 */
const searchFeedProgressThrottleTs = new Map();

const forwardVideoOpen = ref(false);
const forwardVideoCard = ref(null);

const commentReportOpen = ref(false);
const commentReportTarget = ref(null);

function openForwardVideoModal(card) {
  if (!isAuthed.value) {
    pushToast("请先登录后使用转发", "info");
    return;
  }
  if (!card?.id) {
    pushToast("缺少视频信息", "error");
    return;
  }
  forwardVideoCard.value = card;
  forwardVideoOpen.value = true;
}

function openCommentReportModal(comment) {
  if (!isAuthed.value) {
    pushToast("请先登录后再举报", "info");
    return;
  }
  if (!comment?.id) {
    pushToast("缺少评论信息", "error");
    return;
  }
  if (currentUserId.value != null && String(comment.userId) === String(currentUserId.value)) {
    pushToast("不能举报自己的评论", "info");
    return;
  }
  if (isBlockedComment(comment)) {
    pushToast("该评论已被处理，无法举报", "info");
    return;
  }
  commentReportTarget.value = comment;
  commentReportOpen.value = true;
}

/** 搜索结果里的用户行：关注态读同一张乐观覆盖表 */
function searchUserRowIsSelf(u) {
  if (!u?.id || currentUserId.value == null) return false;
  return String(u.id) === String(currentUserId.value);
}

function searchUserRowIsFollowed(u) {
  if (!u?.id) return false;
  const key = String(u.id);
  if (Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)) {
    return followStateOverrideByAuthorId.value[key];
  }
  return Boolean(u.isFollow);
}

async function toggleSearchUserFollow(u) {
  if (!u?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再关注", "info");
    return;
  }
  const aid = String(u.id);
  if (currentUserId.value != null && aid === String(currentUserId.value)) {
    return;
  }
  const key = aid;
  const currently = Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)
    ? followStateOverrideByAuthorId.value[key]
    : Boolean(u.isFollow);
  try {
    if (currently) {
      await unfollowAuthor(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: false };
      patchIsFollowForAuthor(aid, false);
      pushToast("已取消关注", "success");
    } else {
      await followAuthor(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: true };
      patchIsFollowForAuthor(aid, true);
      pushToast("关注成功", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
}

/** 搜索全屏放大层：播放控制是命令式的，实现已移入 searchStore，这里保留同名薄封装供桥与生命周期调用 */
const searchExpandVideoUrl = () => searchStore.searchExpandVideoUrl();
const openSearchExpand = (card, index) => searchStore.openSearchExpand(card, index);
const closeSearchExpand = () => searchStore.closeSearchExpand();
const flushSearchExpandPlaybackResume = (reason) => searchStore.flushSearchExpandPlaybackResume(reason);
const toggleSearchExpandPlay = () => searchStore.toggleSearchExpandPlay();
const toggleSearchExpandCommentPanel = () => searchStore.toggleSearchExpandCommentPanel();
const toggleSearchExpandFollow = () => searchStore.toggleSearchExpandFollow();
const onSearchExpandFgPlay = () => searchStore.onSearchExpandFgPlay();
const onSearchExpandFgPause = () => searchStore.onSearchExpandFgPause();
const onSearchExpandFgSeeked = (e) => searchStore.onSearchExpandFgSeeked(e);
const onSearchExpandVideoTimeUpdate = (e) => searchStore.onSearchExpandVideoTimeUpdate(e);
const onSearchExpandVideoLoadedMetadata = (e) => searchStore.onSearchExpandVideoLoadedMetadata(e);
const onSearchExpandScrubInput = (e) => searchStore.onSearchExpandScrubInput(e);
const onSearchExpandVolumeInput = (e) => searchStore.onSearchExpandVolumeInput(e);
const onSearchExpandLikeClick = () => searchStore.onSearchExpandLikeClick();
const onSearchExpandDislikeClick = () => searchStore.onSearchExpandDislikeClick();
const onSearchExpandShareClick = () => searchStore.onSearchExpandShareClick();
const onSearchExpandForwardClick = () => searchStore.onSearchExpandForwardClick();
const searchExpandRequestPip = () => searchStore.searchExpandRequestPip();
const searchExpandRequestFullscreen = () => searchStore.searchExpandRequestFullscreen();
const searchExpandToggleMute = () => searchStore.searchExpandToggleMute();
const searchExpandPrev = () => searchStore.searchExpandPrev();
const searchExpandNext = () => searchStore.searchExpandNext();

function onShortsForwardClick() {
  openForwardVideoModal(shortsFeedCurrent.value);
}

watch(searchActive, (v) => {
  if (!v && searchExpandOpen.value) closeSearchExpand();
  if (!v && searchScrollLoadTimer) {
    clearTimeout(searchScrollLoadTimer);
    searchScrollLoadTimer = null;
  }
});

/** ShortsView 残余桥: 状态/函数/DOM ref 全量注入(ref 直传保持响应式) */
provide("shortsBridge", {
  activeCommentVideo,
  activeCommentVideoAllowsComment,
  activeMenuKey,
  cardAuthorAvatar,
  closeProfileFeed,
  closeSearchExpand,
  closeShortsCommentPanel,
  commentDisplayNickname,
  currentUserId,
  feedScope,
  followSubscribedFilterAuthorId,
  followingHasMore,
  followingLoading,
  followingRows,
  formatDetailCommentTime,
  formatSearchRelTime,
  formatShortsTime,
  isAuthed,
  isBlockedComment,
  isCommentLikeBusy,
  loadFollowingNextPage,
  loadMoreShortsPanelComments,
  loading,
  onAuthorAvatarClick,
  onDeleteShortsPanelComment,
  onSearchExpandDislikeClick,
  onSearchExpandFgPause,
  onSearchExpandFgPlay,
  onSearchExpandFgSeeked,
  onSearchExpandForwardClick,
  onSearchExpandLikeClick,
  onSearchExpandScrubInput,
  onSearchExpandShareClick,
  onSearchExpandVideoLoadedMetadata,
  onSearchExpandVideoTimeUpdate,
  onSearchExpandVolumeInput,
  onSearchTagNavigate,
  onShortsDislikeClick,
  onShortsFeedFgCanPlay,
  onShortsFeedFgDurationChange,
  onShortsFeedFgEnded,
  onShortsFeedFgLoadStart,
  onShortsFeedFgLoadedMetadata,
  onShortsFeedFgMediaError,
  onShortsFeedFgPause,
  onShortsFeedFgPlay,
  onShortsFeedFgSeeked,
  onShortsFeedFgTimeUpdate,
  onShortsFeedTouchEnd,
  onShortsFeedTouchStart,
  onShortsFeedWheel,
  onShortsForwardClick,
  onShortsLikeClick,
  onShortsPanelCommentEmojiPick,
  onShortsPanelCommentUserClick,
  onShortsScrubInput,
  onShortsVolumeInput,
  onTopbarAvatarError,
  openCommentReportModal,
  parseSearchTagsArray,
  profileFeedOpen,
  requestDeleteCurrentSelfVideo,
  searchActive,
  searchExpandBlurRef,
  searchExpandCard,
  searchExpandCommentOpen,
  searchExpandCurrentTime,
  searchExpandDuration,
  searchExpandDurationReady,
  searchExpandIndex,
  searchExpandIsFollowed,
  searchExpandIsPortrait,
  searchExpandIsSelf,
  searchExpandLikedDisplay,
  searchExpandMuted,
  searchExpandNext,
  searchExpandOpen,
  searchExpandPaused,
  searchExpandPlayerRef,
  searchExpandPrev,
  searchExpandRequestFullscreen,
  searchExpandRequestPip,
  searchExpandScrubDisplayValue,
  searchExpandScrubMax,
  searchExpandStageRef,
  searchExpandToggleMute,
  searchExpandVideoUrl,
  searchExpandVolume,
  searchListCommentOpen,
  selectFollowSidebarAll,
  selectFollowSidebarAuthor,
  selfVideoActionOpen,
  setShortsPanelReplyTarget,
  shortsCommentPanelOpen,
  shortsDurationReady,
  shortsFeedAuthorAvatar,
  shortsFeedAuthorName,
  shortsFeedBgRef,
  shortsFeedCenterRef,
  shortsFeedCurrent,
  shortsFeedCurrentTime,
  shortsFeedDescText,
  shortsFeedDuration,
  shortsFeedFgRef,
  shortsFeedIsPortrait,
  shortsFeedLikedDisplay,
  shortsFeedMetaTimeText,
  shortsFeedMuted,
  shortsFeedNext,
  shortsFeedPaused,
  shortsFeedPlayToken,
  shortsFeedPrev,
  shortsFeedTitleText,
  shortsFeedVideoBuffering,
  shortsFeedVideoSrc,
  shortsFeedVolume,
  shortsIsFollowedCurrent,
  shortsIsSelfCurrent,
  shortsPanelCommentDraft,
  shortsPanelCommentHasMore,
  shortsPanelCommentInputRef,
  shortsPanelCommentSubmitting,
  shortsPanelCommentsLoading,
  shortsPanelExpandedRoots,
  shortsPanelReplyTarget,
  shortsPanelThreadByRootId,
  shortsPanelTopComments,
  shortsRequestFullscreen,
  shortsRequestPip,
  shortsScrubDisplayValue,
  shortsScrubMax,
  shortsToggleMute,
  shortsTogglePlay,
  showProfileCenter,
  submitCommentLike,
  submitShortsPanelComment,
  toggleCurrentSelfVideoAllowComment,
  toggleCurrentSelfVideoVisibility,
  toggleSearchExpandCommentPanel,
  toggleSearchExpandFollow,
  toggleSearchExpandPlay,
  toggleSelfVideoActionMenu,
  toggleShortsCommentPanel,
  toggleShortsFollow,
  toggleShortsPanelThread,
  topbarAvatarSrc,
});
/** HomeView 残余桥: 短视频条滚动加载/进短视频流/卡片进详情仍依赖 App 级逻辑 */
provide("homeBridge", {
  showProfileCenter,
  homeShortsStripRef,
  onHomeShortsStripScroll,
  onHomeShortsStripWheel,
  onHomeShortCardClick,
  onVideoCardClick,
  onAuthorAvatarClick,
});
/** StudioView 残余桥: 依赖 App 级上传弹窗/删除确认/个人资料计算属性 */
provide("studioBridge", {
  displayNickname: selfDisplayNickname,
  topbarAvatarSrc,
  openUploadModal,
  onStudioRowRequestDelete,
});

// 创作中心 store 的跨域依赖接线(App 拥有 profile 加载与跨列表补丁)
registerStudioHooks({
  getUserId: () => currentUserId.value,
  ensureProfile: () => profileStore.loadUser(),
  patchFeedById: patchFeedVideoById,
});

function loadStudioVideos() {
  return studioStore.load();
}
function closeStudioRowMenu() {
  studioStore.closeRowMenu();
}

const historyStore2 = useHistoryStore();
function loadHistoryLongPage({ reset = false } = {}) {
  return historyStore2.loadLongPage({ reset });
}
function loadHistoryShortsPage({ reset = false } = {}) {
  return historyStore2.loadShortPage({ reset });
}
function toggleHistoryLongExpanded() {
  return historyStore2.toggleLongExpanded();
}
function toggleHistoryShortExpanded() {
  return historyStore2.toggleShortExpanded();
}
function onHistoryLongPrevPage() {
  return historyStore2.longPrevPage();
}
function onHistoryLongNextPage() {
  return historyStore2.longNextPage();
}
function onHistoryShortPrevPage() {
  return historyStore2.shortPrevPage();
}
function onHistoryShortNextPage() {
  return historyStore2.shortNextPage();
}
function onHistoryShortCardClick(card, index) {
  historyStore2.openShortCard(index);
}
function onHistoryLongCardClick(card, index) {
  historyStore2.openLongCard(index);
}
function openHistoryFeedFullscreen(sourceList, index, { forceVideoType = "" } = {}) {
  useShortsStore().openProfileFeed(sourceList, index, { forceVideoType });
}
const likedStore2 = useLikedStore();
function loadLikedPage({ reset = false } = {}) {
  return likedStore2.load({ reset });
}
registerLikedHooks({ getUserId: () => currentUserId.value });
registerHomeHooks({ reloadHome: () => loadModuleCards(primaryMenus[0]) });
registerShortsFeedPlayerRefs({
  fg: shortsFeedFgRef,
  bg: shortsFeedBgRef,
  center: shortsFeedCenterRef,
});
registerSearchExpandRefs({
  main: searchExpandPlayerRef,
  blur: searchExpandBlurRef,
  stage: searchExpandStageRef,
});
/** searchStore 的宿主依赖：搜索结果列表的内联预览观察器、短视频历史队列、跨列表补丁、转发弹窗都在 App */
registerSearchExpandHooks({
  teardownSearchVideoObserver: () => teardownSearchVideoObserver(),
  setupSearchVideoAutoplay: () => setupSearchVideoAutoplay(),
  enqueueShortsHistory: (videoIdLike, feedItem) => enqueueShortsHistoryByVideoId(videoIdLike, feedItem),
  flushShortsHistory: () => flushShortsHistoryQueue(),
  patchFeedVideoById: (id, patch) => patchFeedVideoById(id, patch),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
  openForwardVideo: (card) => openForwardVideoModal(card),
});
/** shortsStore 的宿主依赖：评论数变更跨多份列表打补丁，观看历史入队，菜单归属与播放代次仍在 App */
registerShortsHooks({
  patchFeedVideoById: (id, patch) => patchFeedVideoById(id, patch),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
  enqueueShortsHistory: (videoIdLike, feedItem) => enqueueShortsHistoryByVideoId(videoIdLike, feedItem),
  isShortsMenuActive: () => activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed",
  isSubscribedMenuActive: () => activeMenuKey.value === "subscribed",
  getPlayToken: () => shortsFeedPlayToken,
});

/** detailStore 的宿主依赖：打开详情要收起 App 级遮罩，跨列表关注/短视频历史/举报转发弹窗都在 App */
registerDetailHooks({
  pauseShortsFeed: () => pauseShortsFeedVideos({ muteForeground: true }),
  closeProfileFeedIfOpen: () => {
    if (profileFeedOpen.value) {
      closeProfileFeed();
    }
  },
  closeUploadModal: () => {
    uploadModalOpen.value = false;
  },
  enqueueShortsHistory: (videoIdLike, feedItem) => enqueueShortsHistoryByVideoId(videoIdLike, feedItem),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
  openCommentReport: (comment) => openCommentReportModal(comment),
  openForwardVideo: (card) => openForwardVideoModal(card),
  onSearchTagNavigate: (tag) => onSearchTagNavigate(tag),
});

/** profileStore 的宿主依赖：打开主页要收起 App 级遮罩，作品卡要进全屏短视频流 */
registerProfileHooks({
  prepareOpen: () => {
    searchActive.value = false;
    teardownSearchVideoObserver();
    if (activeMenuKey.value === "home") {
      saveHomeFeedSnapshot();
    }
    if (activeMenuKey.value === "shorts") {
      void flushShortsHistoryQueue();
    }
    if (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed") {
      pauseShortsFeedVideos({ muteForeground: true });
    }
    const returnCtx = captureProfileReturnContext();
    if (profileFeedOpen.value) {
      closeProfileFeed();
    }
    if (shortPlayerOpen.value) {
      closeShortPlayer();
    }
    showStudio.value = false;
    uploadModalOpen.value = false;
    if (!showVideoDetail.value) {
      clearVideoDetailFromAddressBar();
    }
    showVideoDetail.value = false;
    videoDetail.value = null;
    activeMenuLabel.value = "个人中心";
    return returnCtx;
  },
  pushHistory: (key) => {
    try {
      history.pushState({ myyoutubeProfile: key }, "", window.location.href);
    } catch {
      /* ignore */
    }
  },
  openWorkFeed: (item) => void openProfileWorkFeed(item),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
});

onMounted(async () => {
  registerGlobalToast(pushToast);
  registerChatNavigator(async (conversationId) => {
    const menu = exploreMenus.find((m) => m.key === "chat");
    if (menu) {
      await setActiveMenu(menu);
    }
    await openChatConversation(String(conversationId || ""));
  });
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
    if (searchExpandOpen.value) {
      void flushSearchExpandPlaybackResume("unload");
    }
    if (!showVideoDetail.value) return;
    void detailStore.flushPlaybackResume("unload");
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
    if (searchExpandOpen.value) {
      void flushSearchExpandPlaybackResume("unload");
    }
    if (!showVideoDetail.value) return;
    void detailStore.flushPlaybackResume("unload");
  });

  if (!isAuthed.value) {
    return;
  }
  await profileStore.loadUser();
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
        detailStore.scheduleAutoplayRetries();
        setTimeout(() => detailStore.scheduleAutoplayRetries(), 400);
        setTimeout(() => detailStore.scheduleAutoplayRetries(), 1200);
      });
    }
  } else {
    const state = loadUiStateFromSession();
    if (state?.showProfileCenter) {
      profileTargetUserId.value = state.profileTargetUserId ?? null;
      showProfileCenter.value = true;
      activeMenuLabel.value = "个人中心";
      await profileStore.loadUser({ withWorks: true });
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
    const menuKey = menuKeyForPath(route.path) || state?.activeMenuKey || homeMenu.key;
    const allMenus = [...primaryMenus, ...exploreMenus];
    const menu = allMenus.find((m) => m.key === menuKey) || homeMenu;
    homeCategoryKey.value = state?.homeCategoryKey || "all";
    homeSortKey.value = state?.homeSortKey || "RECOMMENDED";
    activeMenuKey.value = menu.key;
    activeMenuLabel.value = menu.label;
    await loadModuleCards(menu);
    if (menu.key === "shorts" || menu.key === "subscribed") {
      nextTick(() => {
        shortsFeedFgRef.value?.load();
        shortsFeedBgRef.value?.load();
      });
    }
  }
});

onBeforeUnmount(() => {
  teardownSearchVideoObserver();
  searchStore.detachSearchExpandEscape();
  if (searchSuggestBlurTimer) clearTimeout(searchSuggestBlurTimer);
  if (searchPromptDebounceTimer) clearTimeout(searchPromptDebounceTimer);
  shortsStore.clearShortsBufferingUITimer();
  stopNotifyUnreadPoll();
  stopNotifyWebSocket();
  stopCardHoverPreview();
  window.removeEventListener("resize", onWindowResize);
  window.removeEventListener("scroll", onWindowScroll);
  window.removeEventListener("keydown", onGlobalKeydown);
  window.removeEventListener("popstate", onWindowPopState);
  window.removeEventListener("pageshow", onWindowPageShow);
  window.removeEventListener(getAuthExpiredEventName(), onAuthExpired);
  void flushShortsHistoryQueue({ keepalive: true });
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
    <aside v-if="!showStudio && !showVideoDetail" class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <button class="icon-btn" title="折叠侧边栏" @click="sidebarCollapsed = !sidebarCollapsed">☰</button>
        <h1 class="brand">THYME</h1>
      </div>

      <nav class="nav-section">
        <template v-for="menu in primaryMenus" :key="menu.key">
        <button
          class="nav-item"
          :class="{ active: menu.key === activeMenuKey && !(showProfileCenter && isViewingSelf) }"
          @click="setActiveMenu(menu)"
        >
            <span class="nav-icon"><i :class="menu.icon"></i></span>
          <span class="nav-label">{{ menu.label }}</span>
        </button>
        </template>
        <button
          type="button"
          class="nav-item"
          :class="{ active: showProfileCenter && isViewingSelf }"
          title="我的主页"
          @click="onSidebarOpenMyProfile"
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
            :class="{ active: menu.key === activeMenuKey && !(showProfileCenter && isViewingSelf) }"
            @click="setActiveMenu(menu)"
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

    <div class="main-wrapper">
      <header class="topbar">
        <div class="search-box search-box--with-dropdown">
          <div class="search-box__input-wrap">
          <input
              ref="searchInputRef"
            v-model="searchKeyword"
            type="text"
              :placeholder="SEARCH_UI.placeholder"
            aria-label="搜索"
              autocomplete="off"
            @keyup.enter="onSearch"
              @focus="onSearchInputFocus"
              @blur="onSearchInputBlur"
            />
            <button
              v-show="searchKeyword.trim().length > 0"
              type="button"
              class="search-box__clear"
              aria-label="清空"
              title="清空"
              @mousedown.prevent
              @click="clearSearchKeyword"
            >
              <i class="ri-close-line" aria-hidden="true"></i>
            </button>
          </div>
          <button class="icon-btn" type="button" @click="onSearch">搜索</button>
          <div v-show="searchDropdownVisible" class="search-dropdown" role="listbox" @mousedown.prevent>
            <div v-if="searchPromptLoading || searchPromptRows.length" class="search-dropdown-block">
              <div class="search-dropdown-title">{{ SEARCH_UI.promptTitle }}</div>
              <div v-if="searchPromptLoading" class="search-dropdown-hint">加载中…</div>
              <button
                v-for="(line, idx) in searchPromptRows"
                :key="'p-' + idx + line"
                type="button"
                class="search-dropdown-item"
                role="option"
                @click="selectSearchSuggestion(line)"
              >
                <template v-for="(seg, j) in highlightSuggestParts(line)" :key="j">
                  <span v-if="seg.hl" class="search-dropdown-hi">{{ seg.t }}</span>
                  <span v-else>{{ seg.t }}</span>
                </template>
              </button>
            </div>
            <div v-if="searchHotLoading || searchHotRows.length" class="search-dropdown-block">
              <div class="search-dropdown-title">{{ SEARCH_UI.hotTitle }}</div>
              <div v-if="searchHotLoading" class="search-dropdown-hint">加载中…</div>
              <button
                v-for="(line, idx) in searchHotRows"
                :key="'h-' + idx + line"
                type="button"
                class="search-dropdown-item"
                role="option"
                @click="selectSearchSuggestion(line)"
              >
                {{ line }}
              </button>
            </div>
          </div>
        </div>

        <div class="top-actions">
          <button v-if="showVideoDetail" type="button" class="action-btn" @click="closeVideoDetail">返回</button>
          <button v-else-if="showStudio" type="button" class="action-btn" @click="closeStudio">返回</button>
          <button class="action-btn" @click="onCreate">{{ showStudio ? "上传视频" : "创建" }}</button>
          <div class="notify-shell">
            <div
              v-if="notifyPanelOpen"
              class="notify-backdrop"
              aria-hidden="true"
              @click="closeNotifyPanel"
            />
            <button
              type="button"
              class="action-btn notify-trigger"
              :class="{ 'notify-trigger--active': notifyPanelOpen }"
              aria-haspopup="dialog"
              :aria-expanded="String(notifyPanelOpen)"
              @click.stop="toggleNotifyPanel"
            >
              通知
              <span v-if="notifyUnreadCount > 0" class="notify-badge" :title="`未读 ${notifyUnreadCount}`">
                {{ notifyUnreadCount > 99 ? "99+" : notifyUnreadCount }}
              </span>
            </button>
            <div
              v-if="notifyPanelOpen"
              class="notify-panel"
              role="dialog"
              aria-label="通知列表"
              @click.stop
            >
              <div class="notify-panel__head">
                <div class="notify-panel__title-wrap">
                  <span class="notify-panel__title">通知</span>
                  <span v-if="notifyTotal > 0" class="notify-panel__sub">共 {{ notifyTotal }} 条</span>
                </div>
                <button type="button" class="notify-panel__close" aria-label="关闭" @click="closeNotifyPanel">
                  <i class="ri-close-line" aria-hidden="true"></i>
                </button>
              </div>
              <p v-if="notifyErrorMsg" class="notify-panel__err">{{ notifyErrorMsg }}</p>
              <div v-if="notifyList.length" class="notify-panel__list">
                <div
                  v-for="item in notifyList"
                  :key="item.id"
                  class="notify-row"
                  :class="{ 'notify-row--unread': !item.isRead }"
                >
                  <button type="button" class="notify-row__main" @click="onNotifyItemClick(item)">
                    <img
                      :src="item.senderAvatarUrl || profileAvatar"
                      alt=""
                      class="notify-row__avatar"
                      @error="onTopbarAvatarError"
                    />
                    <div class="notify-row__body">
                      <div class="notify-row__meta">
                        <span class="notify-row__nick">{{ item.senderNickname }}</span>
                        <span class="notify-row__type">{{ item.typeLabel }}</span>
                        <span v-if="!item.isRead" class="notify-row__unread-badge" title="未读">未读</span>
                      </div>
                      <p v-if="item.content" class="notify-row__content">{{ item.content }}</p>
                      <time class="notify-row__time">{{ formatNotifyTime(item.createTime) }}</time>
                    </div>
                  </button>
                  <button
                    type="button"
                    class="notify-row__delete"
                    title="删除"
                    aria-label="删除此通知"
                    @click.stop="onNotifyDelete(item)"
                  >
                    <i class="ri-delete-bin-line" aria-hidden="true"></i>
                  </button>
                </div>
                <button
                  v-if="notifyPages > 0 && notifyPageNo < notifyPages"
                  type="button"
                  class="notify-load-more"
                  :disabled="notifyLoading"
                  @click="loadMoreNotifications"
                >
                  {{ notifyLoading ? "加载中…" : "加载更多" }}
                </button>
              </div>
              <p v-else-if="!notifyLoading" class="notify-panel__empty">暂无通知</p>
              <p v-else class="notify-panel__loading">加载中…</p>
            </div>
          </div>
          <button
            type="button"
            class="avatar-btn avatar-btn--img"
            title="个人中心"
            aria-label="个人中心"
            @click="openProfile"
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
          :class="t.notify ? 'toast-item--live-notify' : `toast-item--${t.type}`"
        >
          <template v-if="t.notify">
            <div
              class="toast-notify-main"
              :class="{
                'toast-notify-main--clickable':
                  String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
                  (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
                  (t.notify.videoId != null && t.notify.videoId !== '') ||
                  (t.notify.notificationId != null && t.notify.notificationId !== ''),
              }"
              :role="
                String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
                (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
                (t.notify.videoId != null && t.notify.videoId !== '') ||
                (t.notify.notificationId != null && t.notify.notificationId !== '')
                  ? 'button'
                  : undefined
              "
              :tabindex="
                String(t.notify.notifyKind || '') === 'CHAT_NOTICE' ||
                (t.notify.conversationId != null && String(t.notify.conversationId).trim() !== '') ||
                (t.notify.videoId != null && t.notify.videoId !== '') ||
                (t.notify.notificationId != null && t.notify.notificationId !== '')
                  ? 0
                  : undefined
              "
              @click="onNotifyToastClick(t)"
              @keydown.enter.prevent="onNotifyToastClick(t)"
              @keydown.space.prevent="onNotifyToastClick(t)"
            >
              <img
                :src="t.notify.avatarUrl || profileAvatar"
                alt=""
                class="toast-notify-avatar"
                @error="(e) => (e.target.src = profileAvatar)"
              />
              <div class="toast-notify-copy">
                <div class="toast-notify-title">{{ t.notify.title }}</div>
                <div v-if="t.notify.content" class="toast-notify-body">{{ t.notify.content }}</div>
                <div v-else class="toast-notify-body toast-notify-body--muted">（无正文预览）</div>
                <span v-if="String(t.notify.notifyKind || '') === 'CHAT_NOTICE'" class="toast-notify-cta">
                  点击查看说明 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
                </span>
                <span
                  v-else-if="t.notify.conversationId != null && String(t.notify.conversationId).trim() !== ''"
                  class="toast-notify-cta"
                >
                  点击打开聊天 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
                </span>
                <span
                  v-else-if="t.notify.videoId != null && t.notify.videoId !== ''"
                  class="toast-notify-cta"
                >
                  点击查看视频 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
                </span>
                <span
                  v-else-if="t.notify.notificationId != null && t.notify.notificationId !== ''"
                  class="toast-notify-cta"
                >
                  点击标记已读 <i class="ri-arrow-right-s-line" aria-hidden="true"></i>
                </span>
              </div>
            </div>
            <button
              type="button"
              class="toast-item__close toast-item__close--glass"
              aria-label="关闭"
              @click.stop="removeToast(t.id)"
            >
              <i class="ri-close-line"></i>
            </button>
          </template>
          <template v-else>
            <span class="toast-item__text">{{ t.message }}</span>
            <button type="button" class="toast-item__close" aria-label="关闭提示" @click="removeToast(t.id)">
              <i class="ri-close-line"></i>
            </button>
          </template>
        </div>
      </div>

      <main
        ref="contentScrollRef"
        v-show="!showVideoDetail"
        class="content"
        @scroll.passive="onContentScroll"
        :class="{
          'content--shorts':
            !showProfileCenter &&
            (activeMenuKey === 'shorts' || activeMenuKey === 'subscribed') &&
            !showStudio &&
            !showVideoDetail,
          'content--studio': showStudio,
          'content--search': searchActive && !showStudio,
          'content--music':
            !showProfileCenter && activeMenuKey === 'music' && !showStudio && !searchActive,
          'content--chat':
            !showProfileCenter && activeMenuKey === 'chat' && !showStudio && !searchActive,
        }"
      >
        <!-- 创作中心（YouTube Studio 风格） -->
        <StudioView v-if="showStudio" />

        <template v-else>
        <SearchView v-if="searchActive" :scroll-ref="searchFeedScrollRef" />
        <ShortsView />
        <template v-if="!searchActive">

        <ProfileCenterView v-if="showProfileCenter" />

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

        <template v-if="!showProfileCenter && activeMenuKey !== 'shorts' && activeMenuKey !== 'subscribed'">
          <HistoryView v-if="activeMenuKey === 'history'" />

          <LikedView v-else-if="activeMenuKey === 'liked'" />

          <section v-else-if="activeMenuKey === 'music'">
            <MusicView />
          </section>

          <section v-else-if="activeMenuKey === 'chat'" class="explore-chat-page">
            <ChatView
              :authed="isAuthed"
              :self-user-id="currentUserId"
              @open-video="({ videoId }) => openVideoDetail({ id: videoId })"
              @open-user="({ userId }) => openOtherUserProfile(userId)"
            />
          </section>

          <HomeView v-else-if="activeMenuKey === 'home'" />
        </template>
        </template>
        </template>
      </main>

      <VideoDetailView v-if="showVideoDetail" />

      <input
        ref="uploadFileInputRef"
        type="file"
        accept="video/*"
        class="visually-hidden"
        aria-hidden="true"
        @change="onUploadInputOrDrop"
      />
      <input
        ref="uploadSlideshowImagesInputRef"
        type="file"
        accept="image/*"
        :multiple="true"
        class="visually-hidden"
        aria-hidden="true"
        @change="onSlideshowImagesInputChange"
      />
      <input
        ref="uploadSlideshowAudioInputRef"
        type="file"
        accept="audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg"
        class="visually-hidden"
        aria-hidden="true"
        @change="onSlideshowAudioInputChange"
      />

      <div
        v-if="selfVideoDeleteConfirmOpen"
        class="modal-overlay shorts-delete-modal-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="确认删除视频"
        @click.self="cancelDeleteCurrentSelfVideo"
      >
        <div class="modal-card shorts-delete-modal" @click.stop>
          <h3>删除视频</h3>
          <p class="shorts-delete-modal-text">确定删除该视频吗？删除后将无法恢复。</p>
          <div class="shorts-delete-modal-actions">
            <button type="button" class="action-btn" @click="cancelDeleteCurrentSelfVideo">取消</button>
            <button type="button" class="action-btn shorts-delete-btn" @click="confirmDeleteCurrentSelfVideo">确认删除</button>
          </div>
        </div>
      </div>

      <div
        v-if="uploadModalOpen"
        class="modal-overlay upload-modal-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="上传视频"
      >
        <form
          class="upload-modal"
          :class="{ 'upload-modal--slideshow-wide': uploadMode === 'slideshow' }"
          @submit.prevent="submitUploadVideo"
          @click.stop
        >
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
          <div class="upload-mode-tabs" role="tablist" aria-label="上传方式">
            <button
              type="button"
              role="tab"
              class="upload-mode-tab"
              :class="{ 'upload-mode-tab--active': uploadMode === 'video' }"
              :disabled="uploadSubmitting"
              :aria-selected="uploadMode === 'video'"
              @click="setUploadMode('video')"
            >
              视频文件
            </button>
            <button
              type="button"
              role="tab"
              class="upload-mode-tab"
              :class="{ 'upload-mode-tab--active': uploadMode === 'slideshow' }"
              :disabled="uploadSubmitting"
              :aria-selected="uploadMode === 'slideshow'"
              @click="setUploadMode('slideshow')"
            >
              图片幻灯片
            </button>
          </div>
          <div
            class="upload-modal-main"
            :class="{ 'upload-modal-main--split': uploadMode === 'slideshow' }"
          >
          <div
            class="upload-modal-drop"
            :class="{ 'upload-modal-drop--slide-col': uploadMode === 'slideshow' }"
            @dragover.prevent
            @drop.prevent="onUploadModalDrop"
          >
            <template v-if="uploadMode === 'video'">
              <div class="upload-modal-icon">
                <i class="ri-upload-cloud-2-line"></i>
              </div>
              <p class="upload-modal-main-text">将要上传的视频文件拖放到此处</p>
              <button type="button" class="upload-modal-select" :disabled="uploadSubmitting" @click="triggerUploadPick">
                选择文件
              </button>
              <p v-if="uploadPickedFile" class="upload-picked-file">已选：{{ uploadPickedFile.name }}</p>
              <p v-if="uploadPickedDuration" class="upload-progress-text">时长约 {{ uploadPickedDuration }} 秒</p>
            </template>
            <template v-else>
              <div class="upload-modal-icon">
                <i class="ri-image-2-line"></i>
              </div>
              <p class="upload-modal-main-text">先按顺序添加图片（可拖入）；再选背景音乐，BGM 时长会与幻灯片总长对齐</p>
              <div class="upload-slideshow-actions">
                <button
                  type="button"
                  class="upload-modal-select"
                  :disabled="uploadSubmitting"
                  title="在文件选择框中可一次多选：Windows 用 Ctrl/Shift；Mac 用 Command/Shift"
                  @click="triggerSlideshowImagesPick"
                >
                  添加图片（可多选）
                </button>
                <template v-if="uploadSlideshowImages.length">
                  <button
                    type="button"
                    class="upload-modal-select upload-modal-select--ghost"
                    :disabled="uploadSubmitting"
                    @click="openSlideshowMusicPicker"
                  >
                    云端音乐（截取片段）
                  </button>
                  <button
                    type="button"
                    class="upload-modal-select upload-modal-select--ghost"
                    :disabled="uploadSubmitting"
                    @click="triggerSlideshowAudioPick"
                  >
                    本地上传音频
                  </button>
                </template>
                <button
                  v-if="uploadSlideshowImages.length"
                  type="button"
                  class="upload-modal-select upload-modal-select--ghost"
                  :disabled="uploadSubmitting"
                  @click="clearSlideshowImages"
                >
                  清空图片
                </button>
                <button
                  v-if="uploadSlideshowAudioFile"
                  type="button"
                  class="upload-modal-select upload-modal-select--ghost"
                  :disabled="uploadSubmitting"
                  @click="clearSlideshowAudio"
                >
                  移除音频
                </button>
              </div>
              <ul v-if="uploadSlideshowImages.length" class="upload-slideshow-list">
                <li v-for="(f, idx) in uploadSlideshowImages" :key="`${idx}-${f.name}-${f.size}`" class="upload-slideshow-item">
                  <span class="upload-slideshow-item-name">{{ idx + 1 }}. {{ f.name }}</span>
                  <button type="button" class="upload-slideshow-remove" :disabled="uploadSubmitting" @click="removeSlideshowImage(idx)">
                    移除
                  </button>
                </li>
              </ul>
              <p v-if="uploadSlideshowAudioFile" class="upload-slideshow-audio-line">音频：{{ uploadSlideshowAudioFile.name }}</p>
            </template>
            <p v-if="uploadSubmitting" class="upload-progress-text">
              {{ uploadMode === "slideshow" ? "进度" : "上传进度" }}：{{ uploadProgress }}%
            </p>
          </div>

          <div
            class="upload-meta-grid"
            :class="{ 'upload-meta-grid--slide-col': uploadMode === 'slideshow' }"
          >
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
              {{
                uploadSubmitting
                  ? uploadMode === "slideshow"
                    ? "处理中…"
                    : "发布中…"
                  : uploadMode === "slideshow"
                    ? "生成并发布"
                    : "上传并发布"
              }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <SlideshowMusicPicker
      :open="uploadMusicPickerOpen"
      :target-segment-sec="slideshowBgmTargetSec"
      @close="uploadMusicPickerOpen = false"
      @picked="onSlideshowCloudMusicPicked"
    />
    <MusicPlayerAudioHost />
    <MusicMiniPlayer :show="activeMenuKey !== 'music' && activeMenuKey !== 'chat'" />
    <ChatFloatingRail
      :authed="isAuthed"
      :chat-page-active="activeMenuKey === 'chat'"
      :narrow-peek="activeMenuKey === 'shorts' || activeMenuKey === 'subscribed'"
      :suppress="chatRailSuppress"
    />
    <ChatForwardVideoModal
      v-model="forwardVideoOpen"
      :self-user-id="currentUserId"
      :video-id="forwardVideoCard?.id ?? null"
      :video-title="forwardVideoCard?.title ?? ''"
      :video-cover-url="forwardVideoCard?.coverUrl || forwardVideoCard?.cover || forwardVideoCard?.avatarUrl || ''"
      @sent="() => refreshConversationList(true)"
    />
    <CommentReportModal
      v-model="commentReportOpen"
      :comment="commentReportTarget"
      :self-user-id="currentUserId"
      @reported="() => pushToast('已提交举报', 'success')"
    />

    <Teleport to="body">
      <div
        v-if="showLoginLoadingOverlay"
        class="login-success-loading-overlay"
        aria-busy="true"
        aria-live="polite"
      >
        <LoginLoadingCubes @finished="onLoginCubesAnimFinished" />
      </div>
    </Teleport>
  </div>
</template>
