<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useRoute, useRouter } from "vue-router";
import { menuKeyForPath, menuPathFor } from "./router";
import { useAuthStore } from "./stores/authStore";
import { useSearchStore, SEARCH_PAGE_SIZE, SEARCH_UI } from "./stores/searchStore";
import { useHistoryStore } from "./stores/historyStore";
import { useLikedStore, LIKED_PAGE_SIZE } from "./stores/likedStore";
import { useHomeStore } from "./stores/homeStore";
import { useShortsStore } from "./stores/shortsStore";
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
  fetchAuthorVideos,
  fetchOtherUserVideos,
  fetchMyVideos,
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
  setMyVideoPrivate,
  setMyVideoPublic,
  openMyVideoComment,
  closeMyVideoComment,
} from "./api/videoApi";
import {
  fetchSearchHot,
  fetchSearchPrompt,
  fetchSearchTag,
  fetchSearchUser,
  fetchSearchVideo,
} from "./api/searchApi";
import { fetchCommentPage, publishComment as publishCommentApi, deleteComment as deleteCommentApi } from "./api/commentApi";
import { reportVideoBehavior } from "./api/behaviorApi";
import { STORAGE_BIZ_WORK } from "./api/otherApi";
import { uploadFileWithPresign } from "./utils/presignUpload.js";
import { VIDEO_CATEGORY_CHIPS } from "./constants/videoCategories";
import {
  getUserInfo,
  getUserInfoById,
  updateUserAvatar,
  updateUserBgImage,
  updateUserProfile,
} from "./api/userApi";
import {
  likeVideo as likeVideoApi,
  likeComment as likeCommentApi,
  cancelLikeComment as cancelLikeCommentApi,
  cancelLikeVideo as cancelLikeVideoApi,
  dislikeVideo as dislikeVideoApi,
  fetchLikedVideosPage,
  fetchVideoLikedStatus,
} from "./api/likeApi";
import {
  fetchMyFansPage,
  fetchMyFollowingPage,
  followAuthor,
  unfollowAuthor,
  isFollowed,
  removeFan,
} from "./api/followApi";
import {
  fetchNotificationPage,
  markNotificationRead,
  deleteNotification,
  fetchUnreadNotificationCount,
  NOTIFICATION_TYPE_LABELS,
} from "./api/notificationApi";
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
import appIco from "../static/狗.png?url";
import MusicView from "./views/MusicView.vue";
import SearchView from "./views/SearchView.vue";
import MusicPlayerAudioHost from "./components/MusicPlayerAudioHost.vue";
import MusicMiniPlayer from "./components/MusicMiniPlayer.vue";
import ChatView from "./views/ChatView.vue";
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

/** 过渡桥接: SearchView 依赖的交互函数仍在本组件, 拆分完成前经 provide 注入 */
provide("searchBridge", {
  selectSearchTab,
  onSearchTagNavigate,
  toggleSearchUserFollow,
  onSearchUserRowActivate,
  searchUserRowIsSelf,
  searchUserRowIsFollowed,
  onAuthorAvatarClick,
  resolveVideoUrl,
  cardAuthorAvatar,
  cardAuthorLabel,
  parseSearchTagsArray,
  formatSearchRelTime,
  formatShortsTime,
  formatHomeShortStat,
  searchFeedBindMainVideo,
  searchFeedBindMediaWrap,
  searchFeedListMuted,
  searchFeedListVolValue,
  searchFeedListScrubMax,
  searchFeedListScrubDisplay,
  searchFeedListDurationReady,
  searchFeedListCurrent,
  searchFeedListDuration,
  searchFeedListShowPaused,
  onSearchFeedListScrubInput,
  searchFeedListToggleMute,
  onSearchFeedListVolumeInput,
  searchFeedListRequestPip,
  searchFeedListRequestFullscreen,
  searchFeedListTogglePlay,
  onSearchFeedListTimeUpdate,
  onSearchFeedListLoadedMetadata,
  onSearchFeedListSeeked,
  onSearchFeedListVideoPlay,
  onSearchFeedListVideoPause,
  onSearchFeedMainVideoClick,
  onSearchFeedLikeClick,
  onSearchFeedForwardClick,
  openSearchListCommentPanel,
});
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
  searchExpandOpen,
  searchExpandCard,
  searchExpandIndex,
  searchExpandCommentOpen,
  searchListCommentOpen,
  searchListCommentCard,
  searchFeedProgressMap,
  searchFeedLikeBusyId,
  searchFeedPausedMap,
  searchFeedListPrefs,
  searchFeedMainVideoById,
  searchFeedMediaWrapById,
  searchExpandCurrentTime,
  searchExpandDuration,
  searchExpandPaused,
  searchExpandAutoplayPending,
  searchExpandMuted,
  searchExpandVolume,
  searchExpandLikeBusy,
  searchExpandIsPortrait,
} = storeToRefs(searchStore);
const searchInputRef = ref(null);
const searchFeedScrollRef = ref(null);
const contentScrollRef = ref(null);
const searchExpandPlayerRef = ref(null);
const searchExpandBlurRef = ref(null);
const searchExpandStageRef = ref(null);

/** 搜索页文案与分页常量已移至 stores/searchStore.js */

let searchSuggestBlurTimer = null;
let searchPromptDebounceTimer = null;
let searchVideoObserver = null;
let searchScrollLoadTimer = null;

/** 搜索列表进度条：节流 timeupdate，避免每帧整表重绘卡死 */
const searchFeedProgressThrottleTs = new Map();
let lastSearchExpandLikeAt = 0;
let searchExpandEscapeHandler = null;
/** 搜索全屏长视频：续播上报节流（与详情页长视频一致，写入 PUT /videos/playback/resume） */
let searchExpandResumeTickCount = 0;
let searchExpandResumeInFlight = false;
let searchExpandResumeLastSentAtMs = 0;

function searchExpandVideoUrl() {
  const card = searchExpandCard.value;
  if (!card) return "";
  return resolveVideoUrl(card);
}

function openSearchExpand(card, index) {
  searchListCommentOpen.value = false;
  searchListCommentCard.value = null;
  searchExpandResumeTickCount = 0;
  searchExpandResumeInFlight = false;
  searchExpandResumeLastSentAtMs = 0;
  searchExpandCard.value = card;
  searchExpandIndex.value = index;
  searchExpandOpen.value = true;
  teardownSearchVideoObserver();
  if (searchExpandEscapeHandler) {
    window.removeEventListener("keydown", searchExpandEscapeHandler);
    searchExpandEscapeHandler = null;
  }
  searchExpandEscapeHandler = (e) => {
    if (e.key === "Escape") closeSearchExpand();
  };
  window.addEventListener("keydown", searchExpandEscapeHandler);
  nextTick(() => {
    document.querySelectorAll(".search-feed-slide .search-feed-video").forEach((v) => {
      try {
        v.pause();
      } catch {
        /* ignore */
      }
    });
    const main = searchExpandPlayerRef.value;
    const blur = searchExpandBlurRef.value;
    searchExpandCurrentTime.value = 0;
    searchExpandDuration.value = 0;
    searchExpandPaused.value = true;
    searchExpandIsPortrait.value = true;
    if (main) {
      try {
        main.volume = searchExpandVolume.value;
        main.muted = searchExpandMuted.value;
      } catch {
        /* ignore */
      }
    }
    if (blur) blur.play().catch(() => {});
    if (main) main.play().catch(() => {});
  });
}

function onSearchExpandFgPlay() {
  searchExpandPaused.value = false;
  searchExpandBlurRef.value?.play?.().catch(() => {});
  /** 搜索列表内预览不写入历史；仅在全屏放大且实际开始播放时上报短视频观看（PUT /videos/shorts/play） */
  if (searchActive.value) {
    const card = searchExpandCard.value;
    if (card) {
      enqueueShortsHistoryByVideoId(card.id, card);
    }
  }
}

function onSearchExpandFgPause() {
  if (searchExpandAutoplayPending.value) {
    return;
  }
  searchExpandPaused.value = true;
  try {
    searchExpandBlurRef.value?.pause?.();
  } catch {
    /* ignore */
  }
  if (isLongSearchExpandVideo()) {
    void flushSearchExpandPlaybackResume("pause");
  }
}

function isLongSearchExpandVideo() {
  return isLongVideoItem(searchExpandCard.value);
}

function getSearchExpandCurrentPlayTimeMs() {
  const el = searchExpandPlayerRef.value;
  if (!el) return 0;
  const ms = Math.floor((Number(el.currentTime || 0) || 0) * 1000);
  return ms > 0 ? ms : 0;
}

function getSearchExpandPlaybackPayload(playTimeMs) {
  const userId = currentUserId.value;
  const videoId = searchExpandCard.value?.id;
  if (!userId || userId === "" || !videoId) return null;
  if (!Number.isFinite(Number(playTimeMs))) return null;
  return {
    videoId,
    playTime: playTimeMs,
    userId,
  };
}

function flushSearchExpandPlaybackResume(reason) {
  if (!isLongSearchExpandVideo()) return Promise.resolve();
  const player = searchExpandPlayerRef.value;
  if (!player) return Promise.resolve();

  if (reason !== "pause" && player.paused) {
    return Promise.resolve();
  }

  const playTimeMs = getSearchExpandCurrentPlayTimeMs();
  if (
    reason !== "pause" &&
    reason !== "switch" &&
    reason !== "leave" &&
    reason !== "unload" &&
    playTimeMs <= 0
  ) {
    return Promise.resolve();
  }

  const payload = getSearchExpandPlaybackPayload(playTimeMs);
  if (!payload) return Promise.resolve();

  const now = Date.now();
  if (reason === "tick" && now - searchExpandResumeLastSentAtMs < 8_000) {
    return Promise.resolve();
  }
  if (searchExpandResumeInFlight) {
    return Promise.resolve();
  }
  searchExpandResumeInFlight = true;
  searchExpandResumeLastSentAtMs = now;

  const keepalive = reason === "unload" || reason === "leave";
  return sendPlaybackResumePut(payload, { keepalive }).finally(() => {
    searchExpandResumeInFlight = false;
  });
}

function closeSearchExpand() {
  if (searchExpandEscapeHandler) {
    window.removeEventListener("keydown", searchExpandEscapeHandler);
    searchExpandEscapeHandler = null;
  }
  searchExpandCommentOpen.value = false;
  void flushSearchExpandPlaybackResume("leave");
  searchExpandResumeTickCount = 0;
  searchExpandOpen.value = false;
  searchExpandCard.value = null;
  try {
    searchExpandPlayerRef.value?.pause?.();
  } catch {
    /* ignore */
  }
  try {
    searchExpandBlurRef.value?.pause?.();
  } catch {
    /* ignore */
  }
  void flushShortsHistoryQueue();
  nextTick(() => {
    if (searchActive.value) setupSearchVideoAutoplay();
  });
}

function toggleSearchExpandPlay() {
  const v = searchExpandPlayerRef.value;
  if (!v) return;
  if (v.paused) v.play().catch(() => {});
  else v.pause();
}

function onSearchExpandVideoTimeUpdate(e) {
  const v = e.target;
  searchExpandCurrentTime.value = Number(v.currentTime) || 0;
  if (!searchExpandAutoplayPending.value) {
    searchExpandPaused.value = v.paused;
  }
  const blur = searchExpandBlurRef.value;
  if (blur && v && Math.abs(blur.currentTime - v.currentTime) > 0.2) {
    blur.currentTime = v.currentTime;
  }
  if (isLongSearchExpandVideo() && v && !v.paused) {
    searchExpandResumeTickCount += 1;
    if (searchExpandResumeTickCount >= 30) {
      searchExpandResumeTickCount = 0;
      void flushSearchExpandPlaybackResume("tick");
    }
  }
}

function onSearchExpandFgSeeked(e) {
  const fg = e.target;
  const blur = searchExpandBlurRef.value;
  if (blur && fg) {
    blur.currentTime = fg.currentTime;
  }
}

function onSearchExpandVideoLoadedMetadata(e) {
  const v = e.target;
  searchExpandIsPortrait.value =
    v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
  searchExpandDuration.value = readVideoDurationFromVideoEl(v);
}

function onSearchExpandScrubInput(e) {
  const v = searchExpandPlayerRef.value;
  if (!v) return;
  const t = Number(e.target.value);
  if (!Number.isFinite(t)) return;
  v.currentTime = t;
  searchExpandCurrentTime.value = t;
}

async function searchExpandRequestPip() {
  const v = searchExpandPlayerRef.value;
  if (!v) return;
  try {
    if (document.pictureInPictureElement === v) {
      await document.exitPictureInPicture();
    } else if (v.requestPictureInPicture) {
      await v.requestPictureInPicture();
    }
  } catch {
    /* ignore */
  }
}

async function searchExpandRequestFullscreen() {
  const el = searchExpandStageRef.value;
  if (!el) return;
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

function searchExpandToggleMute() {
  const v = searchExpandPlayerRef.value;
  if (!v) return;
  v.muted = !v.muted;
  searchExpandMuted.value = v.muted;
}

function onSearchExpandVolumeInput(e) {
  const v = searchExpandPlayerRef.value;
  if (!v) return;
  const vol = Number(e.target.value);
  v.volume = vol;
  v.muted = vol === 0;
  searchExpandVolume.value = vol;
  searchExpandMuted.value = v.muted;
}

function syncSearchExpandPlayerAfterSwitch() {
  const main = searchExpandPlayerRef.value;
  const blur = searchExpandBlurRef.value;
  searchExpandAutoplayPending.value = true;
  try {
    main?.load?.();
    blur?.load?.();
  } catch {
    /* ignore */
  }
  searchExpandCurrentTime.value = 0;
  searchExpandDuration.value = 0;
  searchExpandIsPortrait.value = true;
  nextTick(() => {
    try {
      main.volume = searchExpandVolume.value;
      main.muted = searchExpandMuted.value;
    } catch {
      /* ignore */
    }
    blur?.play?.().catch(() => {});
    const p = main?.play?.();
    if (p && typeof p.then === "function") {
      p.then(
        () => {
          searchExpandAutoplayPending.value = false;
        },
        () => {
          searchExpandAutoplayPending.value = false;
          searchExpandPaused.value = true;
        }
      );
    } else {
      searchExpandAutoplayPending.value = false;
    }
  });
}

function searchExpandPrev() {
  const list = searchFeedVideoList.value;
  const i = searchExpandIndex.value;
  if (i <= 0 || !list.length) return;
  void flushSearchExpandPlaybackResume("switch");
  searchExpandResumeTickCount = 0;
  searchExpandCard.value = list[i - 1];
  searchExpandIndex.value = i - 1;
  searchExpandCommentOpen.value = false;
  nextTick(() => syncSearchExpandPlayerAfterSwitch());
}

function searchExpandNext() {
  const list = searchFeedVideoList.value;
  const i = searchExpandIndex.value;
  if (!list.length || i >= list.length - 1) return;
  void flushSearchExpandPlaybackResume("switch");
  searchExpandResumeTickCount = 0;
  searchExpandCard.value = list[i + 1];
  searchExpandIndex.value = i + 1;
  searchExpandCommentOpen.value = false;
  nextTick(() => syncSearchExpandPlayerAfterSwitch());
}

async function onSearchExpandLikeClick() {
  const cur = searchExpandCard.value;
  if (!cur?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再点赞", "info");
    return;
  }
  const t = Date.now();
  if (t - lastSearchExpandLikeAt < 300) return;
  lastSearchExpandLikeAt = t;
  if (searchExpandLikeBusy.value) return;
  const vid = parseVideoIdForApi(cur.id);
  if (vid == null) return;
  searchExpandLikeBusy.value = true;
  try {
    const wasLiked = Boolean(cur.isLike);
    if (wasLiked) {
      await cancelLikeVideoApi(vid);
      patchFeedVideoById(cur.id, { isLike: false, likeCount: Math.max(0, Number(cur.likeCount ?? 0) - 1) });
    } else {
      await likeVideoApi(vid);
      patchFeedVideoById(cur.id, {
        isLike: true,
        likeCount: Number(cur.likeCount ?? 0) + 1,
      });
    }
    const raw = await fetchVideoDetail(vid);
    const merged = normalizeVideoDetail(raw);
    if (merged && merged.likeCount != null) {
      patchFeedVideoById(cur.id, { likeCount: merged.likeCount });
    }
    void reportBehaviorSilently(cur.id, "LIKE", Number(searchExpandCurrentTime.value || 0) * 1000);
  } catch (error) {
    pushToast(error.message || "点赞失败", "error");
  } finally {
    searchExpandLikeBusy.value = false;
  }
}

async function onSearchExpandDislikeClick() {
  const cur = searchExpandCard.value;
  if (!cur?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  const vid = parseVideoIdForApi(cur.id);
  if (vid == null) return;
  try {
    await dislikeVideoApi(vid);
    void reportBehaviorSilently(cur.id, "DISLIKE", Number(searchExpandCurrentTime.value || 0) * 1000);
    pushToast("已标记为不感兴趣", "success");
  } catch (error) {
    pushToast(error.message || "操作失败", "error");
  }
}

async function toggleSearchExpandFollow() {
  const c = searchExpandCard.value;
  if (!c?.authorId) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再关注", "info");
    return;
  }
  const aid = String(c.authorId);
  if (currentUserId.value != null && aid === String(currentUserId.value)) {
    return;
  }
  const key = aid;
  const currently = Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)
    ? followStateOverrideByAuthorId.value[key]
    : Boolean(c.isFollow);
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

async function onSearchExpandShareClick() {
  const cur = searchExpandCard.value;
  const url = typeof window !== "undefined" ? window.location.href : "";
  try {
    if (navigator.share && cur?.title) {
      await navigator.share({ title: cur.title, url });
    } else if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url);
      pushToast("链接已复制", "success");
    } else {
      pushToast(url || "无法分享", "info");
    }
  } catch {
    /* 用户取消或不可用 */
  }
}

const forwardVideoOpen = ref(false);
const forwardVideoCard = ref(null);

const commentReportOpen = ref(false);
const commentReportTarget = ref(null);

function isBlockedComment(comment) {
  const stRaw = comment?.status ?? comment?.commentStatus ?? comment?.comment_status;
  const st = String(stRaw ?? "").trim().toUpperCase();
  if (st === "BLOCKED" || st === "BANNED") return true;
  if (stRaw === 2 || stRaw === "2") return true;
  const text = String(comment?.content || "");
  return text.includes("评论违规") || text.includes("已被处理");
}

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

function onSearchExpandForwardClick() {
  openForwardVideoModal(searchExpandCard.value);
}

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

const searchFeedVideoList = computed(() => {
  if (searchTab.value === "tag") return searchTagRows.value;
  if (searchTab.value === "all" || searchTab.value === "video") return searchVideoRows.value;
  return [];
});

const searchVerticalLoading = computed(() => {
  if (searchTab.value === "tag") return searchTagLoading.value;
  return searchVideoLoading.value;
});

const searchVerticalLoadingMore = computed(() => {
  if (searchTab.value === "tag") return searchTagLoadingMore.value;
  return searchVideoLoadingMore.value;
});

function formatSearchTagsText(card) {
  const raw = card?.tags ?? card?.tagNames ?? "";
  if (Array.isArray(raw)) return raw.map((x) => String(x)).filter(Boolean).join(" ");
  return String(raw || "");
}

/** 话题展示为 #标签 形式（短视频底部文案用） */
function formatSearchTagsHashtags(card) {
  const raw = card?.tags ?? card?.tagNames ?? "";
  const arr = Array.isArray(raw)
    ? raw.map((x) => String(x).trim()).filter(Boolean)
    : String(raw || "")
        .split(/[\s,，;]+/)
        .map((x) => x.trim())
        .filter(Boolean);
  if (!arr.length) return "";
  return arr.map((x) => (x.startsWith("#") ? x : `#${x}`)).join(" ");
}

/** 解析为可点击标签（不含 # 前缀，便于搜索） */
function parseSearchTagsArray(card) {
  const raw = card?.tags ?? card?.tagNames ?? "";
  const arr = Array.isArray(raw)
    ? raw.map((x) => String(x).trim()).filter(Boolean)
    : String(raw || "")
        .split(/[\s,，;]+/)
        .map((x) => x.trim().replace(/^#/, ""))
        .filter(Boolean);
  return arr;
}

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

function searchFeedBindMainVideo(cardId, el) {
  const k = String(cardId);
  const cur = searchFeedMainVideoById.value[k];
  if (el) {
    if (cur === el) return;
    searchFeedMainVideoById.value = { ...searchFeedMainVideoById.value, [k]: el };
  } else {
    if (cur === undefined) return;
    const next = { ...searchFeedMainVideoById.value };
    delete next[k];
    searchFeedMainVideoById.value = next;
  }
}

function searchFeedBindMediaWrap(cardId, el) {
  const k = String(cardId);
  const cur = searchFeedMediaWrapById.value[k];
  if (el) {
    if (cur === el) return;
    searchFeedMediaWrapById.value = { ...searchFeedMediaWrapById.value, [k]: el };
  } else {
    if (cur === undefined) return;
    const next = { ...searchFeedMediaWrapById.value };
    delete next[k];
    searchFeedMediaWrapById.value = next;
  }
}

function searchFeedGetMainVideo(id) {
  return searchFeedMainVideoById.value[String(id)] ?? null;
}

function searchFeedListMuted(card) {
  return searchFeedListPrefs.value[String(card.id)]?.muted !== false;
}

function searchFeedListVolValue(card) {
  return searchFeedListPrefs.value[String(card.id)]?.volume ?? 0.85;
}

function searchFeedListScrubMax(cardId) {
  const d = Number(searchFeedProgressMap.value[String(cardId)]?.d);
  return Number.isFinite(d) && d > 0 ? d : 1;
}

function searchFeedListScrubDisplay(cardId) {
  const p = searchFeedProgressMap.value[String(cardId)];
  const t = Number(p?.t);
  const d = Number(p?.d);
  if (!Number.isFinite(t) || t < 0) return 0;
  if (Number.isFinite(d) && d > 0) return Math.min(t, d);
  return 0;
}

function searchFeedListDurationReady(cardId) {
  const d = Number(searchFeedProgressMap.value[String(cardId)]?.d);
  return Number.isFinite(d) && d > 0;
}

function searchFeedListCurrent(cardId) {
  return Number(searchFeedProgressMap.value[String(cardId)]?.t) || 0;
}

function searchFeedListDuration(cardId) {
  return Number(searchFeedProgressMap.value[String(cardId)]?.d) || 0;
}

function searchFeedListShowPaused(card) {
  return searchFeedPausedMap.value[String(card.id)] !== false;
}

function onSearchFeedListScrubInput(e, card) {
  const v = searchFeedGetMainVideo(card.id);
  if (!v) return;
  const t = Number(e.target.value);
  if (!Number.isFinite(t)) return;
  v.currentTime = t;
  const d = readVideoDurationFromVideoEl(v);
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [String(card.id)]: { t, d: d > 0 ? d : searchFeedProgressMap.value[String(card.id)]?.d ?? 0 },
  };
}

function searchFeedListToggleMute(card, ev) {
  ev?.stopPropagation?.();
  const k = String(card.id);
  const next = !searchFeedListMuted(card);
  searchFeedListPrefs.value = { ...searchFeedListPrefs.value, [k]: { ...searchFeedListPrefs.value[k], muted: next } };
  const el = searchFeedGetMainVideo(card.id);
  if (el) el.muted = next;
}

function onSearchFeedListVolumeInput(e, card) {
  const vol = Number(e.target.value);
  const k = String(card.id);
  searchFeedListPrefs.value = {
    ...searchFeedListPrefs.value,
    [k]: { ...searchFeedListPrefs.value[k], volume: vol, muted: vol === 0 },
  };
  const v = searchFeedGetMainVideo(card.id);
  if (v) {
    v.volume = vol;
    v.muted = vol === 0;
  }
}

async function searchFeedListRequestPip(card, ev) {
  ev?.stopPropagation?.();
  const v = searchFeedGetMainVideo(card.id);
  if (!v?.requestPictureInPicture) return;
  try {
    if (document.pictureInPictureElement === v) {
      await document.exitPictureInPicture();
    } else {
      await v.requestPictureInPicture();
    }
  } catch {
    /* ignore */
  }
}

async function searchFeedListRequestFullscreen(card, ev) {
  ev?.stopPropagation?.();
  const el = searchFeedMediaWrapById.value[String(card.id)];
  if (!el) return;
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

function searchFeedListTogglePlay(card, ev) {
  ev?.stopPropagation?.();
  const v = searchFeedGetMainVideo(card.id);
  if (!v) return;
  if (v.paused) v.play().catch(() => {});
  else v.pause();
}

function onSearchFeedForwardClick(card, ev) {
  ev?.stopPropagation?.();
  openForwardVideoModal(card);
}

function onSearchFeedListTimeUpdate(e, cardId) {
  const v = e.target;
  const id = String(cardId);
  const now = Date.now();
  const last = searchFeedProgressThrottleTs.get(id) ?? 0;
  if (now - last < 180) {
    return;
  }
  searchFeedProgressThrottleTs.set(id, now);
  const d = readVideoDurationFromVideoEl(v);
  const t = Number(v.currentTime) || 0;
  const prev = searchFeedProgressMap.value[id];
  const dur = d > 0 ? d : prev?.d ?? 0;
  if (prev && Math.abs((prev.t ?? 0) - t) < 0.12 && prev.d === dur) {
    return;
  }
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [id]: { t, d: dur },
  };
  const slide = e.target.closest?.("[data-search-v-slide]");
  const blur = slide?.querySelector?.(".search-feed-video--blur");
  if (blur && v && Math.abs(blur.currentTime - v.currentTime) > 0.25) {
    blur.currentTime = v.currentTime;
  }
}

function onSearchFeedListLoadedMetadata(e, cardId) {
  const v = e.target;
  const id = String(cardId);
  const d = readVideoDurationFromVideoEl(v);
  searchFeedProgressMap.value = {
    ...searchFeedProgressMap.value,
    [id]: { t: Number(v.currentTime) || 0, d },
  };
  const p = searchFeedListPrefs.value[id];
  v.volume = p?.volume ?? 0.85;
  v.muted = p?.muted !== false;
}

function onSearchFeedListSeeked(e) {
  const v = e.target;
  const slide = e.target.closest?.("[data-search-v-slide]");
  const blur = slide?.querySelector?.(".search-feed-video--blur");
  if (blur && v) {
    blur.currentTime = v.currentTime;
  }
}

function onSearchFeedListPlay(e) {
  const blur = e.target?.closest?.("[data-search-v-slide]")?.querySelector?.(".search-feed-video--blur");
  blur?.play?.().catch(() => {});
}

function onSearchFeedListPause(e) {
  const blur = e.target?.closest?.("[data-search-v-slide]")?.querySelector?.(".search-feed-video--blur");
  blur?.pause?.();
}

function onSearchFeedListVideoPlay(e, cardId) {
  onSearchFeedListPlay(e);
  searchFeedPausedMap.value = { ...searchFeedPausedMap.value, [String(cardId)]: false };
}

function onSearchFeedListVideoPause(e, cardId) {
  onSearchFeedListPause(e);
  searchFeedPausedMap.value = { ...searchFeedPausedMap.value, [String(cardId)]: true };
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

function openSearchListCommentPanel(card, ev) {
  ev?.stopPropagation?.();
  if (!card?.id) return;
  shortsCommentPanelOpen.value = false;
  searchExpandCommentOpen.value = false;
  searchListCommentCard.value = card;
  searchListCommentOpen.value = true;
}

function formatSearchRelTime(card) {
  const t = card?.createTime ?? card?.publishTime ?? "";
  if (!t) return "";
  try {
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return "";
    const diff = Date.now() - d.getTime();
    const day = Math.floor(diff / 86400000);
    if (day <= 0) return "今天";
    if (day === 1) return "昨天";
    if (day < 7) return `${day}天前`;
    return d.toLocaleDateString();
  } catch {
    return "";
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
const hoveringPreviewCardId = ref("");
const activePreviewCardId = ref("");
const activePreviewVideoUrl = ref("");
let hoverPreviewTimer = null;
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
const { token } = storeToRefs(authStore);
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
const showProfileCenter = ref(false);
/** null = 自己的主页；有值 = 正在查看该 userId 的主页 */
const profileTargetUserId = ref(null);
/** 从详情页或短视频流进个人主页：浏览器后退时恢复（含侧栏评论里点头像） */
const profileReturnContextRef = ref(null);
/** 当前登录用户 id，来自 /user/info，用于判断是否「看自己」 */
const currentUserId = ref(null);
/** 当前登录用户资料快照（顶栏头像等），不因进入他人主页而被替换 */
const selfUserProfile = ref(null);
const profileUser = ref(null);
const profileLoading = ref(false);
/** 他人主页：是否已关注该用户（GET /follow/isFollow/{authorId}） */
const profileTargetFollowed = ref(false);
const profileFollowBusy = ref(false);
const profileChatBusy = ref(false);
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
/** 视频详情页 */
const showVideoDetail = ref(false);
const videoDetail = ref(null);
const detailLoading = ref(false);
/** 详情页：仅当加载超过阈值才显示加载层，避免闪一下 */
const detailVideoBuffering = ref(false);
/** 短视频播放器域状态在 pinia shortsStore（含历史/赞过/主页/关注共用的全屏队列），同名解构零引用改动 */
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
  shortsFeedIndex,
  shortsFeedIsPortrait,
  shortsFeedDuration,
  shortsFeedCurrentTime,
  shortsFeedVolume,
  shortsFeedMuted,
  shortsFeedPaused,
  shortsFeedAutoplayPending,
  shortsFeedVideoBuffering,
  shortsCommentPanelOpen,
  shortsPanelTopComments,
  shortsPanelCommentsLoading,
  shortsPanelCommentsPageNo,
  shortsPanelCommentsPages,
  shortsPanelCommentDraft,
  shortsPanelCommentSubmitting,
  shortsPanelReplyTarget,
  shortsPanelThreadByRootId,
  shortsPanelExpandedRoots,
  shortsLikeBusy,
  shortsPlaybackAccSec,
  shortsLastTimeSec,
  shortsAccVideoId,
} = storeToRefs(useShortsStore());
const DETAIL_BUFFERING_UI_DELAY_MS = 280;
const SHORTS_BUFFERING_UI_DELAY_MS = 320;
let detailBufferingUITimer = null;
let shortsBufferingUITimer = null;

function clearDetailBufferingUITimer() {
  if (detailBufferingUITimer != null) {
    clearTimeout(detailBufferingUITimer);
    detailBufferingUITimer = null;
  }
}

function scheduleDetailBufferingOverlay() {
  clearDetailBufferingUITimer();
  detailVideoBuffering.value = false;
  detailBufferingUITimer = window.setTimeout(() => {
    detailBufferingUITimer = null;
    detailVideoBuffering.value = true;
  }, DETAIL_BUFFERING_UI_DELAY_MS);
}

function hideDetailBufferingOverlay() {
  clearDetailBufferingUITimer();
  detailVideoBuffering.value = false;
}

function clearShortsBufferingUITimer() {
  if (shortsBufferingUITimer != null) {
    clearTimeout(shortsBufferingUITimer);
    shortsBufferingUITimer = null;
  }
}

function scheduleShortsBufferingOverlay() {
  clearShortsBufferingUITimer();
  shortsFeedVideoBuffering.value = false;
  shortsBufferingUITimer = window.setTimeout(() => {
    shortsBufferingUITimer = null;
    shortsFeedVideoBuffering.value = true;
  }, SHORTS_BUFFERING_UI_DELAY_MS);
}

function hideShortsBufferingOverlay() {
  clearShortsBufferingUITimer();
  shortsFeedVideoBuffering.value = false;
}
const detailErrorMsg = ref("");
const detailPlayerRef = ref(null);
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

/** 长视频详情页评论（GET /comment/list、POST publish/delete） */
const DETAIL_COMMENT_PAGE_SIZE = 10;
const detailTopComments = ref([]);
const detailCommentsLoading = ref(false);
const detailCommentsPageNo = ref(1);
const detailCommentsPages = ref(0);
const detailCommentDraft = ref("");
const detailCommentInputRef = ref(null);
const detailCommentSubmitting = ref(false);
const detailReplyTarget = ref(null);
/** 楼主 id → { loaded, list } */
const detailThreadByRootId = ref({});
const detailExpandedRoots = ref({});
/** 评论点赞请求中，避免连点（commentId → true） */
const commentLikeBusyById = ref({});
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
/** 当前作者是否已关注（按视频 id） */
/** 按作者 ID 覆盖关注状态（乐观更新后优先于接口返回的 isFollow） */
const followStateOverrideByAuthorId = ref({});
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
let shortsLongResumeInFlight = false;
let shortsLongResumeLastSentAtMs = 0;
let shortsLongResumeTickCount = 0;
let shortsLongResumeAppliedVideoId = "";
const UI_STATE_STORAGE_KEY = "myyoutube_ui_state_v1";
/** 进个人主页时把返回快照写入 sessionStorage，避免内存 ref 丢失导致后退无法恢复短视频流 */
const PROFILE_RETURN_CTX_STORAGE_KEY = "myyoutube_profile_return_ctx_v1";

function persistProfileReturnContext(ctx) {
  try {
    if (ctx == null) {
      sessionStorage.removeItem(PROFILE_RETURN_CTX_STORAGE_KEY);
      return;
    }
    sessionStorage.setItem(PROFILE_RETURN_CTX_STORAGE_KEY, JSON.stringify(ctx));
  } catch {
    /* ignore */
  }
}

function readPersistedProfileReturnContext() {
  try {
    const raw = sessionStorage.getItem(PROFILE_RETURN_CTX_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const o = JSON.parse(raw);
    return o && typeof o === "object" ? o : null;
  } catch {
    return null;
  }
}

function clearCapturedProfileReturnContext() {
  profileReturnContextRef.value = null;
  persistProfileReturnContext(null);
}

const profileWorksRows = ref([]);
const profileWorksLoading = ref(false);
const profileWorksFilter = ref("ALL");

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
/** 详情页点赞：无 isLiked 时用本地切换（与后端幂等配合） */
const detailSessionLiked = ref(false);
const detailLikeBusy = ref(false);
const detailFollowBusy = ref(false);
let lastShortsLikeAt = 0;
let lastDetailLikeAt = 0;
/** 仅当「当前短视频 id」变化时同步爱心 UI，避免 patch 列表导致 watch 反复把已取消赞设回 true */
/** 自己的视频在全屏短视频里显示更多操作（三点） */
const selfVideoActionOpen = ref(false);
const selfVideoDeleteConfirmOpen = ref(false);
/** 删除确认弹窗对应的视频 id（创作中心 / 短视频流共用） */
const pendingDeleteVideoId = ref(null);
/** 创作中心表格行「更多」菜单 */
const studioRowMenuOpenId = ref(null);
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
    ...item,
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
const moduleEmptyState = computed(() => {
  if (activeMenuKey.value === "shorts") {
    return {
      icon: "ri-video-line",
      title: "暂无短视频",
      desc: "当前模块暂无可展示内容",
    };
  }
  if (activeMenuKey.value === "home") {
    return {
      icon: "ri-film-line",
      title: "暂无推荐内容",
      desc: "稍后刷新或切换分类再试",
    };
  }
  return {
    icon: "ri-inbox-line",
    title: "暂无数据",
    desc: "当前模块暂无可展示内容",
  };
});

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

/** 顶栏「我」头像：始终为当前登录用户，不随他人主页 profileUser 变化 */
const topbarAvatarSrc = computed(() => {
  const u = selfUserProfile.value;
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

const detailSubscribeFollowed = computed(() => {
  const aid = videoDetail.value?.authorId;
  if (aid == null || aid === "") {
    return false;
  }
  const k = String(aid);
  if (Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, k)) {
    return followStateOverrideByAuthorId.value[k];
  }
  return Boolean(videoDetail.value?.isFollow);
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

const followingRows = ref([]);
const followingPageNo = ref(1);
const followingLoading = ref(false);
const followingHasMore = ref(true);

const followerRows = ref([]);
const followerPageNo = ref(1);
const followerLoading = ref(false);
const followerHasMore = ref(true);

/** 与 videoApi.normalizeVideoListVo 作者头像字段链一致；最终展示 URL 在 normalizeRelationUser 里算好，避免列表每次渲染重复 sanitize */
function relationRowRawAuthorAvatar(raw) {
  if (!raw || typeof raw !== "object") {
    return "";
  }
  const direct =
    raw.authorAvatarUrl ??
    raw.avatar_url ??
    raw.authorAvatar ??
    raw.avatarUrl ??
    raw.avatar ??
    "";
  if (typeof direct === "string" && direct.trim()) {
    return direct.trim();
  }
  if (direct != null && direct !== "") {
    return String(direct).trim();
  }
  const nested = raw.user ?? raw.followee ?? raw.followUser ?? raw.fanUser;
  if (nested && typeof nested === "object") {
    return relationRowRawAuthorAvatar(nested);
  }
  return "";
}

function normalizeRelationUser(raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.followeeId ?? raw.fanId ?? raw.id ?? raw.userId ?? raw.user_id ?? "";
  const authorAvatarUrl = relationRowRawAuthorAvatar(raw);
  const trimmed = String(authorAvatarUrl).trim();
  const avatarDisplaySrc = trimmed ? sanitizeImageUrl(trimmed) || profileAvatar : profileAvatar;
  return {
    id,
    nickName: raw.nickname ?? raw.nickName ?? raw.name ?? raw.nick_name ?? "",
    authorAvatarUrl,
    avatarUrl: authorAvatarUrl,
    avatarDisplaySrc,
    introduction: raw.introduction ?? "",
  };
}

function extractPageList(data) {
  if (Array.isArray(data)) {
    return data;
  }
  if (!data || typeof data !== "object") {
    return [];
  }
  if (Array.isArray(data.list)) {
    return data.list;
  }
  if (Array.isArray(data.records)) {
    return data.records;
  }
  if (Array.isArray(data.content)) {
    return data.content;
  }
  const inner = data.data;
  if (inner && typeof inner === "object") {
    if (Array.isArray(inner.list)) {
      return inner.list;
    }
    if (Array.isArray(inner.records)) {
      return inner.records;
    }
  }
  return [];
}

async function loadFollowingMore({ reset = false } = {}) {
  if (!isAuthed.value) return;
  if (followingLoading.value) return;
  if (!followingHasMore.value && !reset) return;
  followingLoading.value = true;
  try {
    const pageNo = reset ? 1 : followingPageNo.value;
    const pageSize = 20;
    const data = await fetchMyFollowingPage({ pageNo, pageSize });
    const list = extractPageList(data);
    const normalized = list.map(normalizeRelationUser).filter(Boolean);
    followingRows.value = reset ? normalized : [...followingRows.value, ...normalized];
    if (relationTableOpen.value && relationTableKind.value === "following") {
      relationTableUsers.value = followingRows.value;
    }
    followingPageNo.value = pageNo;
    followingHasMore.value = normalized.length === pageSize;
  } catch (e) {
    if (reset) {
      followingRows.value = [];
      followingPageNo.value = 1;
    }
    followingHasMore.value = false;
  } finally {
    followingLoading.value = false;
  }
}

function loadFollowingNextPage() {
  if (followingLoading.value || !followingHasMore.value) return;
  followingPageNo.value += 1;
  void loadFollowingMore({ reset: false });
}

async function loadFollowersMore({ reset = false } = {}) {
  if (!isAuthed.value) return;
  if (followerLoading.value) return;
  if (!followerHasMore.value && !reset) return;
  followerLoading.value = true;
  try {
    const pageNo = reset ? 1 : followerPageNo.value;
    const pageSize = 20;
    const data = await fetchMyFansPage({ pageNo, pageSize });
    const list = extractPageList(data);
    const normalized = list.map(normalizeRelationUser).filter(Boolean);
    followerRows.value = reset ? normalized : [...followerRows.value, ...normalized];
    if (relationTableOpen.value && relationTableKind.value === "followers") {
      relationTableUsers.value = followerRows.value;
    }
    followerPageNo.value = pageNo;
    followerHasMore.value = normalized.length === pageSize;
  } catch (e) {
    if (reset) {
      followerRows.value = [];
      followerPageNo.value = 1;
    }
    followerHasMore.value = false;
  } finally {
    followerLoading.value = false;
  }
}

function loadFollowersNextPage() {
  if (followerLoading.value || !followerHasMore.value) return;
  followerPageNo.value += 1;
  void loadFollowersMore({ reset: false });
}

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
  return normalizeVideoDetail({
    id: vid ?? (card?.id != null ? String(card.id) : ""),
    title: card?.title ?? "未命名视频",
    description: card?.description ?? "",
    coverUrl: card?.coverUrl || card?.avatarUrl || profileBanner,
    videoUrl: resolveVideoUrl(card) || "",
    videoType: card?.videoType ?? "LONG_VIDEO",
    playCount: card?.playCount ?? 0,
    likeCount: card?.likeCount ?? 0,
    commentCount: card?.commentCount ?? 0,
    authorId: card?.authorId ?? null,
    authorNickname: card?.authorNickname ?? card?.author ?? "频道",
    authorAvatarUrl: card?.authorAvatarUrl || "",
    authorFanCount: card?.authorFanCount ?? 0,
    isFollow: card?.isFollow,
    isLike: card?.isLike,
    allowComment: card?.allowComment,
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

/** 仅短视频写入 /videos/shorts/play；长视频在竖屏流里走续播，不得混入 shortIds */
function shouldRecordShortsWatchHistory(item) {
  return item != null && !isLongVideoItem(item);
}

function cardAuthorAvatar(card, index) {
  const raw = card?.authorAvatarUrl ?? card?.avatarUrl ?? "";
  const trimmed = String(raw).trim();
  if (!trimmed) {
    return profileAvatar;
  }
  const u = sanitizeImageUrl(trimmed);
  return u || profileAvatar;
}

function cardCoverSrc(card, index) {
  return card?.coverUrl || card?.avatarUrl || profileBanner;
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
    await el.play();
  } catch {
    try {
      el.muted = true;
      await el.play();
    } catch {
      /* 静音仍被拦截则只能由用户点击控件播放 */
    }
  }
}

function onDetailVideoLoadStart() {
  scheduleDetailBufferingOverlay();
}

function onDetailVideoMediaError() {
  hideDetailBufferingOverlay();
}

function onDetailVideoCanPlay() {
  hideDetailBufferingOverlay();
  applyDetailPlaybackResumeIfNeeded();
  void tryDetailVideoAutoplay();
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

async function onDetailLikeClick() {
  const d = videoDetail.value;
  if (!d?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再点赞", "info");
    return;
  }
  const now = Date.now();
  if (now - lastDetailLikeAt < 300) return;
  lastDetailLikeAt = now;
  if (detailLikeBusy.value) return;
  const vid = parseVideoIdForApi(d.id);
  if (vid == null) return;
  const current = Number(detailPlayerRef.value?.currentTime || 0);
  detailLikeBusy.value = true;
  try {
    if (detailSessionLiked.value) {
      await cancelLikeVideoApi(vid);
      detailSessionLiked.value = false;
    } else {
      await likeVideoApi(vid);
      detailSessionLiked.value = true;
    }
    const raw = await fetchVideoDetail(vid);
    const merged = normalizeVideoDetail(raw);
    if (merged) {
      const keepVideoUrl = d.videoUrl;
      const keepCoverUrl = d.coverUrl;
      videoDetail.value = {
        ...d,
        ...merged,
        videoUrl: keepVideoUrl || merged.videoUrl,
        coverUrl: keepCoverUrl ?? merged.coverUrl,
        isLike: detailSessionLiked.value,
      };
    } else {
      videoDetail.value = { ...d, isLike: detailSessionLiked.value };
    }
    void reportBehaviorSilently(d.id, "LIKE", current * 1000);
  } catch (error) {
    pushToast(error.message || "点赞失败", "error");
  } finally {
    detailLikeBusy.value = false;
  }
}

async function onDetailDislikeClick() {
  const d = videoDetail.value;
  if (!d?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  const vid = parseVideoIdForApi(d.id);
  if (vid == null) return;
  const current = Number(detailPlayerRef.value?.currentTime || 0);
  try {
    await dislikeVideoApi(vid);
    void reportBehaviorSilently(d.id, "DISLIKE", current * 1000);
    pushToast("已标记为不感兴趣", "success");
  } catch (error) {
    pushToast(error.message || "操作失败", "error");
  }
}

async function onDetailFollowClick() {
  const d = videoDetail.value;
  if (!d?.authorId) {
    return;
  }
  if (!isAuthed.value) {
    pushToast("请先登录后再关注", "info");
    return;
  }
  const aid = String(d.authorId);
  if (currentUserId.value != null && aid === String(currentUserId.value)) {
    return;
  }
  const key = aid;
  const currently = detailSubscribeFollowed.value;
  detailFollowBusy.value = true;
  try {
    if (currently) {
      await unfollowAuthor(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: false };
      patchIsFollowForAuthor(aid, false);
      videoDetail.value = { ...d, isFollow: false };
      if (profileTargetUserId.value != null && String(profileTargetUserId.value) === aid) {
        profileTargetFollowed.value = false;
      }
      pushToast("已取消关注", "success");
    } else {
      await followAuthor(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: true };
      patchIsFollowForAuthor(aid, true);
      videoDetail.value = { ...d, isFollow: true };
      if (profileTargetUserId.value != null && String(profileTargetUserId.value) === aid) {
        profileTargetFollowed.value = true;
      }
      pushToast("关注成功", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  } finally {
    detailFollowBusy.value = false;
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

async function sendPlaybackResumePut(payload, { keepalive = false } = {}) {
  if (!payload) return;
  try {
    const headers = {
      "Content-Type": "application/json",
    };
    const authTok = getToken();
    if (authTok) {
      headers["Authorization"] = authTok;
    }
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

async function sendDetailPlaybackResume(payload, options) {
  return sendPlaybackResumePut(payload, options);
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
  // 任意类型：用户手势暂停后禁止自动 play 重试（否则 canplay/定时重试会把暂停顶掉）
  const isUser = e?.isTrusted !== false;
  if (isUser) {
    detailUserPausedManually.value = true;
  }
  if (!isLongDetailVideo()) {
    return;
  }
  stopDetailPlaybackResumeTimer();
  if (isUser) {
    void flushDetailPlaybackResume("pause");
  }
}

function onDetailVideoPlay(e) {
  const isUser = e?.isTrusted !== false;
  if (isUser) {
    detailUserPausedManually.value = false;
  }
  const d = videoDetail.value;
  if (d?.id && !isLongVideoItem(d)) {
    enqueueShortsHistoryByVideoId(d.id, d);
  }
  if (!isLongDetailVideo()) {
    return;
  }
  // 播放恢复后将按 timeupdate 次数上报，这里重置计数避免刚播放就上报。
  detailPlaybackResumeTickCount = 0;
}

/** 刷新 / 往返缓存后 DOM 就绪较晚，多补几轮 */
function onWindowPageShow() {
  if (isAuthed.value) {
    reconnectNotifyWebSocketIfNeeded();
  }
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
  persistProfileReturnContext(null);
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

async function openVideoDetail(card, { skipRemoteDetail = false } = {}) {
  if (!card) {
    return;
  }
  // main 被 v-show 隐藏时部分浏览器仍会继续播放短视频音频，必须先停掉
  pauseShortsFeedVideos({ muteForeground: true });
  const skipRemote = skipRemoteDetail || card?.skipDetailFetch === true;
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
  detailSessionLiked.value = Boolean(instantDetail?.isLike);
  detailLoading.value = false;
  nextTick(() => {
    scheduleDetailVideoAutoplayRetries();
  });
  try {
    if (vid != null && !skipRemote) {
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
        if (!d.coverUrl) {
          d.coverUrl = card?.coverUrl || card?.avatarUrl || "";
        }
      }
      videoDetail.value = d;
      if (isAuthed.value && currentUserId.value && vid != null) {
        try {
          const liked = await fetchVideoLikedStatus(vid, currentUserId.value);
          videoDetail.value = { ...videoDetail.value, isLike: liked };
          detailSessionLiked.value = liked;
        } catch {
          detailSessionLiked.value = Boolean(d?.isLike);
        }
      } else {
        detailSessionLiked.value = Boolean(d?.isLike);
      }
      nextTick(() => {
        applyDetailPlaybackResumeIfNeeded();
      });
    } else {
      videoDetail.value = instantDetail;
      detailSessionLiked.value = Boolean(instantDetail?.isLike);
    }
  } catch (e) {
    videoDetail.value = instantDetail;
    detailErrorMsg.value = `详情接口不可用，已展示本地数据：${e.message}`;
    detailSessionLiked.value = Boolean(instantDetail?.isLike);
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
  hideDetailBufferingOverlay();
  detailErrorMsg.value = "";
  detailSidebarList.value = [];
  resetDetailCommentsState();
  clearCapturedProfileReturnContext();
  clearVideoDetailFromAddressBar();
}

function resetDetailCommentsState() {
  detailTopComments.value = [];
  detailCommentsLoading.value = false;
  detailCommentsPageNo.value = 1;
  detailCommentsPages.value = 0;
  detailCommentDraft.value = "";
  detailCommentSubmitting.value = false;
  detailReplyTarget.value = null;
  detailThreadByRootId.value = {};
  detailExpandedRoots.value = {};
}

function detailVideoAllowComment() {
  return Number(videoDetail.value?.allowComment ?? 1) === 1;
}

/** 评论区：本人评论显示「我」，与 currentUserId / 评论 userId 比较 */
function commentDisplayNickname(userId, nickname) {
  const nick = nickname ?? "用户";
  if (currentUserId.value == null || userId == null || userId === "") {
    return nick;
  }
  return String(userId) === String(currentUserId.value) ? "我" : nick;
}

function formatDetailCommentTime(isoLike) {
  if (isoLike == null || isoLike === "") {
    return "";
  }
  try {
    const s = String(isoLike);
    const d = new Date(s.includes("T") ? s : s.replace(" ", "T"));
    if (Number.isNaN(d.getTime())) {
      return s;
    }
    const diff = Date.now() - d.getTime();
    const sec = Math.floor(diff / 1000);
    if (sec < 60) {
      return "刚刚";
    }
    if (sec < 3600) {
      return `${Math.floor(sec / 60)} 分钟前`;
    }
    if (sec < 86400) {
      return `${Math.floor(sec / 3600)} 小时前`;
    }
    if (sec < 86400 * 7) {
      return `${Math.floor(sec / 86400)} 天前`;
    }
    return d.toLocaleDateString("zh-CN");
  } catch {
    return String(isoLike);
  }
}

async function loadDetailTopComments(reset, opts = {}) {
  const preserveThreadUi = opts.preserveThreadUi === true;
  const savedExpanded =
    preserveThreadUi && reset ? { ...detailExpandedRoots.value } : null;

  const vid = parseVideoIdForApi(videoDetail.value?.id);
  if (!vid) {
    return;
  }
  if (reset) {
    detailCommentsPageNo.value = 1;
    detailTopComments.value = [];
    if (!preserveThreadUi) {
      detailThreadByRootId.value = {};
      detailExpandedRoots.value = {};
    }
  }
  detailCommentsLoading.value = true;
  try {
    const { pages, list } = await fetchCommentPage({
      videoId: vid,
      parentId: 0,
      pageNo: detailCommentsPageNo.value,
      pageSize: DETAIL_COMMENT_PAGE_SIZE,
    });
    detailCommentsPages.value = pages || 0;
    if (reset) {
      detailTopComments.value = list;
    } else {
      detailTopComments.value = [...detailTopComments.value, ...list];
    }

    if (savedExpanded) {
      const topIds = new Set(detailTopComments.value.map((x) => String(x.id)));
      const nextExpanded = {};
      for (const k of Object.keys(savedExpanded)) {
        if (savedExpanded[k] && topIds.has(k)) {
          nextExpanded[k] = true;
        }
      }
      detailExpandedRoots.value = nextExpanded;
      const nextThreads = { ...detailThreadByRootId.value };
      for (const k of Object.keys(nextExpanded)) {
        delete nextThreads[k];
      }
      detailThreadByRootId.value = nextThreads;
      for (const k of Object.keys(nextExpanded)) {
        void ensureDetailThreadLoaded(k);
      }
    }
  } catch (e) {
    pushToast(e?.message || "评论加载失败", "error");
  } finally {
    detailCommentsLoading.value = false;
  }
}

function detailCommentHasMore() {
  return detailCommentsPageNo.value < detailCommentsPages.value;
}

async function loadMoreDetailComments() {
  if (detailCommentsLoading.value || !detailCommentHasMore()) {
    return;
  }
  detailCommentsPageNo.value += 1;
  await loadDetailTopComments(false);
}

async function ensureDetailThreadLoaded(rootId) {
  const vid = parseVideoIdForApi(videoDetail.value?.id);
  const k = String(rootId);
  if (!vid || detailThreadByRootId.value[k]?.loaded) {
    return;
  }
  detailThreadByRootId.value = {
    ...detailThreadByRootId.value,
    [k]: { ...(detailThreadByRootId.value[k] || {}), loading: true, list: [] },
  };
  try {
    const { list } = await fetchCommentPage({
      videoId: vid,
      parentId: rootId,
      rootId,
      pageNo: 1,
      pageSize: 100,
    });
    detailThreadByRootId.value = {
      ...detailThreadByRootId.value,
      [k]: { loaded: true, loading: false, list },
    };
  } catch (e) {
    detailThreadByRootId.value = {
      ...detailThreadByRootId.value,
      [k]: { loaded: false, loading: false, list: [] },
    };
    pushToast(e?.message || "回复加载失败", "error");
  }
}

function toggleDetailThread(rootId) {
  const k = String(rootId);
  if (detailExpandedRoots.value[k]) {
    const next = { ...detailExpandedRoots.value };
    delete next[k];
    detailExpandedRoots.value = next;
  } else {
    detailExpandedRoots.value = { ...detailExpandedRoots.value, [k]: true };
    void ensureDetailThreadLoaded(rootId);
  }
}

function setDetailReplyTarget(row) {
  if (!row) {
    detailReplyTarget.value = null;
    return;
  }
  detailReplyTarget.value = {
    id: row.id,
    nickname: row.nickname || "用户",
  };
}

function insertGlyphIntoCommentDraft(draftRef, inputRef, glyph) {
  const g = String(glyph || "");
  if (!g) return;
  const el = inputRef?.value;
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    const v = String(draftRef.value ?? "");
    const start = typeof el.selectionStart === "number" ? el.selectionStart : v.length;
    const end = typeof el.selectionEnd === "number" ? el.selectionEnd : start;
    draftRef.value = `${v.slice(0, start)}${g}${v.slice(end)}`;
    nextTick(() => {
      const pos = start + g.length;
      el.selectionStart = el.selectionEnd = pos;
      el.focus();
    });
  } else {
    draftRef.value = String(draftRef.value ?? "") + g;
  }
}

function onShortsPanelCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(shortsPanelCommentDraft, shortsPanelCommentInputRef, glyph);
}

function onDetailCommentEmojiPick(glyph) {
  insertGlyphIntoCommentDraft(detailCommentDraft, detailCommentInputRef, glyph);
}

async function submitDetailComment() {
  const text = detailCommentDraft.value.trim();
  if (!text) {
    return;
  }
  const vid = parseVideoIdForApi(videoDetail.value?.id);
  if (!vid) {
    return;
  }
  if (!isAuthed.value) {
    pushToast("请先登录后再评论", "info");
    return;
  }
  if (!detailVideoAllowComment()) {
    pushToast("作者已关闭评论", "info");
    return;
  }
  const wasTopLevel = detailReplyTarget.value == null;
  const parentId = wasTopLevel ? 0 : detailReplyTarget.value.id;
  detailCommentSubmitting.value = true;
  try {
    await publishCommentApi({ videoId: vid, content: text, parentId });
    detailCommentDraft.value = "";
    detailReplyTarget.value = null;
    if (videoDetail.value && wasTopLevel) {
      videoDetail.value = {
        ...videoDetail.value,
        commentCount: Number(videoDetail.value.commentCount ?? 0) + 1,
      };
    }
    await loadDetailTopComments(true, { preserveThreadUi: true });
  } catch (e) {
    pushToast(e?.message || "发送失败", "error");
  } finally {
    detailCommentSubmitting.value = false;
  }
}

function onDetailCommentUserClick(ev, userId) {
  if (userId == null || userId === "") {
    return;
  }
  onAuthorAvatarClick(ev, userId);
}

async function onDeleteDetailComment(c) {
  if (!c?.id || !isAuthed.value) {
    return;
  }
  if (String(c.userId) !== String(currentUserId.value)) {
    return;
  }
  try {
    await deleteCommentApi(c.id);
    pushToast("已删除", "success");
    await loadDetailTopComments(true, { preserveThreadUi: true });
    const pid = c.parentId;
    if (videoDetail.value && (pid === 0 || pid === "0")) {
      const n = Math.max(0, Number(videoDetail.value.commentCount ?? 0) - 1);
      videoDetail.value = { ...videoDetail.value, commentCount: n };
    }
  } catch (e) {
    pushToast(e?.message || "删除失败", "error");
  }
}

function resetShortsPanelCommentsState() {
  shortsPanelTopComments.value = [];
  shortsPanelCommentsLoading.value = false;
  shortsPanelCommentsPageNo.value = 1;
  shortsPanelCommentsPages.value = 0;
  shortsPanelCommentDraft.value = "";
  shortsPanelCommentSubmitting.value = false;
  shortsPanelReplyTarget.value = null;
  shortsPanelThreadByRootId.value = {};
  shortsPanelExpandedRoots.value = {};
}

function toggleShortsCommentPanel() {
  if (!shortsFeedCurrent.value?.id) {
    return;
  }
  searchExpandCommentOpen.value = false;
  searchListCommentOpen.value = false;
  searchListCommentCard.value = null;
  shortsCommentPanelOpen.value = !shortsCommentPanelOpen.value;
}

function toggleSearchExpandCommentPanel() {
  if (!searchExpandCard.value?.id) {
    return;
  }
  shortsCommentPanelOpen.value = false;
  searchListCommentOpen.value = false;
  searchListCommentCard.value = null;
  searchExpandCommentOpen.value = !searchExpandCommentOpen.value;
}

function closeShortsCommentPanel() {
  shortsCommentPanelOpen.value = false;
  searchExpandCommentOpen.value = false;
  searchListCommentOpen.value = false;
  searchListCommentCard.value = null;
}

async function loadShortsPanelTopComments(reset, opts = {}) {
  const preserveThreadUi = opts.preserveThreadUi === true;
  const savedExpanded =
    preserveThreadUi && reset ? { ...shortsPanelExpandedRoots.value } : null;

  const vid = parseVideoIdForApi(activeCommentVideo.value?.id);
  if (!vid) {
    return;
  }
  if (reset) {
    shortsPanelCommentsPageNo.value = 1;
    shortsPanelTopComments.value = [];
    if (!preserveThreadUi) {
      shortsPanelThreadByRootId.value = {};
      shortsPanelExpandedRoots.value = {};
    }
  }
  shortsPanelCommentsLoading.value = true;
  try {
    const { pages, list } = await fetchCommentPage({
      videoId: vid,
      parentId: 0,
      pageNo: shortsPanelCommentsPageNo.value,
      pageSize: DETAIL_COMMENT_PAGE_SIZE,
    });
    shortsPanelCommentsPages.value = pages || 0;
    if (reset) {
      shortsPanelTopComments.value = list;
    } else {
      shortsPanelTopComments.value = [...shortsPanelTopComments.value, ...list];
    }

    if (savedExpanded) {
      const topIds = new Set(shortsPanelTopComments.value.map((x) => String(x.id)));
      const nextExpanded = {};
      for (const k of Object.keys(savedExpanded)) {
        if (savedExpanded[k] && topIds.has(k)) {
          nextExpanded[k] = true;
        }
      }
      shortsPanelExpandedRoots.value = nextExpanded;
      const nextThreads = { ...shortsPanelThreadByRootId.value };
      for (const k of Object.keys(nextExpanded)) {
        delete nextThreads[k];
      }
      shortsPanelThreadByRootId.value = nextThreads;
      for (const k of Object.keys(nextExpanded)) {
        void ensureShortsPanelThreadLoaded(k);
      }
    }
  } catch (e) {
    pushToast(e?.message || "评论加载失败", "error");
  } finally {
    shortsPanelCommentsLoading.value = false;
  }
}

function shortsPanelCommentHasMore() {
  return shortsPanelCommentsPageNo.value < shortsPanelCommentsPages.value;
}

async function loadMoreShortsPanelComments() {
  if (shortsPanelCommentsLoading.value || !shortsPanelCommentHasMore()) {
    return;
  }
  shortsPanelCommentsPageNo.value += 1;
  await loadShortsPanelTopComments(false);
}

async function ensureShortsPanelThreadLoaded(rootId) {
  const vid = parseVideoIdForApi(activeCommentVideo.value?.id);
  const k = String(rootId);
  if (!vid || shortsPanelThreadByRootId.value[k]?.loaded) {
    return;
  }
  shortsPanelThreadByRootId.value = {
    ...shortsPanelThreadByRootId.value,
    [k]: { ...(shortsPanelThreadByRootId.value[k] || {}), loading: true, list: [] },
  };
  try {
    const { list } = await fetchCommentPage({
      videoId: vid,
      parentId: rootId,
      rootId,
      pageNo: 1,
      pageSize: 100,
    });
    shortsPanelThreadByRootId.value = {
      ...shortsPanelThreadByRootId.value,
      [k]: { loaded: true, loading: false, list },
    };
  } catch (e) {
    shortsPanelThreadByRootId.value = {
      ...shortsPanelThreadByRootId.value,
      [k]: { loaded: false, loading: false, list: [] },
    };
    pushToast(e?.message || "回复加载失败", "error");
  }
}

function toggleShortsPanelThread(rootId) {
  const k = String(rootId);
  if (shortsPanelExpandedRoots.value[k]) {
    const next = { ...shortsPanelExpandedRoots.value };
    delete next[k];
    shortsPanelExpandedRoots.value = next;
  } else {
    shortsPanelExpandedRoots.value = { ...shortsPanelExpandedRoots.value, [k]: true };
    void ensureShortsPanelThreadLoaded(rootId);
  }
}

function setShortsPanelReplyTarget(row) {
  if (!row) {
    shortsPanelReplyTarget.value = null;
    return;
  }
  shortsPanelReplyTarget.value = {
    id: row.id,
    nickname: row.nickname || "用户",
  };
}

async function submitShortsPanelComment() {
  const text = shortsPanelCommentDraft.value.trim();
  if (!text) {
    return;
  }
  const cur = activeCommentVideo.value;
  const vid = parseVideoIdForApi(cur?.id);
  if (!vid) {
    return;
  }
  if (!isAuthed.value) {
    pushToast("请先登录后再评论", "info");
    return;
  }
  if (!activeCommentVideoAllowsComment.value) {
    pushToast("作者已关闭评论", "info");
    return;
  }
  const wasTopLevel = shortsPanelReplyTarget.value == null;
  const parentId = wasTopLevel ? 0 : shortsPanelReplyTarget.value.id;
  shortsPanelCommentSubmitting.value = true;
  try {
    await publishCommentApi({ videoId: vid, content: text, parentId });
    shortsPanelCommentDraft.value = "";
    shortsPanelReplyTarget.value = null;
    if (cur?.id && wasTopLevel) {
      patchFeedVideoById(cur.id, {
        commentCount: Number(cur.commentCount ?? 0) + 1,
      });
    }
    await loadShortsPanelTopComments(true, { preserveThreadUi: true });
  } catch (e) {
    pushToast(e?.message || "发送失败", "error");
  } finally {
    shortsPanelCommentSubmitting.value = false;
  }
}

function onShortsPanelCommentUserClick(ev, userId) {
  if (userId == null || userId === "") {
    return;
  }
  onAuthorAvatarClick(ev, userId);
}

async function onDeleteShortsPanelComment(c) {
  if (!c?.id || !isAuthed.value) {
    return;
  }
  if (String(c.userId) !== String(currentUserId.value)) {
    return;
  }
  const cur = activeCommentVideo.value;
  try {
    await deleteCommentApi(c.id);
    pushToast("已删除", "success");
    await loadShortsPanelTopComments(true, { preserveThreadUi: true });
    const pid = c.parentId;
    if (cur?.id && (pid === 0 || pid === "0")) {
      const n = Math.max(0, Number(cur.commentCount ?? 0) - 1);
      patchFeedVideoById(cur.id, { commentCount: n });
    }
  } catch (e) {
    pushToast(e?.message || "删除失败", "error");
  }
}

function isCommentLikeBusy(id) {
  return Boolean(commentLikeBusyById.value[String(id)]);
}

function applyCommentLikePatch(commentId, patch) {
  const sid = String(commentId);
  const merge = (row) => (String(row.id) === sid ? { ...row, ...patch } : row);
  detailTopComments.value = detailTopComments.value.map(merge);
  shortsPanelTopComments.value = shortsPanelTopComments.value.map(merge);
  const dThreads = { ...detailThreadByRootId.value };
  for (const k of Object.keys(dThreads)) {
    const t = dThreads[k];
    if (t?.list?.length) {
      dThreads[k] = { ...t, list: t.list.map(merge) };
    }
  }
  detailThreadByRootId.value = dThreads;
  const sThreads = { ...shortsPanelThreadByRootId.value };
  for (const k of Object.keys(sThreads)) {
    const t = sThreads[k];
    if (t?.list?.length) {
      sThreads[k] = { ...t, list: t.list.map(merge) };
    }
  }
  shortsPanelThreadByRootId.value = sThreads;
}

async function submitCommentLike(row) {
  if (!row?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再点赞", "info");
    return;
  }
  const sid = String(row.id);
  if (commentLikeBusyById.value[sid]) return;
  commentLikeBusyById.value = { ...commentLikeBusyById.value, [sid]: true };
  try {
    if (row.isLike) {
      await cancelLikeCommentApi(row.id);
      applyCommentLikePatch(row.id, {
        isLike: false,
        likeCount: Math.max(0, Number(row.likeCount ?? 0) - 1),
      });
    } else {
      await likeCommentApi(row.id);
      applyCommentLikePatch(row.id, {
        isLike: true,
        likeCount: Number(row.likeCount ?? 0) + 1,
      });
    }
  } catch (e) {
    pushToast(e?.message || (row.isLike ? "取消点赞失败" : "点赞失败"), "error");
  } finally {
    const next = { ...commentLikeBusyById.value };
    delete next[sid];
    commentLikeBusyById.value = next;
  }
}

watch(
  () => [showVideoDetail.value, parseVideoIdForApi(videoDetail.value?.id)],
  ([open, vid], prev) => {
    const prevOpen = prev?.[0];
    const prevVid = prev?.[1];
    if (!open) {
      resetDetailCommentsState();
      return;
    }
    if (!vid) {
      return;
    }
    if (open && vid === prevVid && prevOpen) {
      return;
    }
    void loadDetailTopComments(true);
  },
);

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
    detailSidebarList.value = [];
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
    const query = {
      pageNo: detailSidebarPageNo.value,
      pageSize: DETAIL_SIDEBAR_PAGE_SIZE,
    };
    if (tab === "short") {
      query.videoType = "SHORT_VIDEO";
    } else if (tab === "long") {
      query.videoType = "LONG_VIDEO";
    }
    const res = await fetchAuthorVideos(authorId, query);
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
    detailSidebarList.value = [];
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
  /** 评论打开时循环当前条，避免播完突然切走影响阅读 */
  if (n <= 1 || shortsCommentPanelOpen.value) {
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

async function loadHistoryShortsPage({ reset = false } = {}) {
  if (!isAuthed.value) return;
  if (historyShortLoading.value) return;
  historyShortLoading.value = true;
  try {
    const targetPage = reset ? 1 : historyShortPageNo.value;
    const pageSize = historyShortExpanded.value ? 15 : 5;
    const res = await fetchShortHistoryPage({
      pageNo: targetPage,
      pageSize,
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
      const url = resolveVideoUrl(x);
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

async function loadLikedPage({ reset = false } = {}) {
  const uid = currentUserId.value;
  if (!uid) {
    return;
  }
  const isFirst = reset || likedRows.value.length === 0;
  if (isFirst) {
    if (likedLoadingMore.value) return;
    likedLoading.value = true;
  } else {
    if (likedLoading.value || likedLoadingMore.value) return;
    if (likedPageNo.value >= (likedPages.value || 0)) return;
    likedLoadingMore.value = true;
  }
  try {
    const pageNo = isFirst ? 1 : likedPageNo.value + 1;
    const res = await fetchLikedVideosPage(uid, { pageNo, pageSize: LIKED_PAGE_SIZE });
    if (isFirst) {
      likedRows.value = res.list || [];
    } else {
      likedRows.value = [...likedRows.value, ...(res.list || [])];
    }
    likedPageNo.value = pageNo;
    likedPages.value = res.pages ?? 0;
    likedTotal.value = res.total ?? 0;
  } catch (error) {
    if (isFirst) {
      likedRows.value = [];
    }
    pushToast(error.message || "加载赞过的视频失败", "error");
  } finally {
    likedLoading.value = false;
    likedLoadingMore.value = false;
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
      await loadFollowingMore({ reset: true });
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
  clearCapturedProfileReturnContext();
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

async function loadProfileUser({ withWorks = false } = {}) {
  if (!getToken()) {
    return;
  }
  profileLoading.value = true;
  profileMsg.value = "";
  try {
    const me = normalizeUserProfile(await getUserInfo());
    selfUserProfile.value = me;
    if (me?.id != null) {
      currentUserId.value = me.id;
    }
    if (profileTargetUserId.value == null) {
      profileUser.value = me;
      profileTargetFollowed.value = false;
    } else {
      profileUser.value = normalizeUserProfile(await getUserInfoById(profileTargetUserId.value));
    }
  } catch (error) {
    profileMsg.value = `加载用户信息失败：${error.message}`;
  } finally {
    profileLoading.value = false;
    applyCropsFromStorage();
  }
  void refreshProfileTargetFollowState();
  if (withWorks) {
    void loadProfileWorks();
  }
}

async function refreshProfileTargetFollowState() {
  if (!getToken() || profileTargetUserId.value == null) {
    profileTargetFollowed.value = false;
    return;
  }
  if (currentUserId.value != null && String(profileTargetUserId.value) === String(currentUserId.value)) {
    profileTargetFollowed.value = false;
    return;
  }
  try {
    profileTargetFollowed.value = await isFollowed(profileTargetUserId.value);
  } catch {
    profileTargetFollowed.value = false;
  }
}

async function onProfileWorksChatClick() {
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  if (profileTargetUserId.value == null || isViewingSelf.value) {
    return;
  }
  const aid = profileTargetUserId.value;
  if (currentUserId.value != null && String(aid) === String(currentUserId.value)) {
    return;
  }
  const u = profileUser.value;
  const peerId =
    parseDigitSnowflakeId(u?.id) ?? parseDigitSnowflakeId(aid) ?? safeSnowflakeId(u?.id) ?? safeSnowflakeId(aid);
  const name = stripUserIdLikeName(u?.nickName || u?.nickname || "") || `用户 ${peerId}`;
  const avatar = sanitizeImageUrl(u?.avatarUrl) || profileAvatar;
  profileChatBusy.value = true;
  try {
    await startSingleChat(peerId, { name, avatar });
    pushToast("已打开会话", "success");
  } catch (e) {
    pushToast(e?.message || "发起聊天失败", "error");
  } finally {
    profileChatBusy.value = false;
  }
}

async function toggleProfileTargetFollow() {
  if (!isAuthed.value || profileTargetUserId.value == null) {
    pushToast("请先登录", "info");
    return;
  }
  if (isViewingSelf.value) {
    return;
  }
  const aid = profileTargetUserId.value;
  profileFollowBusy.value = true;
  try {
    if (profileTargetFollowed.value) {
      await unfollowAuthor(aid);
      profileTargetFollowed.value = false;
      const key = String(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: false };
      patchIsFollowForAuthor(aid, false);
      pushToast("已取消关注", "success");
    } else {
      await followAuthor(aid);
      profileTargetFollowed.value = true;
      const key = String(aid);
      followStateOverrideByAuthorId.value = { ...followStateOverrideByAuthorId.value, [key]: true };
      patchIsFollowForAuthor(aid, true);
      pushToast("关注成功", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  } finally {
    profileFollowBusy.value = false;
  }
}

async function loadProfileWorks() {
  profileWorksFilter.value = "ALL";
  const uid = profileTargetUserId.value ?? currentUserId.value;
  if (!uid) {
    profileWorksRows.value = [];
    return;
  }
  profileWorksLoading.value = true;
  try {
    const isSelf = profileTargetUserId.value == null;
    const worksRes = isSelf
      ? await fetchMyVideos({ pageNo: 1, pageSize: 24 })
      : await fetchOtherUserVideos(uid, { pageNo: 1, pageSize: 24 });
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

function onSidebarOpenMyProfile() {
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  onProfile();
}

function onProfile() {
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
  profileReturnContextRef.value = captureProfileReturnContext();
  persistProfileReturnContext(profileReturnContextRef.value);
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
  profileTargetUserId.value = null;
  showProfileCenter.value = true;
  try {
    history.pushState({ myyoutubeProfile: "self" }, "", window.location.href);
  } catch {
    /* ignore */
  }
  activeMenuLabel.value = "个人中心";
  pageHint.value = "这里展示用户主页、作品和数据概览。";
  loadProfileUser({ withWorks: true });
}

/** 进入他人主页（可从视频作者、关注列表等调用 userId；此处提供顶部「Ta」演示入口） */
function openOtherUserProfile(userId) {
  if (searchActive.value) {
    searchActive.value = false;
    teardownSearchVideoObserver();
    if (searchExpandOpen.value) {
      closeSearchExpand();
    }
  }
  if (activeMenuKey.value === "home") {
    saveHomeFeedSnapshot();
  }
  if (activeMenuKey.value === "shorts") {
    void flushShortsHistoryQueue();
  }
  if (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed") {
    pauseShortsFeedVideos({ muteForeground: true });
  }
  profileReturnContextRef.value = captureProfileReturnContext();
  persistProfileReturnContext(profileReturnContextRef.value);
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
  if (shortPlayerOpen.value) {
    closeShortPlayer();
  }
  closeRelationTable();
  showStudio.value = false;
  uploadModalOpen.value = false;
  if (!showVideoDetail.value) {
    clearVideoDetailFromAddressBar();
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  profileTargetUserId.value = userId;
  showProfileCenter.value = true;
  try {
    history.pushState({ myyoutubeProfile: String(userId) }, "", window.location.href);
  } catch {
    /* ignore */
  }
  activeMenuLabel.value = "个人中心";
  pageHint.value = "正在查看他人主页。";
  loadProfileUser({ withWorks: true });
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

function openRelationTable(type) {
  // 他人主页不允许查看关注/粉丝列表，也不请求对应接口
  if (!isViewingSelf.value) {
    return;
  }
  relationFollowerMenuId.value = null;
  if (type === "following") {
    relationTableKind.value = "following";
    relationTableTitle.value = "关注列表";
    relationTableUsers.value = followingRows.value;
    followingPageNo.value = 1;
    followingHasMore.value = true;
    void loadFollowingMore({ reset: true });
  } else {
    relationTableKind.value = "followers";
    relationTableTitle.value = "粉丝列表";
    relationTableUsers.value = followerRows.value;
    followerPageNo.value = 1;
    followerHasMore.value = true;
    void loadFollowersMore({ reset: true });
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
  void (async () => {
    try {
      await removeFan(user.id);
      followerRows.value = followerRows.value.filter((x) => String(x.id) !== String(user.id));
      if (relationTableKind.value === "followers") {
        relationTableUsers.value = followerRows.value;
      }
      if (profileUser.value && isViewingSelf.value) {
        const n = Number(profileUser.value.fans);
        if (!Number.isNaN(n) && n > 0) {
          profileUser.value = normalizeUserProfile({ ...profileUser.value, fans: n - 1 });
        }
      }
      pushToast(`已移除粉丝「${user.nickName || "该用户"}」`, "success");
    } catch (e) {
      pushToast(`移除失败：${e.message || e}`, "error");
    }
  })();
}

function onFollowerFollowBack(user) {
  relationFollowerMenuId.value = null;
  void (async () => {
    try {
      await followAuthor(user.id);
      pushToast(`已回关「${user.nickName || "该用户"}」`, "success");
    } catch (e) {
      pushToast(`回关失败：${e.message || e}`, "error");
    }
  })();
}

function onFollowingUnfollow(user) {
  void (async () => {
    try {
      await unfollowAuthor(user.id);
      followingRows.value = followingRows.value.filter((x) => String(x.id) !== String(user.id));
      if (relationTableKind.value === "following") {
        relationTableUsers.value = followingRows.value;
      }
      pushToast(`已取关「${user.nickName || "该用户"}」`, "success");
    } catch (e) {
      pushToast(`取关失败：${e.message || e}`, "error");
    }
  })();
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

/** 仅从列表项取播放地址，不再请求 /videos/detail */
function resolveVideoUrl(item) {
  if (!item || typeof item !== "object") return "";
  return (
    item.videoUrl ||
    item.playUrl ||
    item.url ||
    item.videoURL ||
    item.video_url ||
    ""
  );
}

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

function videoCardPlayable(card) {
  return Boolean(resolveVideoUrl(card));
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
  if (feedScope.value === "liked") {
    return likedFeedQueue.value;
  }
  if (feedScope.value === "follow") {
    const aid = followSubscribedFilterAuthorId.value;
    if (aid != null && aid !== "") {
      return followAuthorFeedQueue.value;
    }
    return followFeedQueue.value;
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

/** 评论侧栏：短视频流、搜索全屏放大或搜索列表共用同一套列表与接口 */
const activeCommentVideo = computed(() => {
  if (searchExpandCommentOpen.value && searchExpandCard.value) {
    return searchExpandCard.value;
  }
  if (searchListCommentOpen.value && searchListCommentCard.value) {
    return searchListCommentCard.value;
  }
  return shortsFeedCurrent.value;
});

const activeCommentVideoAllowsComment = computed(() => Number(activeCommentVideo.value?.allowComment ?? 1) === 1);

const searchExpandScrubMax = computed(() => {
  const d = Number(searchExpandDuration.value);
  if (Number.isFinite(d) && d > 0) return d;
  return 1;
});

const searchExpandScrubDisplayValue = computed(() => {
  const t = Number(searchExpandCurrentTime.value);
  const d = Number(searchExpandDuration.value);
  if (!Number.isFinite(t) || t < 0) return 0;
  if (Number.isFinite(d) && d > 0) return Math.min(t, d);
  return 0;
});

const searchExpandDurationReady = computed(() => {
  const d = Number(searchExpandDuration.value);
  return Number.isFinite(d) && d > 0;
});

const searchExpandLikedDisplay = computed(() => {
  const c = searchExpandCard.value;
  if (!c?.id) return false;
  return Boolean(c.isLike);
});

const searchExpandIsSelf = computed(() => {
  const authorId = searchExpandCard.value?.authorId;
  const selfId = currentUserId.value;
  if (authorId == null || selfId == null) return false;
  return String(authorId) === String(selfId);
});

const searchExpandIsFollowed = computed(() => {
  const c = searchExpandCard.value;
  if (!c?.authorId) return false;
  const key = String(c.authorId);
  if (Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)) {
    return followStateOverrideByAuthorId.value[key];
  }
  return Boolean(c.isFollow);
});

const shortsFeedVideoSrc = computed(() => resolveVideoUrl(shortsFeedCurrent.value));

watch(
  () => shortsFeedVideoSrc.value,
  (src) => {
    if (!src) {
      hideShortsBufferingOverlay();
    }
  }
);

/**
 * 短视频流红心：普通流看接口 isLike；赞过列表后端可能不传该字段，默认视为已赞，
 * 仅当本地明确 patch 为 isLike === false（取消赞）时显示空心。
 */
const shortsFeedLikedDisplay = computed(() => {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) {
    return false;
  }
  if (feedScope.value === "liked") {
    return cur.isLike !== false;
  }
  return Boolean(cur.isLike);
});

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

/** 从 video 元素读取有效时长，避免 NaN / Infinity / 未缓冲导致进度条错乱 */
function readVideoDurationFromVideoEl(v) {
  if (!v) {
    return 0;
  }
  let d = Number(v.duration);
  if (Number.isFinite(d) && d > 0 && d !== Number.POSITIVE_INFINITY) {
    return d;
  }
  try {
    if (v.seekable && v.seekable.length > 0) {
      const end = Number(v.seekable.end(v.seekable.length - 1));
      if (Number.isFinite(end) && end > 0 && end !== Number.POSITIVE_INFINITY) {
        return end;
      }
    }
  } catch {
    /* ignore */
  }
  return 0;
}

/**
 * 进度条：duration 未就绪时若 max 过小（曾用 0.01），而 currentTime 仍为上一段或中间值，
 * 原生 range 会把滑块钳到最右，看起来像「暂停就跳到结尾」或闪烁。
 */
const shortsScrubMax = computed(() => {
  const d = Number(shortsFeedDuration.value);
  if (Number.isFinite(d) && d > 0) {
    return d;
  }
  return 1;
});

const shortsScrubDisplayValue = computed(() => {
  const t = Number(shortsFeedCurrentTime.value);
  const d = Number(shortsFeedDuration.value);
  if (!Number.isFinite(t) || t < 0) {
    return 0;
  }
  if (Number.isFinite(d) && d > 0) {
    return Math.min(t, d);
  }
  return 0;
});

const shortsDurationReady = computed(() => {
  const d = Number(shortsFeedDuration.value);
  return Number.isFinite(d) && d > 0;
});

const shortsFeedAuthorName = computed(() => {
  const c = shortsFeedCurrent.value;
  const nick = stripUserIdLikeName(c?.authorNickname) || stripUserIdLikeName(c?.author);
  if (nick) return nick;
  if (feedScope.value === "profile") {
    const p = stripUserIdLikeName(profileUser.value?.nickName || profileUser.value?.nickname);
    if (p) {
      return p;
    }
    if (isViewingSelf.value) {
      const s = stripUserIdLikeName(selfUserProfile.value?.nickName || selfUserProfile.value?.nickname);
      if (s) {
        return s;
      }
    }
  }
  if (feedScope.value === "liked") {
    return nick || "创作者";
  }
  return "创作者";
});

const shortsFeedMetaTimeText = computed(() => formatRelativeTimeText(shortsFeedCurrent.value?.createTime || ""));
const shortsFeedTitleText = computed(() => shortsFeedCurrent.value?.title || "未命名主题");
const shortsFeedDescText = computed(
  () => shortsFeedCurrent.value?.description || shortsFeedCurrent.value?.title || ""
);
/** 关注 Tab：与左侧关注列表同一套 avatarDisplaySrc，避免视频 VO 里作者头像与侧栏不一致/糊片 */
const shortsFeedAuthorAvatar = computed(() => {
  const c = shortsFeedCurrent.value;
  if (!c) {
    return profileAvatar;
  }
  if (activeMenuKey.value === "subscribed" && feedScope.value === "follow" && c.authorId != null && c.authorId !== "") {
    const key = String(c.authorId);
    const row = followingRows.value.find((u) => u && String(u.id) === key);
    if (row?.avatarDisplaySrc) {
      return row.avatarDisplaySrc;
    }
  }
  const fromAuthor =
    sanitizeImageUrl(c.authorAvatarUrl ?? "") || sanitizeImageUrl(c.authorAvatar ?? "");
  if (fromAuthor) {
    return fromAuthor;
  }
  if (feedScope.value === "profile" && showProfileCenter.value) {
    if (isViewingSelf.value) {
      const selfAv = sanitizeImageUrl(selfUserProfile.value?.avatarUrl || "");
      if (selfAv) {
        return selfAv;
      }
      return topbarAvatarSrc.value;
    }
    const pav = sanitizeImageUrl(profileUser.value?.avatarUrl || "");
    if (pav) {
      return pav;
    }
  }
  return profileAvatar;
});

const shortsIsFollowedCurrent = computed(() => {
  const c = shortsFeedCurrent.value;
  if (!c?.authorId) {
    return false;
  }
  const key = String(c.authorId);
  if (Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)) {
    return followStateOverrideByAuthorId.value[key];
  }
  // 关注 Tab：侧栏点「某作者」时该作者必已关注；全部动态里作者应在关注列表中
  if (activeMenuKey.value === "subscribed" && feedScope.value === "follow") {
    const filtered = followSubscribedFilterAuthorId.value;
    if (filtered != null && filtered !== "" && String(filtered) === key) {
      return true;
    }
    if ((filtered == null || filtered === "") && followingRows.value.some((u) => u && String(u.id) === key)) {
      return true;
    }
  }
  return Boolean(c.isFollow);
});

const shortsIsSelfCurrent = computed(() => {
  const authorId = shortsFeedCurrent.value?.authorId;
  const selfId = currentUserId.value;
  if (authorId == null || selfId == null) {
    return false;
  }
  return String(authorId) === String(selfId);
});

async function toggleShortsFollow() {
  const c = shortsFeedCurrent.value;
  if (!c?.authorId) {
    return;
  }
  if (!isAuthed.value) {
    pushToast("请先登录后再关注", "info");
    return;
  }
  const aid = String(c.authorId);
  if (currentUserId.value != null && aid === String(currentUserId.value)) {
    return;
  }
  const key = aid;
  const currently = Object.prototype.hasOwnProperty.call(followStateOverrideByAuthorId.value, key)
    ? followStateOverrideByAuthorId.value[key]
    : Boolean(c.isFollow);
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

function toggleSelfVideoActionMenu() {
  selfVideoActionOpen.value = !selfVideoActionOpen.value;
}

function closeSelfVideoActionMenu() {
  selfVideoActionOpen.value = false;
}

function patchCurrentSelfVideo(partial) {
  const id = shortsFeedCurrent.value?.id;
  if (!id) return;
  patchFeedVideoById(id, partial);
  if (videoDetail.value?.id && String(videoDetail.value.id) === String(id)) {
    videoDetail.value = { ...videoDetail.value, ...partial };
  }
}

async function toggleCurrentSelfVideoVisibility() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  const v = String(cur.visibility || "").toUpperCase();
  const goingPrivate = v !== "PRIVATE";
  closeSelfVideoActionMenu();
  try {
    if (goingPrivate) {
      await setMyVideoPrivate(cur.id);
      patchCurrentSelfVideo({ visibility: "PRIVATE" });
      pushToast("已设为私密", "success");
    } else {
      await setMyVideoPublic(cur.id);
      patchCurrentSelfVideo({ visibility: "PUBLIC" });
      pushToast("已设为公开", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
}

async function toggleCurrentSelfVideoAllowComment() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  const current = Number(cur.allowComment ?? 1);
  const enable = current !== 1;
  closeSelfVideoActionMenu();
  try {
    if (enable) {
      await openMyVideoComment(cur.id);
      patchCurrentSelfVideo({ allowComment: 1 });
      pushToast("已开启评论", "success");
    } else {
      await closeMyVideoComment(cur.id);
      patchCurrentSelfVideo({ allowComment: 0 });
      pushToast("已关闭评论", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
}

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

function closeStudioRowMenu() {
  studioRowMenuOpenId.value = null;
}

function toggleStudioRowMenu(row, ev) {
  ev?.stopPropagation?.();
  const id = row?.id != null ? String(row.id) : "";
  if (!id) return;
  studioRowMenuOpenId.value = studioRowMenuOpenId.value === id ? null : id;
}

function studioRowVisibilityIsPrivate(row) {
  const v = row?.visibility?.code ?? row?.visibility?.name ?? row?.visibility ?? "";
  return String(v).toUpperCase().includes("PRIVATE");
}

async function onStudioRowToggleVisibility(row) {
  if (!row?.id) return;
  closeStudioRowMenu();
  const isPrivate = studioRowVisibilityIsPrivate(row);
  try {
    if (isPrivate) {
      await setMyVideoPublic(row.id);
      patchFeedVideoById(row.id, { visibility: "PUBLIC" });
      pushToast("已设为公开", "success");
    } else {
      await setMyVideoPrivate(row.id);
      patchFeedVideoById(row.id, { visibility: "PRIVATE" });
      pushToast("已设为私密", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
}

async function onStudioRowToggleComment(row) {
  if (!row?.id) return;
  closeStudioRowMenu();
  const current = Number(row.allowComment ?? 1);
  const enable = current !== 1;
  try {
    if (enable) {
      await openMyVideoComment(row.id);
      patchFeedVideoById(row.id, { allowComment: 1 });
      pushToast("已开启评论", "success");
    } else {
      await closeMyVideoComment(row.id);
      patchFeedVideoById(row.id, { allowComment: 0 });
      pushToast("已关闭评论", "success");
    }
  } catch (e) {
    pushToast(e?.message || "操作失败", "error");
  }
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

function shortsFeedPrev() {
  const cur = shortsFeedCurrent.value;
  const dur = Number(shortsFeedDuration.value || 0);
  const now = Number(shortsFeedCurrentTime.value || 0);
  if (isLongShortsFeedVideo(cur)) {
    void flushShortsLongPlaybackResume("switch");
  }
  if (cur?.id) {
    enqueueShortsHistoryByVideoId(cur.id, cur);
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
    enqueueShortsHistoryByVideoId(cur.id, cur);
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

function onShortsFeedFgLoadStart() {
  scheduleShortsBufferingOverlay();
}

function onShortsFeedFgCanPlay() {
  hideShortsBufferingOverlay();
}

function onShortsFeedFgMediaError() {
  hideShortsBufferingOverlay();
}

async function onShortsFeedFgLoadedMetadata(e) {
  const v = e.target;
  const tokenOfEl = Number(v?.dataset?.playToken || 0);
  applyShortsLongPlaybackResumeIfNeeded(v);
  shortsFeedIsPortrait.value =
    v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
  const dur = readVideoDurationFromVideoEl(v);
  shortsFeedDuration.value = dur;
  let ct = Number(v.currentTime) || 0;
  if (dur > 0) {
    ct = Math.min(ct, dur);
  }
  shortsFeedCurrentTime.value = ct;
  v.volume = shortsFeedVolume.value;
  v.muted = shortsFeedMuted.value;
  shortsFeedVolume.value = v.volume;
  shortsFeedMuted.value = v.muted;
  if (shortsFeedAutoplayPending.value) {
    try {
      const inMainShorts =
        activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed";
      if (!inMainShorts && !profileFeedOpen.value) {
        return;
      }
      if (tokenOfEl !== shortsFeedPlayToken) {
        return;
      }
      await v.play();
      await shortsFeedBgRef.value?.play().catch(() => {});
    } catch {
      /* 浏览器策略可能阻止自动播放，此时显示中央播放按钮 */
    } finally {
      shortsFeedAutoplayPending.value = false;
    }
  }
  shortsFeedPaused.value = v.paused;
}

function onShortsFeedFgTimeUpdate(e) {
  const inMainShorts =
    activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed";
  if (!inMainShorts && !profileFeedOpen.value) {
    return;
  }
  const fg = e.target;
  const dur = readVideoDurationFromVideoEl(fg);
  if (dur > 0) {
    const prev = shortsFeedDuration.value;
    if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
      shortsFeedDuration.value = dur;
    }
  }
  let ct = Number(fg.currentTime) || 0;
  if (dur > 0) {
    ct = Math.min(ct, dur);
  }
  shortsFeedCurrentTime.value = ct;
  const cur = shortsFeedCurrent.value;
  const duration = dur > 0 ? dur : Number(fg.duration);
  const current = ct;
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
  const dur = readVideoDurationFromVideoEl(v);
  if (!(dur > 0)) {
    return;
  }
  const t = Math.min(Math.max(0, Number(e.target.value)), dur);
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
  if (showProfileCenter.value || (activeMenuKey.value !== "shorts" && activeMenuKey.value !== "subscribed")) {
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
    if (player.paused) {
      detailUserPausedManually.value = false;
      player.play().catch(() => {});
    } else {
      detailUserPausedManually.value = true;
      player.pause();
    }
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

  onDetailPageKeydown(ev);
}

function onShortsFeedFgPlay() {
  shortsFeedPaused.value = false;
  const curPlay = shortsFeedCurrent.value;
  enqueueShortsHistoryByVideoId(curPlay?.id, curPlay);
  if (isLongShortsFeedVideo()) {
    shortsLongResumeTickCount = 0;
  }
  shortsFeedBgRef.value?.play().catch(() => {});
}

function onShortsFeedFgPause() {
  if (shortsFeedAutoplayPending.value) {
    return;
  }
  shortsFeedPaused.value = true;
  const fg = shortsFeedFgRef.value;
  if (fg) {
    const dur = readVideoDurationFromVideoEl(fg);
    if (dur > 0) {
      shortsFeedDuration.value = dur;
    }
    let ct = Number(fg.currentTime) || 0;
    if (dur > 0) {
      ct = Math.min(ct, dur);
    }
    shortsFeedCurrentTime.value = ct;
  }
  if (isLongShortsFeedVideo()) {
    void flushShortsLongPlaybackResume("pause");
  }
  shortsFeedBgRef.value?.pause();
}

function onShortsFeedFgDurationChange(e) {
  const v = e.target;
  const dur = readVideoDurationFromVideoEl(v);
  if (dur > 0) {
    const prev = shortsFeedDuration.value;
    if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
      shortsFeedDuration.value = dur;
    }
    let ct = Number(v.currentTime) || 0;
    shortsFeedCurrentTime.value = dur > 0 ? Math.min(ct, dur) : ct;
  }
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
    const authTok = getToken();
    if (authTok) headers.Authorization = authTok;
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

async function onShortsLikeClick() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录后再点赞", "info");
    return;
  }
  const t = Date.now();
  if (t - lastShortsLikeAt < 300) return;
  lastShortsLikeAt = t;
  if (shortsLikeBusy.value) return;
  const vid = parseVideoIdForApi(cur.id);
  if (vid == null) return;
  shortsLikeBusy.value = true;
  try {
    const wasLiked = shortsFeedLikedDisplay.value;
    if (wasLiked) {
      await cancelLikeVideoApi(vid);
      patchFeedVideoById(cur.id, { isLike: false, likeCount: Math.max(0, Number(cur.likeCount ?? 0) - 1) });
    } else {
      await likeVideoApi(vid);
      patchFeedVideoById(cur.id, {
        isLike: true,
        likeCount: Number(cur.likeCount ?? 0) + 1,
      });
    }
    const raw = await fetchVideoDetail(vid);
    const merged = normalizeVideoDetail(raw);
    if (merged && merged.likeCount != null) {
      patchFeedVideoById(cur.id, { likeCount: merged.likeCount });
    }
    void reportBehaviorSilently(cur.id, "LIKE", Number(shortsFeedCurrentTime.value || 0) * 1000);
  } catch (error) {
    pushToast(error.message || "点赞失败", "error");
  } finally {
    shortsLikeBusy.value = false;
  }
}

async function onShortsDislikeClick() {
  const cur = shortsFeedCurrent.value;
  if (!cur?.id) return;
  if (!isAuthed.value) {
    pushToast("请先登录", "info");
    return;
  }
  const vid = parseVideoIdForApi(cur.id);
  if (vid == null) return;
  try {
    await dislikeVideoApi(vid);
    void reportBehaviorSilently(cur.id, "DISLIKE", Number(shortsFeedCurrentTime.value || 0) * 1000);
    pushToast("已标记为不感兴趣", "success");
  } catch (error) {
    pushToast(error.message || "操作失败", "error");
  }
}

function pauseShortsFeedVideos({ muteForeground = false } = {}) {
  const fg = shortsFeedFgRef.value;
  const bg = shortsFeedBgRef.value;
  fg?.pause();
  bg?.pause();
  if (muteForeground && fg) {
    try {
      fg.muted = true;
    } catch {
      /* ignore */
    }
  }
}

/** 从详情页等遮罩返回时，把前景音量状态与 UI 同步，避免误静音 */
function restoreShortsFeedForegroundMuteFromState() {
  const fg = shortsFeedFgRef.value;
  if (!fg) return;
  try {
    fg.muted = shortsFeedMuted.value;
    fg.volume = shortsFeedVolume.value;
  } catch {
    /* ignore */
  }
}

/** 切换作者/数据源时重置底部进度条状态（index 仍为 0 时 watch 不会触发） */
function resetShortsFeedProgressUiForSwitch() {
  shortsFeedIsPortrait.value = false;
  shortsFeedAutoplayPending.value = true;
  shortsFeedDuration.value = 0;
  shortsFeedCurrentTime.value = 0;
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
  shortsLongResumeTickCount = 0;
  shortsLongResumeAppliedVideoId = "";
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
  clearCapturedProfileReturnContext();
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
  showProfileCenter.value = false;
  showStudio.value = false;
  uploadModalOpen.value = false;
  profileFeedOpen.value = false;
  clearCapturedProfileReturnContext();
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
    const returnCtx = profileReturnContextRef.value || readPersistedProfileReturnContext();
    showProfileCenter.value = false;
    profileTargetUserId.value = null;
    if (hadProfile && returnCtx?.kind === "shortsFeed") {
      clearCapturedProfileReturnContext();
      void restoreProfileReturnContext(returnCtx);
      return;
    }
    const vidRestore = vParam || (returnCtx?.kind === "detail" ? returnCtx.videoId : null);
    if (hadProfile && vidRestore) {
      if (returnCtx?.menuKey) {
        applyMenuKeyIfKnown(returnCtx.menuKey);
      }
      clearCapturedProfileReturnContext();
      void restoreVideoDetailFromUrl(vidRestore);
      return;
    }
    clearCapturedProfileReturnContext();
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
    hideDetailBufferingOverlay();
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
    if (searchExpandOpen.value) {
      void flushSearchExpandPlaybackResume("unload");
    }
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
      await loadProfileUser({ withWorks: true });
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
  if (searchExpandEscapeHandler) {
    window.removeEventListener("keydown", searchExpandEscapeHandler);
    searchExpandEscapeHandler = null;
  }
  if (searchSuggestBlurTimer) clearTimeout(searchSuggestBlurTimer);
  if (searchPromptDebounceTimer) clearTimeout(searchPromptDebounceTimer);
  clearDetailBufferingUITimer();
  clearShortsBufferingUITimer();
  stopNotifyUnreadPoll();
  stopNotifyWebSocket();
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
              <div v-else class="studio-table-panel" @click="closeStudioRowMenu">
                <table class="studio-table" @click.stop>
                  <thead>
                    <tr>
                      <th>视频</th>
                      <th>公开范围</th>
                      <th>日期</th>
                      <th>观看次数</th>
                      <th class="studio-th-actions" aria-label="操作"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!studioRows.length">
                      <td colspan="5" class="studio-table-empty">没有可显示的内容</td>
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
                      <td class="studio-td-actions">
                        <button
                          type="button"
                          class="studio-row-more-btn"
                          aria-label="更多操作"
                          @click.stop="toggleStudioRowMenu(row, $event)"
                        >
                          <i class="ri-more-2-fill"></i>
                        </button>
                        <div
                          v-if="studioRowMenuOpenId === String(row.id)"
                          class="studio-row-action-menu"
                          @click.stop
                        >
                          <button type="button" class="studio-row-action-btn" @click="onStudioRowToggleVisibility(row)">
                            {{ studioRowVisibilityIsPrivate(row) ? "设为公开" : "设为私密" }}
                          </button>
                          <button type="button" class="studio-row-action-btn" @click="onStudioRowToggleComment(row)">
                            {{ Number(row.allowComment ?? 1) === 1 ? "关闭评论" : "开启评论" }}
                          </button>
                          <button
                            type="button"
                            class="studio-row-action-btn studio-row-action-btn--danger"
                            @click="onStudioRowRequestDelete(row)"
                          >
                            删除
                          </button>
                        </div>
                      </td>
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
        <SearchView v-if="searchActive" :scroll-ref="searchFeedScrollRef" />
        <Teleport to="body">
          <div
            v-if="searchActive && searchExpandOpen && searchExpandCard"
            class="search-expand-overlay search-expand-overlay--fullscreen"
            role="dialog"
            aria-modal="true"
            aria-label="全屏播放"
          >
            <div class="search-expand-shorts-shell">
              <div ref="searchExpandStageRef" class="shorts-feed-center search-expand-shorts-center">
                <button type="button" class="search-expand-close" aria-label="关闭" @click="closeSearchExpand">
                  <i class="ri-close-line"></i>
                </button>
                <div class="shorts-feed-stage-wrap">
                  <div
                    class="short-video-stage short-video-stage--feed"
                    :class="{
                      'short-video-stage--portrait': searchExpandIsPortrait,
                      'short-video-stage--landscape': !searchExpandIsPortrait,
                    }"
                  >
                    <video
                      ref="searchExpandBlurRef"
                      class="short-video-bg"
                      muted
                      loop
                      playsinline
                      preload="metadata"
                      :src="searchExpandVideoUrl()"
                    />
                    <video
                      ref="searchExpandPlayerRef"
                      class="short-video-fg"
                      playsinline
                      preload="metadata"
                      :poster="searchExpandCard?.coverUrl || searchExpandCard?.avatarUrl"
                      :src="searchExpandVideoUrl()"
                      @click="toggleSearchExpandPlay"
                      @timeupdate="onSearchExpandVideoTimeUpdate"
                      @loadedmetadata="onSearchExpandVideoLoadedMetadata"
                      @seeked="onSearchExpandFgSeeked"
                      @play="onSearchExpandFgPlay"
                      @pause="onSearchExpandFgPause"
                    />
                    <div
                      v-show="searchExpandPaused"
                      class="shorts-feed-pause-overlay"
                      @click.stop="toggleSearchExpandPlay"
                    >
                      <span class="shorts-feed-pause-overlay__icon" aria-hidden="true">
                        <i class="ri-play-fill"></i>
                      </span>
                    </div>
                  </div>
                  <div class="shorts-feed-nav-side" aria-label="切换上一条下一条">
                    <button type="button" class="shorts-feed-nav-line" title="上一条" @click.stop="searchExpandPrev">
                      <i class="ri-arrow-up-s-line"></i>
                    </button>
                    <button type="button" class="shorts-feed-nav-line" title="下一条" @click.stop="searchExpandNext">
                      <i class="ri-arrow-down-s-line"></i>
                    </button>
                  </div>
                  <div class="shorts-feed-ui">
                  <div class="shorts-feed-caption">
                    <p class="shorts-feed-user">
                      <button
                        v-if="searchExpandCard?.authorId != null && searchExpandCard?.authorId !== ''"
                        type="button"
                        class="shorts-feed-user-link"
                        title="查看作者主页"
                        @click.stop="onAuthorAvatarClick($event, searchExpandCard.authorId)"
                      >
                        @{{ searchExpandCard.authorNickname || searchExpandCard.author }}
                      </button>
                      <template v-else>@{{ searchExpandCard.authorNickname || searchExpandCard.author }}</template>
                      <span v-if="formatSearchRelTime(searchExpandCard)" class="shorts-feed-time-dot"> · {{ formatSearchRelTime(searchExpandCard) }}</span>
                    </p>
                    <p class="shorts-feed-topic">{{ searchExpandCard.title }}</p>
                    <p class="shorts-feed-desc">
                      {{ searchExpandCard.description || "暂无描述" }}
                      <span v-if="parseSearchTagsArray(searchExpandCard).length" class="search-feed-tag-row">
                        <button
                          v-for="tg in parseSearchTagsArray(searchExpandCard)"
                          :key="'extg-' + searchExpandCard.id + '-' + tg"
                          type="button"
                          class="search-feed-tag-chip"
                          @click.stop="onSearchTagNavigate(tg)"
                        >
                          {{ tg.startsWith("#") ? tg : "#" + tg }}
                        </button>
                      </span>
                    </p>
                  </div>
                  <div class="shorts-feed-action-col">
                    <div class="shorts-feed-author-block">
                      <div class="shorts-feed-author-avatar-wrap">
                        <img
                          class="shorts-feed-author-avatar"
                          :src="cardAuthorAvatar(searchExpandCard, searchExpandIndex)"
                          alt=""
                          role="button"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, searchExpandCard.authorId)"
                          @error="(e) => (e.target.src = profileAvatar)"
                        />
                        <button
                          v-if="!searchExpandIsSelf && !searchExpandIsFollowed"
                          type="button"
                          class="shorts-feed-follow-btn"
                          title="关注"
                          aria-label="关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-add-line"></i>
                        </button>
                        <button
                          v-else-if="!searchExpandIsSelf"
                          type="button"
                          class="shorts-feed-follow-btn shorts-feed-follow-btn--followed"
                          title="已关注"
                          aria-label="已关注"
                          @click.stop="toggleSearchExpandFollow"
                        >
                          <i class="ri-check-line"></i>
                        </button>
                      </div>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        :class="{ 'shorts-feed-action-btn--active': searchExpandLikedDisplay }"
                        title="点赞 / 取消赞"
                        aria-label="点赞"
                        @click.stop="onSearchExpandLikeClick"
                      >
                        <i :class="searchExpandLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.likeCount ?? 0 }}</span>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        title="评论"
                        aria-label="评论"
                        @click.stop="toggleSearchExpandCommentPanel"
                      >
                        <i class="ri-chat-3-line"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.commentCount ?? 0 }}</span>
                    </div>
                    <div v-if="!searchExpandIsSelf" class="shorts-feed-action-item">
                      <button
                        type="button"
                        class="shorts-feed-action-btn"
                        title="不感兴趣"
                        aria-label="不感兴趣"
                        @click.stop="onSearchExpandDislikeClick"
                      >
                        <i class="ri-thumb-down-line"></i>
                      </button>
                    </div>
                    <div class="shorts-feed-action-item">
                      <button type="button" class="shorts-feed-action-btn" title="分享" aria-label="分享" @click.stop="onSearchExpandShareClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <span class="shorts-feed-action-count">{{ searchExpandCard?.shareCount ?? 0 }}</span>
                    </div>
                  </div>
                </div>
                <div class="shorts-feed-bottom-bar shorts-feed-bottom-bar--search-expand">
                  <input
                    type="range"
                    class="shorts-feed-scrub"
                    min="0"
                    :max="searchExpandScrubMax"
                    step="0.1"
                    :disabled="!searchExpandDurationReady"
                    :value="searchExpandScrubDisplayValue"
                    @input="onSearchExpandScrubInput"
                  />
                  <div class="shorts-feed-bottom-bar__row">
                    <span class="shorts-feed-time">
                      {{ formatShortsTime(searchExpandCurrentTime) }} / {{ formatShortsTime(searchExpandDuration) }}
                    </span>
                    <div class="shorts-feed-tools">
                      <button type="button" class="shorts-feed-tool-btn" title="小窗播放（画中画）" @click="searchExpandRequestPip">
                        <i class="ri-picture-in-picture-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-tool-btn" title="静音" @click="searchExpandToggleMute">
                        <i :class="searchExpandMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
                      </button>
                      <input
                        type="range"
                        class="shorts-feed-vol-range"
                        min="0"
                        max="1"
                        step="0.05"
                        :value="searchExpandVolume"
                        title="音量"
                        aria-label="音量"
                        @input="onSearchExpandVolumeInput"
                      />
                      <button type="button" class="shorts-feed-tool-btn" title="全屏" @click="searchExpandRequestFullscreen">
                        <i class="ri-fullscreen-line"></i>
                      </button>
                      <button type="button" class="shorts-feed-tool-btn" title="转发" aria-label="转发" @click="onSearchExpandForwardClick">
                        <i class="ri-share-forward-line"></i>
                      </button>
                      <button
                        type="button"
                        class="shorts-feed-tool-btn shorts-feed-tool-btn--playpause"
                        :title="searchExpandPaused ? '播放' : '暂停'"
                        @click="toggleSearchExpandPlay"
                      >
                        <i :class="searchExpandPaused ? 'ri-play-fill' : 'ri-pause-fill'"></i>
                      </button>
                    </div>
                  </div>
                </div>
                </div>
              </div>
            </div>
          </div>
        </Teleport>
        <Teleport to="body">
              <aside
                v-if="shortsCommentPanelOpen || searchExpandCommentOpen || searchListCommentOpen"
                class="shorts-feed-comments-panel shorts-feed-comments-panel--floating"
                aria-label="评论"
                @click.stop
              >
                <div class="shorts-feed-comments-panel-head">
                  <h3>评论 {{ activeCommentVideo?.commentCount != null ? `（${activeCommentVideo.commentCount}）` : "" }}</h3>
                  <button
                    type="button"
                    class="shorts-feed-comments-panel-close"
                    aria-label="关闭评论"
                    @click="closeShortsCommentPanel"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                </div>
                <span v-if="!activeCommentVideoAllowsComment" class="video-detail-comments-note">作者已关闭评论</span>
                <p v-if="shortsPanelReplyTarget" class="video-detail-comment-reply-hint">
                  回复 @{{ commentDisplayNickname(shortsPanelReplyTarget.userId, shortsPanelReplyTarget.nickname) }}
                  <button type="button" class="video-detail-comment-reply-cancel" @click="setShortsPanelReplyTarget(null)">
                    取消
                  </button>
                </p>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input
                    ref="shortsPanelCommentInputRef"
                    v-model="shortsPanelCommentDraft"
                    type="text"
                    class="video-detail-comment-field"
                    :disabled="!isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting"
                    :placeholder="
                      !isAuthed
                        ? '登录后参与评论…'
                        : !activeCommentVideoAllowsComment
                          ? '评论已关闭'
                          : shortsPanelReplyTarget
                            ? `回复 @${commentDisplayNickname(shortsPanelReplyTarget.userId, shortsPanelReplyTarget.nickname)}…`
                            : '添加评论…'
                    "
                    maxlength="500"
                    @keyup.enter.prevent="submitShortsPanelComment"
                  />
                  <div class="video-detail-comment-input-actions">
                    <ChatEmojiPicker
                      :disabled="!isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting"
                      @pick="onShortsPanelCommentEmojiPick"
                    />
                    <button
                      type="button"
                      class="video-detail-comment-send"
                      :disabled="
                        !isAuthed || !activeCommentVideoAllowsComment || shortsPanelCommentSubmitting || !shortsPanelCommentDraft.trim()
                      "
                      @click="submitShortsPanelComment"
                    >
                      {{ shortsPanelCommentSubmitting ? "发送中…" : "发送" }}
                    </button>
                  </div>
                </div>
                <div class="shorts-feed-comments-panel-scroll">
                  <p v-if="shortsPanelCommentsLoading && !shortsPanelTopComments.length" class="video-detail-comments-empty">
                    加载评论中…
                  </p>
                  <ul v-else-if="shortsPanelTopComments.length" class="video-detail-comments-mock">
                    <template v-for="c in shortsPanelTopComments" :key="`sp-top-${c.id}`">
                      <li class="video-detail-comment-item">
                        <img
                          :src="c.avatarUrl || profileAvatar"
                          alt=""
                          class="video-detail-comment-avatar video-detail-comment-avatar--clickable"
                          role="button"
                          tabindex="0"
                          title="查看用户主页"
                          @click.stop="onShortsPanelCommentUserClick($event, c.userId)"
                          @keyup.enter.stop="onShortsPanelCommentUserClick($event, c.userId)"
                          @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                        />
                        <div class="video-detail-comment-body">
                          <div class="video-detail-comment-meta">
                            <button
                              type="button"
                              class="video-detail-comment-user video-detail-comment-user--link"
                              @click.stop="onShortsPanelCommentUserClick($event, c.userId)"
                            >
                              {{ commentDisplayNickname(c.userId, c.nickname) }}
                            </button>
                            <span class="video-detail-comment-time">{{ formatDetailCommentTime(c.createTime) }}</span>
                          </div>
                          <p class="video-detail-comment-text">{{ c.content }}</p>
                          <div class="video-detail-comment-actions">
                            <button
                              type="button"
                              class="video-detail-comment-like-btn"
                              :class="{ 'video-detail-comment-like-btn--active': c.isLike }"
                              :disabled="isCommentLikeBusy(c.id)"
                              :aria-pressed="c.isLike ? 'true' : 'false'"
                              :aria-label="c.isLike ? '取消点赞' : '点赞'"
                              @click="submitCommentLike(c)"
                            >
                              <i
                                :class="c.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                aria-hidden="true"
                              ></i>
                              {{ c.likeCount ?? 0 }}
                            </button>
                            <button
                              v-if="isAuthed && activeCommentVideoAllowsComment"
                              type="button"
                              class="video-detail-comment-action-btn"
                              @click="setShortsPanelReplyTarget(c)"
                            >
                              回复
                            </button>
                            <button
                              v-if="isAuthed && currentUserId != null && String(c.userId) === String(currentUserId)"
                              type="button"
                              class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                              @click="onDeleteShortsPanelComment(c)"
                            >
                              删除
                            </button>
                          <span
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            class="video-detail-comment-actions-spacer"
                          ></span>
                          <button
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            type="button"
                            class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                            title="举报"
                            @click="openCommentReportModal(c)"
                          >
                            <i class="ri-flag-line" aria-hidden="true"></i>举报
                          </button>
                          </div>
                          <button
                            v-if="c.replyCount > 0"
                            type="button"
                            class="video-detail-thread-toggle"
                            @click="toggleShortsPanelThread(c.id)"
                          >
                            {{
                              shortsPanelExpandedRoots[String(c.id)]
                                ? "收起回复"
                                : `展开 ${c.replyCount} 条回复`
                            }}
                          </button>
                          <ul v-if="shortsPanelExpandedRoots[String(c.id)]" class="video-detail-comment-replies">
                            <li
                              v-for="r in shortsPanelThreadByRootId[String(c.id)]?.list || []"
                              :key="`sp-r-${r.id}`"
                              class="video-detail-comment-item video-detail-comment-item--reply"
                            >
                              <img
                                :src="r.avatarUrl || profileAvatar"
                                alt=""
                                class="video-detail-comment-avatar video-detail-comment-avatar--sm video-detail-comment-avatar--clickable"
                                role="button"
                                tabindex="0"
                                title="查看用户主页"
                                @click.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                @keyup.enter.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                              />
                              <div class="video-detail-comment-body">
                                <div class="video-detail-comment-meta">
                                  <button
                                    type="button"
                                    class="video-detail-comment-user video-detail-comment-user--link"
                                    @click.stop="onShortsPanelCommentUserClick($event, r.userId)"
                                  >
                                    {{ commentDisplayNickname(r.userId, r.nickname) }}
                                  </button>
                                  <span
                                    v-if="r.replyNickname"
                                    class="video-detail-comment-arrow"
                                    aria-hidden="true"
                                    title="回复"
                                  >▶</span>
                                  <button
                                    v-if="r.replyNickname && r.replyUserId != null && r.replyUserId !== ''"
                                    type="button"
                                    class="video-detail-comment-at video-detail-comment-at--link"
                                    @click.stop="onShortsPanelCommentUserClick($event, r.replyUserId)"
                                  >
                                    @{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}
                                  </button>
                                  <span v-else-if="r.replyNickname" class="video-detail-comment-at"
                                    >@{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}</span>
                                  <span class="video-detail-comment-time">{{ formatDetailCommentTime(r.createTime) }}</span>
                                </div>
                                <p class="video-detail-comment-text">{{ r.content }}</p>
                                <div class="video-detail-comment-actions">
                                  <button
                                    type="button"
                                    class="video-detail-comment-like-btn"
                                    :class="{ 'video-detail-comment-like-btn--active': r.isLike }"
                                    :disabled="isCommentLikeBusy(r.id)"
                                    :aria-pressed="r.isLike ? 'true' : 'false'"
                                    :aria-label="r.isLike ? '取消点赞' : '点赞'"
                                    @click="submitCommentLike(r)"
                                  >
                                    <i
                                      :class="r.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                      aria-hidden="true"
                                    ></i>
                                    {{ r.likeCount ?? 0 }}
                                  </button>
                                  <button
                                    v-if="isAuthed && activeCommentVideoAllowsComment"
                                    type="button"
                                    class="video-detail-comment-action-btn"
                                    @click="setShortsPanelReplyTarget(r)"
                                  >
                                    回复
                                  </button>
                                  <button
                                    v-if="isAuthed && currentUserId != null && String(r.userId) === String(currentUserId)"
                                    type="button"
                                    class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                                    @click="onDeleteShortsPanelComment(r)"
                                  >
                                    删除
                                  </button>
                                  <span
                                    v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                    class="video-detail-comment-actions-spacer"
                                  ></span>
                                  <button
                                    v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                    type="button"
                                    class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                                    title="举报"
                                    @click="openCommentReportModal(r)"
                                  >
                                    <i class="ri-flag-line" aria-hidden="true"></i>举报
                                  </button>
                                </div>
                              </div>
                            </li>
                            <li
                              v-if="shortsPanelThreadByRootId[String(c.id)]?.loading"
                              class="video-detail-comment-item video-detail-comment-item--reply"
                            >
                              <span class="video-detail-comments-empty">加载回复中…</span>
                            </li>
                          </ul>
                        </div>
                      </li>
                    </template>
                  </ul>
                  <p v-if="!shortsPanelCommentsLoading && !shortsPanelTopComments.length" class="video-detail-comments-empty">
                    暂无评论，来抢沙发吧
                  </p>
                  <div v-if="shortsPanelCommentHasMore()" class="video-detail-comments-more">
                    <button
                      type="button"
                      class="history-layout-btn"
                      :disabled="shortsPanelCommentsLoading"
                      @click="loadMoreShortsPanelComments"
                    >
                      {{ shortsPanelCommentsLoading ? "加载中…" : "加载更多评论" }}
                    </button>
                  </div>
                </div>
              </aside>
        </Teleport>
        <template v-if="!searchActive">
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
                <button
                  v-if="isViewingSelf"
                  type="button"
                  class="profile-stat-row profile-stat-btn"
                  @click="openRelationTable('following')"
                >
                  <span class="profile-stat-left">关注 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
                </button>
                <div v-else class="profile-stat-row">
                  <span>关注</span>
                  <strong>{{ profileUser?.attentionCount ?? "—" }}</strong>
                </div>
                <button
                  v-if="isViewingSelf"
                  type="button"
                  class="profile-stat-row profile-stat-btn"
                  @click="openRelationTable('followers')"
                >
                  <span class="profile-stat-left">粉丝 <i class="ri-play-fill stat-triangle"></i></span>
                  <strong>{{ profileUser?.fans ?? "—" }}</strong>
                </button>
                <div v-else class="profile-stat-row">
                  <span>粉丝</span>
                  <strong>{{ profileUser?.fans ?? "—" }}</strong>
                </div>
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
                  <button
                    v-if="!isViewingSelf && isAuthed"
                    type="button"
                    class="profile-page-follow-btn"
                    :disabled="profileFollowBusy"
                    @click="toggleProfileTargetFollow"
                  >
                    {{ profileTargetFollowed ? "已关注" : "+ 关注" }}
                  </button>
                  <button
                    v-if="!isViewingSelf && isAuthed"
                    type="button"
                    class="profile-works-filter-btn"
                    :disabled="profileChatBusy"
                    title="与对方发起私聊"
                    @click="onProfileWorksChatClick"
                  >
                    {{ profileChatBusy ? "创建中…" : "聊一聊" }}
                  </button>
                  <div v-if="isViewingSelf" class="profile-works-filters">
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
                    <img
                      class="work-author-avatar"
                      :src="item.authorAvatarUrl || profileAvatar"
                      alt=""
                    />
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
                    <th class="relation-th-nick">昵称</th>
                    <th class="relation-th-intro">简介</th>
                    <th class="relation-th-action">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(user, relIdx) in relationTableUsers" :key="`rel-${relIdx}-${String(user.id)}`">
                    <td>{{ user.id }}</td>
                    <td>
                      <img
                        class="relation-avatar"
                        :src="user.avatarDisplaySrc || profileAvatar"
                        alt=""
                        role="button"
                        title="查看用户主页"
                        @click.stop="onAuthorAvatarClick($event, user.id)"
                        @error="(e) => { const t = e.target; if (!t || t.dataset.fallbackApplied === '1') return; t.dataset.fallbackApplied = '1'; t.src = profileAvatar; }"
                      />
                    </td>
                    <td class="relation-nick-cell">
                      <div
                        class="relation-name"
                        role="button"
                        tabindex="0"
                        title="查看用户主页"
                        @click.stop="onAuthorAvatarClick($event, user.id)"
                        @keyup.enter.stop="onAuthorAvatarClick($event, user.id)"
                      >
                        {{ user.nickName }}
                      </div>
                    </td>
                    <td class="relation-intro-cell">
                      <span class="relation-intro-text">{{ user.introduction?.trim() || "—" }}</span>
                    </td>
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
            <div class="relation-table-more">
              <button
                v-if="relationTableKind === 'following' && followingHasMore"
                type="button"
                class="history-layout-btn"
                :disabled="followingLoading"
                @click="loadFollowingNextPage"
              >
                {{ followingLoading ? "加载中…" : "加载更多" }}
              </button>
              <button
                v-if="relationTableKind === 'followers' && followerHasMore"
                type="button"
                class="history-layout-btn"
                :disabled="followerLoading"
                @click="loadFollowersNextPage"
              >
                {{ followerLoading ? "加载中…" : "加载更多" }}
              </button>
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

        <div
          v-if="profileFeedOpen || (!showProfileCenter && (activeMenuKey === 'shorts' || activeMenuKey === 'subscribed'))"
          class="shorts-feed-shell"
          :class="{ 'shorts-feed-shell--with-sidebar': activeMenuKey === 'subscribed' && !profileFeedOpen }"
        >
          <aside
            v-if="activeMenuKey === 'subscribed' && !profileFeedOpen"
            class="follow-sidebar"
            aria-label="我的关注"
          >
            <div class="follow-sidebar-head">关注的人</div>
            <button
              type="button"
              class="follow-sidebar-row follow-sidebar-row--all"
              :class="{
                'follow-sidebar-row--active': followSubscribedFilterAuthorId == null || followSubscribedFilterAuthorId === '',
              }"
              @click="selectFollowSidebarAll"
            >
              <span class="follow-sidebar-all-icon" aria-hidden="true"><i class="ri-play-list-2-line"></i></span>
              <span class="follow-sidebar-name">全部动态</span>
            </button>
            <div class="follow-sidebar-list">
              <p v-if="!isAuthed" class="follow-sidebar-hint">请先登录</p>
              <template v-else>
                <p v-if="followingLoading && !followingRows.length" class="follow-sidebar-hint">加载中…</p>
                <p v-else-if="!followingRows.length" class="follow-sidebar-hint">暂无关注，去发现页关注作者吧</p>
                <div
                  v-for="(u, uIdx) in followingRows"
                  :key="`follow-side-${uIdx}-${String(u.id)}`"
                  role="button"
                  tabindex="0"
                  class="follow-sidebar-row"
                  :class="{ 'follow-sidebar-row--active': String(followSubscribedFilterAuthorId) === String(u.id) }"
                  @click="selectFollowSidebarAuthor(u)"
                  @keydown.enter.prevent="selectFollowSidebarAuthor(u)"
                  @keydown.space.prevent="selectFollowSidebarAuthor(u)"
                >
                  <img
                    class="follow-sidebar-avatar"
                    :src="u.avatarDisplaySrc"
                    alt=""
                    loading="lazy"
                    decoding="async"
                    @error="(e) => { const t = e.target; if (!t || t.dataset.fallbackApplied === '1') return; t.dataset.fallbackApplied = '1'; t.src = profileAvatar; }"
                  />
                  <span class="follow-sidebar-name">{{ u.nickName || "用户" }}</span>
                </div>
                <button
                  v-if="followingHasMore"
                  type="button"
                  class="follow-sidebar-more"
                  :disabled="followingLoading"
                  @click="loadFollowingNextPage"
                >
                  {{ followingLoading ? "加载中…" : "加载更多" }}
                </button>
              </template>
            </div>
          </aside>
          <section
            :class="[
              profileFeedOpen ? 'profile-feed-overlay' : 'shorts-feed',
              { 'shorts-feed--follow-main': activeMenuKey === 'subscribed' && !profileFeedOpen },
            ]"
          >
          <button
            v-if="profileFeedOpen"
            type="button"
            class="profile-feed-close"
            :class="{ 'profile-feed-close--comments-open': shortsCommentPanelOpen }"
            :aria-label="feedScope === 'liked' ? '关闭赞过视频连播' : '关闭作品连播'"
            @click="closeProfileFeed"
          >
            <i class="ri-close-line"></i>
          </button>
          <div v-if="loading && !profileFeedOpen" class="shorts-feed-loading">
            {{ activeMenuKey === "subscribed" ? "正在加载关注动态…" : "正在加载短视频…" }}
          </div>
          <template v-else-if="shortsFeedVideoSrc">
            <div class="shorts-feed-body" :class="{ 'shorts-feed-body--comments-open': shortsCommentPanelOpen }">
              <div class="shorts-feed-player-column">
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
                      :key="shortsFeedCurrent?.id"
                      ref="shortsFeedBgRef"
                      class="short-video-bg"
                      :data-play-token="shortsFeedPlayToken"
                      muted
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                    ></video>
                    <video
                      :key="shortsFeedCurrent?.id"
                      ref="shortsFeedFgRef"
                      class="short-video-fg"
                      :data-play-token="shortsFeedPlayToken"
                      playsinline
                      preload="metadata"
                      :src="shortsFeedVideoSrc"
                      @click="shortsTogglePlay"
                      @loadstart="onShortsFeedFgLoadStart"
                      @canplay="onShortsFeedFgCanPlay"
                      @error="onShortsFeedFgMediaError"
                      @loadedmetadata="onShortsFeedFgLoadedMetadata"
                      @durationchange="onShortsFeedFgDurationChange"
                      @timeupdate="onShortsFeedFgTimeUpdate"
                      @play="onShortsFeedFgPlay"
                      @pause="onShortsFeedFgPause"
                      @seeked="onShortsFeedFgSeeked"
                      @ended="onShortsFeedFgEnded"
                    ></video>
                    <div
                      v-show="shortsFeedVideoBuffering && shortsFeedVideoSrc"
                      class="video-player-loading video-player-loading--shorts"
                      aria-live="polite"
                      aria-busy="true"
                    >
                      <div class="video-player-loading__shimmer video-player-loading__shimmer--shorts" aria-hidden="true" />
                      <div class="video-player-loading__inner">
                        <span class="video-player-loading__ring video-player-loading__ring--shorts" />
                        <span class="video-player-loading__text video-player-loading__text--shorts">正在加载</span>
                      </div>
                    </div>
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
                        <button
                          v-if="shortsFeedCurrent?.authorId != null && shortsFeedCurrent?.authorId !== ''"
                          type="button"
                          class="shorts-feed-user-link"
                          title="查看作者主页"
                          @click.stop="onAuthorAvatarClick($event, shortsFeedCurrent.authorId)"
                        >
                          @{{ shortsFeedAuthorName }}
                        </button>
                        <template v-else>@{{ shortsFeedAuthorName }}</template>
                        <span v-if="shortsFeedMetaTimeText" class="shorts-feed-time-dot"> · {{ shortsFeedMetaTimeText }}</span>
                      </p>
                      <p class="shorts-feed-topic">{{ shortsFeedTitleText }}</p>
                      <p class="shorts-feed-desc">
                        {{ shortsFeedDescText || "暂无描述" }}
                        <span v-if="parseSearchTagsArray(shortsFeedCurrent).length" class="search-feed-tag-row search-feed-tag-row--shorts-overlay">
                          <button
                            v-for="tg in parseSearchTagsArray(shortsFeedCurrent)"
                            :key="'shtg-' + String(shortsFeedCurrent?.id ?? '') + '-' + tg"
                            type="button"
                            class="search-feed-tag-chip search-feed-tag-chip--shorts-overlay"
                            @click.stop="onSearchTagNavigate(tg)"
                          >
                            {{ tg.startsWith("#") ? tg : "#" + tg }}
                          </button>
                        </span>
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
                            v-if="!shortsIsSelfCurrent && !shortsIsFollowedCurrent"
                            type="button"
                            class="shorts-feed-follow-btn"
                            title="关注"
                            aria-label="关注"
                            @click.stop="toggleShortsFollow"
                          >
                            <i class="ri-add-line"></i>
                          </button>
                          <button
                            v-else-if="!shortsIsSelfCurrent"
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
                          :class="{ 'shorts-feed-action-btn--active': shortsFeedLikedDisplay }"
                          title="点赞 / 取消赞"
                          aria-label="点赞"
                          @click="onShortsLikeClick"
                        >
                          <i :class="shortsFeedLikedDisplay ? 'ri-heart-fill' : 'ri-heart-line'"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.likeCount ?? 0 }}</span>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="评论"
                          aria-label="评论"
                          @click.stop="toggleShortsCommentPanel"
                        >
                          <i class="ri-chat-3-line"></i>
                        </button>
                        <span class="shorts-feed-action-count">{{ shortsFeedCurrent?.commentCount ?? 0 }}</span>
                      </div>
                      <div v-if="!shortsIsSelfCurrent" class="shorts-feed-action-item">
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
                      <div v-else-if="shortsIsSelfCurrent" class="shorts-feed-action-item shorts-feed-action-item--menu">
                        <button
                          type="button"
                          class="shorts-feed-action-btn"
                          title="更多操作"
                          aria-label="更多操作"
                          @click.stop="toggleSelfVideoActionMenu"
                        >
                          <i class="ri-more-fill"></i>
                        </button>
                        <div v-if="selfVideoActionOpen" class="shorts-self-action-menu" @click.stop>
                          <button type="button" class="shorts-self-action-btn" @click="toggleCurrentSelfVideoVisibility">
                            {{ String(shortsFeedCurrent?.visibility || '').toUpperCase() === 'PRIVATE' ? '设为公开' : '设为私密' }}
                          </button>
                          <button type="button" class="shorts-self-action-btn" @click="toggleCurrentSelfVideoAllowComment">
                            {{ Number(shortsFeedCurrent?.allowComment ?? 1) === 1 ? '关闭评论' : '开启评论' }}
                          </button>
                          <button
                            v-if="shortsIsSelfCurrent"
                            type="button"
                            class="shorts-self-action-btn shorts-self-action-btn--danger"
                            @click="requestDeleteCurrentSelfVideo"
                          >
                            删除
                          </button>
                        </div>
                      </div>
                      <div class="shorts-feed-action-item">
                        <button type="button" class="shorts-feed-action-btn" title="转发" aria-label="转发" @click.stop="onShortsForwardClick">
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
                      :max="shortsScrubMax"
                      step="0.1"
                      :disabled="!shortsDurationReady"
                      :value="shortsScrubDisplayValue"
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
            </div>
          </template>
          <div v-else class="shorts-feed-empty">
            <div class="module-empty-icon"><i class="ri-video-line"></i></div>
            <h5>{{ activeMenuKey === "subscribed" ? "暂无关注动态" : "暂无短视频" }}</h5>
            <p>
              {{
                activeMenuKey === "subscribed"
                  ? "关注作者后将在此展示推荐短视频；也可在左侧点选某位作者"
                  : "当前模块暂无可播放内容"
              }}
            </p>
          </div>
        </section>
        </div>

        <template v-if="!showProfileCenter && activeMenuKey !== 'shorts' && activeMenuKey !== 'subscribed'">
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
              <div
                v-else
                class="home-shorts-strip history-shorts-strip"
                :class="{
                  'history-shorts-strip--expanded history-shorts-strip--long-grid': historyLongExpanded,
                }"
              >
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
                      role="button"
                      title="查看作者主页"
                      @click.stop="onAuthorAvatarClick($event, card.authorId)"
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
              <div
                v-else
                class="home-shorts-strip history-shorts-strip"
                :class="{
                  'history-shorts-strip--expanded history-shorts-strip--short-grid': historyShortExpanded,
                }"
              >
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

          <section v-else-if="activeMenuKey === 'liked'" class="liked-videos-page profile-works-section">
            <div class="profile-works-title">
              <h4><i class="ri-thumb-up-line"></i> 赞过的视频</h4>
              <div class="profile-works-toolbar">
                <span v-if="isAuthed">共 {{ likedTotal }} 条</span>
                <span v-else class="liked-login-hint">登录后可查看</span>
              </div>
            </div>
            <p v-if="!isAuthed" class="profile-api-hint">请先登录后查看「赞过的视频」。</p>
            <div v-else-if="likedLoading" class="liked-loading-block">
              <p class="profile-api-hint profile-api-hint--loading">正在加载…</p>
              <div class="works-grid works-grid-profile works-grid-profile--skeleton">
                <article v-for="n in 6" :key="`liked-skeleton-${n}`" class="work-card work-card--skeleton">
                  <div class="work-thumb"></div>
                  <div class="work-meta">
                    <p class="work-title"></p>
                  </div>
                </article>
              </div>
            </div>
            <div v-else class="works-grid works-grid-profile">
              <article
                v-for="item in likedDisplay"
                :key="`liked-${item.id}`"
                class="work-card"
                :class="{ 'work-card--playable': resolveVideoUrl(item) }"
                @click="onLikedWorkClick(item)"
                @mouseenter="onCardHoverEnter(item, 'liked-work')"
                @mouseleave="onCardHoverLeave(item, 'liked-work')"
              >
                <div class="work-thumb">
                  <video
                    v-if="isCardPreviewPlaying(item, 'liked-work')"
                    class="work-preview-video"
                    :src="activePreviewVideoUrl"
                    muted
                    autoplay
                    loop
                    playsinline
                    preload="metadata"
                  ></video>
                  <img v-else :src="item.image" :alt="item.title" />
                  <span class="work-like work-like--bottom-left work-like--liked-list">
                    <i class="ri-heart-fill" aria-hidden="true"></i> {{ item.likes }}
                  </span>
                </div>
                <div class="work-meta">
                  <img
                    class="work-author-avatar"
                    :src="item.authorAvatarUrl || profileAvatar"
                    alt=""
                    role="button"
                    title="查看作者主页"
                    @click.stop="onAuthorAvatarClick($event, item.authorId)"
                  />
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
            <div v-if="isAuthed && !likedLoading && likedDisplay.length && likedHasMore" class="liked-load-more-wrap">
              <button
                type="button"
                class="history-layout-btn"
                :disabled="likedLoadingMore"
                @click="loadLikedPage({ reset: false })"
              >
                {{ likedLoadingMore ? "加载中…" : "加载更多" }}
              </button>
            </div>
            <div v-if="isAuthed && !likedLoading && !likedDisplay.length" class="profile-works-empty">
              <div class="profile-works-empty-icon"><i class="ri-thumb-up-line"></i></div>
              <h5>暂无赞过的视频</h5>
              <p>去短视频或首页给喜欢的内容点个赞吧～</p>
            </div>
          </section>

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
            <section
              v-if="!loading && !homeLongHeadCards.length && !homeLongTailCards.length && !homeShortsRows.length"
              class="module-empty"
            >
              <div class="module-empty-icon"><i :class="moduleEmptyState.icon"></i></div>
              <h5>{{ moduleEmptyState.title }}</h5>
              <p>{{ moduleEmptyState.desc }}</p>
            </section>
          </section>

          <section v-else-if="activeMenuKey !== 'subscribed' && activeMenuKey !== 'chat'" class="card-grid">
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
            <div v-if="!loading && !cards.length" class="module-empty module-empty--grid">
              <div class="module-empty-icon"><i :class="moduleEmptyState.icon"></i></div>
              <h5>{{ moduleEmptyState.title }}</h5>
              <p>{{ moduleEmptyState.desc }}</p>
            </div>
          </section>
        </template>
        </template>
        </template>
      </main>

      <div v-if="showVideoDetail" class="video-detail-page video-detail-page--layer">
        <div v-if="detailLoading" class="video-detail-loading" aria-busy="true">
          <span class="video-player-loading__ring video-player-loading__ring--detail" />
          <span class="video-detail-loading__text">加载中…</span>
    </div>
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
                  :src="videoDetail.videoUrl"
                  @loadstart="onDetailVideoLoadStart"
                  @canplay="onDetailVideoCanPlay"
                  @error="onDetailVideoMediaError"
                  @loadeddata="() => void tryDetailVideoAutoplay()"
                  @loadedmetadata="() => { applyDetailPlaybackResumeIfNeeded(); void tryDetailVideoAutoplay(); }"
                  @canplaythrough="() => void tryDetailVideoAutoplay()"
                  @pause="onDetailVideoPause"
                  @play="onDetailVideoPlay"
                  @timeupdate="onDetailVideoTimeUpdate"
                  @seeked="onDetailVideoSeeked"
                  @ended="onDetailVideoEnded"
                />
                <div
                  v-if="videoDetail.videoUrl && detailVideoBuffering"
                  class="video-player-loading video-player-loading--detail"
                  aria-live="polite"
                  aria-busy="true"
                >
                  <div class="video-player-loading__shimmer" aria-hidden="true" />
                  <div class="video-player-loading__inner">
                    <span class="video-player-loading__ring video-player-loading__ring--detail" />
                    <span class="video-player-loading__text video-player-loading__text--detail">正在加载视频</span>
  </div>
                </div>
                <div v-else-if="!videoDetail.videoUrl" class="video-detail-player-empty">暂无播放地址</div>
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
                      @click.stop="onAuthorAvatarClick($event, videoDetail.authorId)"
                      @error="(e) => (e.target.src = profileAvatar)"
                    />
                  </div>
                  <div class="video-detail-channel-text">
                    <strong>{{ videoDetail.authorNickname }}</strong>
                    <span>{{ detailFanText }}</span>
                  </div>
                  <button
                    v-if="videoDetail.authorId && String(videoDetail.authorId) !== String(currentUserId)"
                    type="button"
                    class="video-detail-subscribe"
                    :disabled="detailFollowBusy"
                    @click="onDetailFollowClick"
                  >
                    {{ detailSubscribeFollowed ? "已关注" : "+ 关注" }}
                  </button>
                </div>
                <div class="video-detail-actions">
                  <button
                    type="button"
                    class="video-detail-action-pill"
                    :class="{ 'video-detail-action-pill--active': detailSessionLiked }"
                    :disabled="detailLikeBusy"
                    @click="onDetailLikeClick"
                  >
                    <i :class="detailSessionLiked ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"></i>
                    {{ videoDetail.likeCount ?? 0 }}
                  </button>
                  <button type="button" class="video-detail-action-pill" @click="onDetailDislikeClick">
                    <i class="ri-thumb-down-line"></i> 不感兴趣
                  </button>
                  <button type="button" class="video-detail-action-pill" @click="openForwardVideoModal(videoDetail)">
                    <i class="ri-share-forward-line"></i> 分享
                  </button>
                </div>
              </div>
              <p class="video-detail-views">{{ videoDetail.viewsText }}</p>
              <p v-if="detailErrorMsg" class="video-detail-api-hint">{{ detailErrorMsg }}</p>
              <p v-if="videoDetail.description" class="video-detail-desc">{{ videoDetail.description }}</p>
              <p v-if="parseSearchTagsArray(videoDetail).length" class="video-detail-tags-row">
                <span class="search-feed-tag-row">
                  <button
                    v-for="tg in parseSearchTagsArray(videoDetail)"
                    :key="'dvtg-' + String(videoDetail.id) + '-' + tg"
                    type="button"
                    class="search-feed-tag-chip"
                    @click.stop="onSearchTagNavigate(tg)"
                  >
                    {{ tg.startsWith("#") ? tg : "#" + tg }}
                  </button>
                </span>
              </p>

              <section class="video-detail-comments" aria-label="评论区">
                <div class="video-detail-comments-head">
                  <h3>评论 {{ videoDetail.commentCount != null ? `（${videoDetail.commentCount}）` : "" }}</h3>
                  <span v-if="!detailVideoAllowComment()" class="video-detail-comments-note">作者已关闭评论</span>
                </div>
                <p v-if="detailReplyTarget" class="video-detail-comment-reply-hint">
                  回复 @{{ commentDisplayNickname(detailReplyTarget.userId, detailReplyTarget.nickname) }}
                  <button type="button" class="video-detail-comment-reply-cancel" @click="setDetailReplyTarget(null)">取消</button>
                </p>
                <div class="video-detail-comment-input">
                  <img :src="topbarAvatarSrc" alt="" class="video-detail-comment-avatar" @error="onTopbarAvatarError" />
                  <input
                    ref="detailCommentInputRef"
                    v-model="detailCommentDraft"
                    type="text"
                    class="video-detail-comment-field"
                    :disabled="!isAuthed || !detailVideoAllowComment() || detailCommentSubmitting"
                    :placeholder="
                      !isAuthed
                        ? '登录后参与评论…'
                        : !detailVideoAllowComment()
                          ? '评论已关闭'
                          : detailReplyTarget
                            ? `回复 @${commentDisplayNickname(detailReplyTarget.userId, detailReplyTarget.nickname)}…`
                            : '添加评论…'
                    "
                    maxlength="500"
                    @keyup.enter.prevent="submitDetailComment"
                  />
                  <div class="video-detail-comment-input-actions">
                    <ChatEmojiPicker
                      :disabled="!isAuthed || !detailVideoAllowComment() || detailCommentSubmitting"
                      @pick="onDetailCommentEmojiPick"
                    />
                    <button
                      type="button"
                      class="video-detail-comment-send"
                      :disabled="!isAuthed || !detailVideoAllowComment() || detailCommentSubmitting || !detailCommentDraft.trim()"
                      @click="submitDetailComment"
                    >
                      {{ detailCommentSubmitting ? "发送中…" : "发送" }}
                    </button>
                  </div>
                </div>
                <p v-if="detailCommentsLoading && !detailTopComments.length" class="video-detail-comments-empty">加载评论中…</p>
                <ul v-else class="video-detail-comments-mock">
                  <template v-for="c in detailTopComments" :key="`dc-top-${c.id}`">
                    <li class="video-detail-comment-item">
                      <img
                        :src="c.avatarUrl || profileAvatar"
                        alt=""
                        class="video-detail-comment-avatar video-detail-comment-avatar--clickable"
                        role="button"
                        tabindex="0"
                        title="查看用户主页"
                        @click.stop="onDetailCommentUserClick($event, c.userId)"
                        @keyup.enter.stop="onDetailCommentUserClick($event, c.userId)"
                        @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                      />
                      <div class="video-detail-comment-body">
                        <div class="video-detail-comment-meta">
                          <button
                            type="button"
                            class="video-detail-comment-user video-detail-comment-user--link"
                            @click.stop="onDetailCommentUserClick($event, c.userId)"
                          >
                            {{ commentDisplayNickname(c.userId, c.nickname) }}
                          </button>
                          <span class="video-detail-comment-time">{{ formatDetailCommentTime(c.createTime) }}</span>
                        </div>
                        <p class="video-detail-comment-text">{{ c.content }}</p>
                        <div class="video-detail-comment-actions">
                          <button
                            type="button"
                            class="video-detail-comment-like-btn"
                            :class="{ 'video-detail-comment-like-btn--active': c.isLike }"
                            :disabled="isCommentLikeBusy(c.id)"
                            :aria-pressed="c.isLike ? 'true' : 'false'"
                            :aria-label="c.isLike ? '取消点赞' : '点赞'"
                            @click="submitCommentLike(c)"
                          >
                            <i
                              :class="c.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                              aria-hidden="true"
                            ></i>
                            {{ c.likeCount ?? 0 }}
                          </button>
                          <button
                            v-if="isAuthed && detailVideoAllowComment()"
                            type="button"
                            class="video-detail-comment-action-btn"
                            @click="setDetailReplyTarget(c)"
                          >
                            回复
                          </button>
                          <button
                            v-if="isAuthed && currentUserId != null && String(c.userId) === String(currentUserId)"
                            type="button"
                            class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                            @click="onDeleteDetailComment(c)"
                          >
                            删除
                          </button>
                          <span
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            class="video-detail-comment-actions-spacer"
                          ></span>
                          <button
                            v-if="isAuthed && currentUserId != null && String(c.userId) !== String(currentUserId) && !isBlockedComment(c)"
                            type="button"
                            class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                            title="举报"
                            @click="openCommentReportModal(c)"
                          >
                            <i class="ri-flag-line" aria-hidden="true"></i>举报
                          </button>
                        </div>
                        <button
                          v-if="c.replyCount > 0"
                          type="button"
                          class="video-detail-thread-toggle"
                          @click="toggleDetailThread(c.id)"
                        >
                          {{
                            detailExpandedRoots[String(c.id)]
                              ? "收起回复"
                              : `展开 ${c.replyCount} 条回复`
                          }}
                        </button>
                        <ul
                          v-if="detailExpandedRoots[String(c.id)]"
                          class="video-detail-comment-replies"
                        >
                          <li
                            v-for="r in detailThreadByRootId[String(c.id)]?.list || []"
                            :key="`dc-r-${r.id}`"
                            class="video-detail-comment-item video-detail-comment-item--reply"
                          >
                            <img
                              :src="r.avatarUrl || profileAvatar"
                              alt=""
                              class="video-detail-comment-avatar video-detail-comment-avatar--sm video-detail-comment-avatar--clickable"
                              role="button"
                              tabindex="0"
                              title="查看用户主页"
                              @click.stop="onDetailCommentUserClick($event, r.userId)"
                              @keyup.enter.stop="onDetailCommentUserClick($event, r.userId)"
                              @error="(e) => { const t = e.target; if (t) t.src = profileAvatar; }"
                            />
                            <div class="video-detail-comment-body">
                              <div class="video-detail-comment-meta">
                                <button
                                  type="button"
                                  class="video-detail-comment-user video-detail-comment-user--link"
                                  @click.stop="onDetailCommentUserClick($event, r.userId)"
                                >
                                  {{ commentDisplayNickname(r.userId, r.nickname) }}
                                </button>
                                <span
                                  v-if="r.replyNickname"
                                  class="video-detail-comment-arrow"
                                  aria-hidden="true"
                                  title="回复"
                                >▶</span>
                                <button
                                  v-if="r.replyNickname && r.replyUserId != null && r.replyUserId !== ''"
                                  type="button"
                                  class="video-detail-comment-at video-detail-comment-at--link"
                                  @click.stop="onDetailCommentUserClick($event, r.replyUserId)"
                                >
                                  @{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}
                                </button>
                                <span v-else-if="r.replyNickname" class="video-detail-comment-at"
                                  >@{{ commentDisplayNickname(r.replyUserId, r.replyNickname) }}</span>
                                <span class="video-detail-comment-time">{{ formatDetailCommentTime(r.createTime) }}</span>
                              </div>
                              <p class="video-detail-comment-text">{{ r.content }}</p>
                              <div class="video-detail-comment-actions">
                                <button
                                  type="button"
                                  class="video-detail-comment-like-btn"
                                  :class="{ 'video-detail-comment-like-btn--active': r.isLike }"
                                  :disabled="isCommentLikeBusy(r.id)"
                                  :aria-pressed="r.isLike ? 'true' : 'false'"
                                  :aria-label="r.isLike ? '取消点赞' : '点赞'"
                                  @click="submitCommentLike(r)"
                                >
                                  <i
                                    :class="r.isLike ? 'ri-thumb-up-fill' : 'ri-thumb-up-line'"
                                    aria-hidden="true"
                                  ></i>
                                  {{ r.likeCount ?? 0 }}
                                </button>
                                <button
                                  v-if="isAuthed && detailVideoAllowComment()"
                                  type="button"
                                  class="video-detail-comment-action-btn"
                                  @click="setDetailReplyTarget(r)"
                                >
                                  回复
                                </button>
                                <button
                                  v-if="isAuthed && currentUserId != null && String(r.userId) === String(currentUserId)"
                                  type="button"
                                  class="video-detail-comment-action-btn video-detail-comment-action-btn--danger"
                                  @click="onDeleteDetailComment(r)"
                                >
                                  删除
                                </button>
                                <span
                                  v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                  class="video-detail-comment-actions-spacer"
                                ></span>
                                <button
                                  v-if="isAuthed && currentUserId != null && String(r.userId) !== String(currentUserId) && !isBlockedComment(r)"
                                  type="button"
                                  class="video-detail-comment-action-btn video-detail-comment-action-btn--report"
                                  title="举报"
                                  @click="openCommentReportModal(r)"
                                >
                                  <i class="ri-flag-line" aria-hidden="true"></i>举报
                                </button>
                              </div>
                            </div>
                          </li>
                          <li
                            v-if="detailThreadByRootId[String(c.id)]?.loading"
                            class="video-detail-comment-item video-detail-comment-item--reply"
                          >
                            <span class="video-detail-comments-empty">加载回复中…</span>
                          </li>
                        </ul>
                      </div>
                    </li>
                  </template>
                </ul>
                <p v-if="!detailCommentsLoading && !detailTopComments.length" class="video-detail-comments-empty">暂无评论，来抢沙发吧</p>
                <div v-if="detailCommentHasMore()" class="video-detail-comments-more">
                  <button
                    type="button"
                    class="history-layout-btn"
                    :disabled="detailCommentsLoading"
                    @click="loadMoreDetailComments"
                  >
                    {{ detailCommentsLoading ? "加载中…" : "加载更多评论" }}
                  </button>
                </div>
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
