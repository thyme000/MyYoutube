<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { storeToRefs } from "pinia";
import { RouterView, useRoute, useRouter } from "vue-router";
import { menuKeyForPath, menuPathFor } from "./router";
import { useAuthStore } from "./stores/authStore";
import { useSearchStore, registerSearchExpandHooks, registerSearchBridgeHooks, SEARCH_UI } from "./stores/searchStore";
import { useLikedStore, registerLikedHooks } from "./stores/likedStore";
import { useHomeStore, registerHomeHooks } from "./stores/homeStore";
import { useShortsStore } from "./stores/shortsStore";
import { useProfileStore, registerProfileHooks } from "./stores/profileStore";
import { useDetailStore, registerDetailHooks } from "./stores/detailStore";
import { useCommentPanelStore } from "./stores/commentPanelStore";
import { useFeedPatchStore } from "./stores/feedPatchStore";
import { useFollowStore } from "./stores/followStore";
import { useBehaviorStore } from "./stores/behaviorStore";
import { useNavigationStore, registerNavigationHooks } from "./stores/navigationStore";
import { registerMenuOrchestrationHooks, useMenuOrchestrationStore } from "./stores/menuOrchestrationStore";
import { useShareStore } from "./stores/shareStore";
import { useStudioStore, registerStudioHooks } from "./stores/studioStore";
import { useHoverPreviewStore } from "./stores/hoverPreviewStore";
import { registerShortsHooks } from "./stores/shortsStore";
import {
  resolveVideoUrl,
  resolveVideoUrlFromLists,
} from "./utils/videoCard.js";
import { getAuthExpiredEventName } from "./api/http";
import { reconnectNotifyWebSocketIfNeeded } from "./utils/notifyWebSocket";
import { useNotificationStore } from "./stores/notificationStore";
import { useToastStore } from "./stores/toastStore";
import { loginApi } from "./api/authApi";
import { fetchVideoDetail, normalizeVideoDetail } from "./api/videoApi";
import { primaryMenus, exploreMenus } from "./constants/menus";
import {
  likeVideo as likeVideoApi,
  cancelLikeVideo as cancelLikeVideoApi,
} from "./api/likeApi";
import { followAuthor, unfollowAuthor } from "./api/followApi";
import {
  applyAvatarFallback,
  sanitizeImageUrl,
  stripUserIdLikeName,
  DEFAULT_PROFILE_AVATAR_URL as profileAvatar,
} from "./utils/userProfile";
import { parseDigitSnowflakeId } from "./utils/snowflakeId.js";
/** 短视频无接口数据时的本地 fallback（static 目录下四个 mp4） */
import appIco from "../static/狗.png?url";
const SearchView = defineAsyncComponent(() => import("./views/SearchView.vue"));
const ShortsView = defineAsyncComponent(() => import("./views/ShortsView.vue"));
import MusicPlayerAudioHost from "./components/MusicPlayerAudioHost.vue";
import MusicMiniPlayer from "./components/MusicMiniPlayer.vue";
import { MenuView, StudioView, ProfileCenterView } from "./router/views.js";
import ChatFloatingRail from "./components/chat/ChatFloatingRail.vue";
import ChatForwardVideoModal from "./components/chat/ChatForwardVideoModal.vue";
import CommentReportModal from "./components/CommentReportModal.vue";
import BaseModal from "./components/ui/BaseModal.vue";
import LoginLoadingCubes from "./components/LoginLoadingCubes.vue";
import { musicLyricsFullscreen } from "./stores/musicPlayerStore.js";
import { registerGlobalToast } from "./utils/toast.js";
import AuthScene from "./components/AuthScene.vue";
import UploadModal from "./components/UploadModal.vue";
import TopbarSearch from "./components/TopbarSearch.vue";
import SideNavbar from "./components/SideNavbar.vue";
import NotifyPanel from "./components/NotifyPanel.vue";
import ToastStack from "./components/ToastStack.vue";
import { registerChatNavigator } from "./utils/chatNavigator.js";
import {
  setChatUserContext,
  resetChatWhenLogout,
  initChatWhenAuthed,
  openConversation as openChatConversation,
  chatActiveConversationId,
  refreshConversationList,
} from "./stores/chatStore.js";

const route = useRoute();
const router = useRouter();
/** 导航域状态在 pinia navigationStore，同名解构保持既有引用零改动 */
const navigationStore = useNavigationStore();
const { activeMenuKey, activeMenuLabel, pageHint } = storeToRefs(navigationStore);
/** 菜单数据装载与切换编排下沉在 menuOrchestrationStore，这里保留同名薄封装供模板/钩子调用 */
const menuOrch = useMenuOrchestrationStore();
/** 搜索域状态在 pinia searchStore，同名解构保持既有引用零改动；拆 SearchView 时随之搬出 */
const searchStore = useSearchStore();
// 列表加载后由 store 通知 App 挂自动播放观察器（依赖本组件的 DOM 引用）
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

let searchVideoObserver = null;
searchStore.registerLoadedHook(setupSearchVideoAutoplay);

const {
  searchKeyword,
  searchActive,
  searchTab,
  searchSubmittedKeyword,
  searchDropdownOpen,
  searchVideoSort,
  searchResultError,
  searchExpandOpen,
  searchFeedLikeBusyId,
} = storeToRefs(searchStore);
const topbarSearchRef = ref(null);
const searchFeedScrollRef = ref(null);
const contentScrollRef = ref(null);

watch(searchVideoSort, () => {
  if (!searchActive.value) return;
  if (searchTab.value === "all" || searchTab.value === "video") {
    void searchStore.loadVideoPage({ reset: true });
  }
});



/** 标签跳搜索的跨域编排在 navigationStore；App 只保留同名薄封装供桥与 detailStore 钩子调用 */
const onSearchTagNavigate = (tag) => navigationStore.navigateToTag(tag);

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

const homeStore = useHomeStore();
const { loading, cards, homeShortsRows, homeCategoryKey, homeSortKey } = storeToRefs(homeStore);
const homeShortsStripRef = ref(null);
/** 离开首页或打开个人中心前保存，回到首页时可无请求恢复，减少列表跳动 */
/** 登录成功后全屏 LOADING 立方体动画，与首屏数据加载并行 */
const showLoginLoadingOverlay = ref(false);
/** 登录立方体动画播完（最后一格 llc-translate-z 结束）时 resolve */
let loginCubesAnimResolve = null;
function onLoginCubesAnimFinished() {
  loginCubesAnimResolve?.();
  loginCubesAnimResolve = null;
}
const authStore = useAuthStore();
/** isAuthed / topbarAvatarSrc 直接取 authStore 的 getter，不再在 App 里重复推导 */
const {
  userId: currentUserId,
  selfProfile: selfUserProfile,
  isAuthed,
  selfAvatarUrl: topbarAvatarSrc,
} = storeToRefs(authStore);
/** 创作中心域状态在 pinia studioStore */
const hoverPreview = useHoverPreviewStore();
const {
  activeId: activePreviewCardId,
} = storeToRefs(hoverPreview);

function stopCardHoverPreview() {
  hoverPreview.stop();
}

const studioStore = useStudioStore();
const {
  showStudio: studioShow,
  studioSideNav,
  studioContentTab,
  studioPageNo,
} = storeToRefs(studioStore);

/** 个人主页域状态在 pinia profileStore；同名解构保持既有引用零改动 */
const profileStore = useProfileStore();
const {
  open: showProfileCenter,
  targetUserId: profileTargetUserId,
  user: profileUser,
  worksRows: profileWorksRows,
  /** 关注列表同时供「关注」页侧栏与粉丝/关注弹窗使用 */
  mediaPreviewOpen,
  isViewingSelf,
} = storeToRefs(profileStore);

/** 主页入口：供顶栏、头像与桥接消费；打开动作与状态都在 profileStore */
const openProfile = () => profileStore.openSelf();
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
/** 创作中心（全屏主区，模拟 YouTube Studio）：URL 由 /studio 路由承载 */
const showStudio = studioShow;

/** 通知域状态在 pinia notificationStore，toast 渲染在 ToastStack */
const notificationStore = useNotificationStore();
const toastStore = useToastStore();
const { pushToast } = toastStore;
const uploadModalOpen = ref(false);
/** 上传弹窗整块在 components/UploadModal.vue，随 /upload 路由挂载 */
/** 视频详情域状态在 pinia detailStore；App 只需判断「是否在详情」与读卡片数据 */
const detailStore = useDetailStore();
const {
  open: showVideoDetail,
  video: videoDetail,
  loading: detailLoading,
} = storeToRefs(detailStore);

/** 详情域动作：App 的菜单/弹窗/地址栏入口仍要调，保留同名薄封装 */
const openVideoDetail = (card, opts) => detailStore.openDetail(card, opts);
const closeVideoDetail = () => detailStore.closeDetail();
const restoreVideoDetailFromUrl = (vidRaw) => detailStore.restoreFromUrl(vidRaw);
const hideDetailBufferingOverlay = () => detailStore.hideBufferingOverlay();
/** 短视频播放器域状态在 pinia shortsStore（含历史/赞过/主页/关注共用的全屏队列），同名解构零引用改动 */
const shortsStore = useShortsStore();
/** 跨列表补丁的协调者在 feedPatchStore，各域 store 只改自己的列表 */
const feedPatch = useFeedPatchStore();
const {
  feedScope,
  profileFeedQueue,
  likedFeedQueue,
  followFeedQueue,
  followSubscribedFilterAuthorId,
  followAuthorFeedQueue,
  profileFeedOpen,
  /** 队列推导出的当前条与「当前评论目标」，播放器与评论侧栏共用 */
  unifiedFeedList,
  shortsFeedCurrent,
  activeCommentVideo,
  /** 当前条的展示值：模板与桥直接消费，推导逻辑在 store */
  shortsFeedVideoSrc,
  shortsFeedIndex,
  shortsFeedAutoplayPending,
  selfVideoActionOpen,
  selfVideoDeleteConfirmOpen,
} = storeToRefs(shortsStore);
/** 评论面板开关在 commentPanelStore：短视频侧栏 / 搜索放大层 / 搜索列表三个入口互斥 */
const {
  shortsCommentPanelOpen,
  searchExpandCommentOpen,
  searchListCommentOpen,
} = storeToRefs(useCommentPanelStore());
/** 播放缓冲遮罩：实现已移入 shortsStore，这里保留同名薄封装供桥与生命周期调用 */
const hideShortsBufferingOverlay = () => shortsStore.hideShortsBufferingOverlay();

/**
 * 短视频播放控制：实现已移入 shortsStore（播放器 DOM ref 归 ShortsView 持有并注册），
 * 这里保留同名薄封装，使生命周期与详情/主页等域调用点零改动。
 */
const shortsFeedPrev = () => shortsStore.shortsFeedPrev();
const shortsFeedNext = () => shortsStore.shortsFeedNext();
const shortsTogglePlay = () => shortsStore.shortsTogglePlay();
const onShortsFeedKeydown = (ev) => shortsStore.onShortsFeedKeydown(ev);
const pauseShortsFeedVideos = (opts) => shortsStore.pauseShortsFeedVideos(opts);
const restoreShortsFeedForegroundMuteFromState = () =>
  shortsStore.restoreShortsFeedForegroundMuteFromState();
const resetShortsFeedProgressUiForSwitch = () => shortsStore.resetShortsFeedProgressUiForSwitch();
const flushShortsLongPlaybackResume = (reason) => shortsStore.flushShortsLongPlaybackResume(reason);


/** 关注页数据源与侧栏筛选：实现已移入 shortsStore */
const maybePrefetchFollowFeed = () => shortsStore.maybePrefetchFollowFeed();
/** 详情深链/刷新恢复经 route watcher 与 sessionStorage 快照（detailStore.restoreFromUrl） */

/** 按作者 ID 覆盖关注状态（乐观更新后优先于接口返回的 isFollow），状态在 pinia followStore */
const { overrideByAuthorId: followStateOverrideByAuthorId } = storeToRefs(useFollowStore());
const behaviorStore = useBehaviorStore();
const reportBehaviorSilently = (videoIdLike, event, watchDurationMs = 0) =>
  behaviorStore.report(videoIdLike, event, watchDurationMs);
/** 转发/举报弹窗：搜索、详情、短视频三处共用，弹窗本体仍在本组件渲染 */
const share = useShareStore();
const {
  forwardVideoOpen,
  forwardVideoCard,
  commentReportOpen,
  commentReportTarget,
} = storeToRefs(share);
/** 头像加载失败回落默认图，与评论头像、通知列表共用同一实现 */
const onTopbarAvatarError = (evt) => applyAvatarFallback(evt, profileAvatar);
const UI_STATE_STORAGE_KEY = "myyoutube_ui_state_v1";

/** 仅音乐歌词「全屏」时隐藏右侧会话条；普通音乐页仍显示 */
const chatRailSuppress = computed(
  () =>
    searchExpandOpen.value ||
    profileFeedOpen.value ||
    showVideoDetail.value ||
    musicLyricsFullscreen.value,
);

/** 赞过的视频列表（GET /like/{userId}/list），状态在 pinia likedStore */
const { likedRows } = storeToRefs(useLikedStore());
/** 创作中心表格行「更多」菜单 */
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

/** 仅当 id 为纯数字字符串时可用于 /videos/detail/{id}，全程用字符串避免精度丢失 */
function parseVideoIdForApi(id) {
  return parseDigitSnowflakeId(id);
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

/** 浏览器前进/后退、刷新：以 URL 为准同步菜单与创作中心 */
/**
 * 主区视图解析：路由为唯一事实来源（菜单 / studio / profile 由路由表给出真实组件）。
 * /upload 叠在来路视图之上（目前仅 /studio 入口会进上传），用最近一次非 /upload 的主区视图归底，深链直入则无底层。
 * 优先级 studio > search（搜索态压掉菜单与 profile，与既有 v-if 链语义一致）。
 */
const uploadUnderlyingView = shallowRef(null);
function mainViewForPath(p) {
  if (p === "/studio") return StudioView;
  if (searchActive.value) return null;
  if (p === "/profile" || p.startsWith("/profile/")) return ProfileCenterView;
  return MenuView[menuKeyForPath(p)] ?? null;
}
const mainView = computed(() =>
  route.path === "/upload" ? uploadUnderlyingView.value : mainViewForPath(route.path)
);
watch(
  () => route.path,
  (p) => {
    if (p !== "/upload") uploadUnderlyingView.value = mainViewForPath(p);
  },
  { immediate: true }
);

watch(
  () => route.path,
  (p) => {
    const key = menuKeyForPath(p);
    if (key) {
      applyMenuKeyIfKnown(key);
    }
    if (p === "/upload") {
      if (isAuthed.value) {
        uploadModalOpen.value = true;
      }
    } else if (uploadModalOpen.value) {
      uploadModalOpen.value = false;
    }
    if (p === "/studio") {
      /** 从 /upload 返回时 showStudio 仍为 true，跳过重进以免重置 Tab 与重复加载 */
      if (!showStudio.value) {
        enterStudio();
      }
    } else if (key && showStudio.value) {
      showStudio.value = false;
      if (!searchActive.value) {
        void reloadFeedAfterLeavingDetail();
      }
    }
    if (p === "/profile" || p.startsWith("/profile/")) {
      enterProfileFromRoute(p);
    }
    if (p.startsWith("/feed/")) {
      enterFeedFromRoute(p);
    } else if (profileFeedOpen.value) {
      shortsStore.closeProfileFeed();
    }
    if (p.startsWith("/video/")) {
      enterVideoDetailFromRoute(p);
    } else if (showVideoDetail.value) {
      /** 离开 /video（后退/前进/深链回退）：route 单向收敛详情状态 */
      detailStore.closeByHistory();
      void reloadFeedAfterLeavingDetail();
    }
  },
  { immediate: true }
);

/** /feed/:scope/:id? 入口：打开方已置好队列时跳过；深链/刷新无法重建队列，回退首页 */
function enterFeedFromRoute(p) {
  if (profileFeedOpen.value) return;
  void router.replace("/");
}

/** /video/:id 入口：详情内切视频（replace 回来）与已开同一视频时跳过；深链/刷新经 sessionStorage 快照恢复 */
function enterVideoDetailFromRoute(p) {
  if (!isAuthed.value) return;
  const vid = p.slice("/video/".length);
  if (!vid) return;
  if (
    showVideoDetail.value &&
    videoDetail.value?.id != null &&
    String(videoDetail.value.id) === vid
  ) {
    return;
  }
  void restoreVideoDetailFromUrl(vid);
}

/** 当前 feedScope 对应的路由；主区 feed（home/shorts）不入路由 */
function feedRouteForCurrentScope() {
  if (feedScope.value === "liked") return "/feed/liked";
  if (feedScope.value === "follow") {
    const aid = followSubscribedFilterAuthorId.value;
    return aid != null && aid !== "" ? `/feed/follow/${aid}` : "/feed/follow";
  }
  if (feedScope.value === "profile") {
    const tid = profileTargetUserId.value ?? currentUserId.value;
    return tid != null ? `/feed/profile/${tid}` : "/feed/profile";
  }
  return null;
}

/** /profile 与 /profile/:userId 的统一入口：菜单点击已由 _enter 走过，这里兜住深链/刷新/前进 */
function enterProfileFromRoute(p) {
  if (!isAuthed.value) return;
  const uid = p === "/profile" ? null : p.slice("/profile/".length);
  const alreadyOpen =
    showProfileCenter.value &&
    String(profileTargetUserId.value ?? "") === String(uid ?? "");
  if (alreadyOpen) return;
  if (uid == null) {
    profileStore.openSelf();
    return;
  }
  if (currentUserId.value != null && String(uid) === String(currentUserId.value)) {
    profileStore.openSelf();
    return;
  }
  profileStore.openOther(uid);
}

/** 进入创作中心（/studio）：菜单点击、深链、刷新统一走这里 */
function enterStudio() {
  if (!isAuthed.value) return;
  showStudio.value = true;
  showProfileCenter.value = false;
  mediaPreviewOpen.value = false;
  studioSideNav.value = "info";
  studioContentTab.value = "video";
  studioPageNo.value = 1;
  loadStudioVideos();
}

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
  shortsStore.reloadFeedPlayers();
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
      shortsStore.reloadFeedPlayers();
    });
  }
}

/** 短视频评论面板：状态与请求已移入 shortsStore，这里保留同名薄封装供跨域调用 */
const resetShortsPanelCommentsState = () => shortsStore.resetShortsPanelCommentsState();
const closeShortsCommentPanel = () => shortsStore.closeShortsCommentPanel();
const loadShortsPanelTopComments = (reset, opts) => shortsStore.loadShortsPanelTopComments(reset, opts);
function onWindowResize() {
}

function onHomeShortsStripScroll(e) {
  stopCardHoverPreview();
  homeStore.maybeLoadMoreShortsByElement(e?.target);
}

function onHomeShortsStripWheel(e) {
  const el = homeShortsStripRef.value;
  if (!el) return;
  e.stopPropagation();
  stopCardHoverPreview();
  const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
  el.scrollLeft += delta;
  homeStore.maybeLoadMoreShortsByElement(el);
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
    void homeStore.loadShortsMore();
  }
}

/**
 * 从首页 Shorts 条进入全屏短视频流：只使用已加载的 homeShortsRows，不立即请求接口；
 * 划到接近末尾时再分页（maybePrefetchHomeShortsForFeed）。
 */
function openHomeShortVideoInFeed(card, index) {
  if (activeMenuKey.value === "home") {
    homeStore.saveSnapshot();
  }
  if (showVideoDetail.value) {
    /** 主页短视频全屏流不走路由，详情的 /video 条目就地替换回菜单页 */
    void router.replace(menuPathFor(activeMenuKey.value));
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
    shortsStore.reloadFeedPlayers();
    maybePrefetchHomeShortsForFeed();
  });
}

function onHomeShortCardClick(card, index) {
  openHomeShortVideoInFeed(card, index);
}

function onWindowScroll() {
  if (activePreviewCardId.value) {
    stopCardHoverPreview();
  }
  homeStore.maybeLoadMoreLongByWindow();
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
        void searchStore.loadVideoPage({ reset: false });
      } else if (tab === "user") {
        void searchStore.loadUserPage({ reset: false });
      } else if (tab === "tag") {
        void searchStore.loadTagPage({ reset: false });
      }
    }, 200);
    return;
  }
  if (activeMenuKey.value !== "home" || loading.value || homeStore.homeLongLoadingMore) return;
  const el = e?.target;
  if (!el) return;
  const remain = el.scrollHeight - el.scrollTop - el.clientHeight;
  if (remain < 260) {
    void homeStore.loadLongMore();
  }
}

function loadModuleCards(menu, opts) {
  return menuOrch.loadModuleCards(menu, opts);
}

function setActiveMenu(menu) {
  return menuOrch.setActiveMenu(menu);
}

async function onSearch() {
  const keyword = searchKeyword.value.trim();
  if (!keyword) {
    pushToast(SEARCH_UI.emptyKeyword, "info");
    return;
  }
  // 创作中心/上传弹窗会挡住 v-else 下的搜索区，开始搜索时先退出以便结果可见
  uploadModalOpen.value = false;
  if (showStudio.value) {
    void router.push(menuPathFor(activeMenuKey.value));
  }
  searchDropdownOpen.value = false;
  searchSubmittedKeyword.value = keyword;
  searchActive.value = true;
  searchResultError.value = "";
  activeMenuLabel.value = `搜索：${keyword}`;
  pageHint.value = `搜索：「${keyword}」`;
  await searchStore.loadTabResults({ reset: true });
}

function onCreate() {
  if (!isAuthed.value) {
    pageHint.value = "请先登录后再使用创作中心。";
    pushToast("请先登录后再使用创作中心", "info");
    return;
  }
  if (showStudio.value) {
    openUploadModal();
    return;
  }
  if (profileFeedOpen.value) {
    closeProfileFeed();
  }
  if (activeMenuKey.value === "shorts") {
    void behaviorStore.flushShortsHistory();
  }
  if (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed") {
    pauseShortsFeedVideos({ muteForeground: true });
  }
  showVideoDetail.value = false;
  videoDetail.value = null;
  showProfileCenter.value = false;
  mediaPreviewOpen.value = false;
  void router.push("/studio");
}

function closeStudio() {
  if (window.history.state?.back) {
    router.back();
    return;
  }
  void router.push(menuPathFor(activeMenuKey.value));
}

/** 打开上传弹窗：状态随组件重挂载自复位，URL 承载历史 */
const openUploadModal = () => {
  void router.push("/upload");
};

/** ESC / 关闭按钮共用：离开 /upload，有来路则回退，深链直入则回当前菜单页 */
function leaveUploadRoute() {
  uploadModalOpen.value = false;
  if (window.history.state?.back) {
    router.back();
    return;
  }
  void router.push(menuPathFor(activeMenuKey.value));
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
const resolveVideoUrlForListItem = (videoId, hintItem) =>
  resolveVideoUrlFromLists(videoId, hintItem, [
    profileWorksRows.value,
    cards.value,
    likedRows.value,
  ]);

/** 跨列表补丁：实现已移入 feedPatchStore（各域 store 只改自己的列表），这里保留同名薄封装 */
const patchFeedVideoById = (videoId, patch) => feedPatch.patchVideoById(videoId, patch);
/** 同一作者下所有视频条目的 isFollow（与列表数据一致，不请求详情） */
const patchIsFollowForAuthor = (authorId, isFollow) =>
  feedPatch.patchAuthorFollow(authorId, isFollow);

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

const cancelDeleteCurrentSelfVideo = () => shortsStore.cancelDeleteCurrentSelfVideo();
const confirmDeleteCurrentSelfVideo = () => shortsStore.confirmDeleteCurrentSelfVideo();

function onStudioRowRequestDelete(row) {
  if (!row?.id) return;
  shortsStore.pendingDeleteVideoId = String(row.id);
  shortsStore.selfVideoDeleteConfirmOpen = true;
  closeStudioRowMenu();
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
    shortsStore.reloadFeedPlayers();
  });
}

function closeTopMostOverlayByEsc() {
  if (uploadModalOpen.value) {
    leaveUploadRoute();
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
    topbarSearchRef.value?.focusInput?.();
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
    shortsStore.reloadFeedPlayers();
  });
}

/** 赞过连播：实现已移入 shortsStore.openLikedFeed */

/** 退出全屏流：实现已移入 shortsStore */
const closeProfileFeed = () => shortsStore.closeProfileFeed();

/** 关闭全屏连播后同步 URL：有来路则回退，深链直入则回当前菜单页 */
function leaveFeedRoute() {
  if (!route.path.startsWith("/feed/")) return;
  if (window.history.state?.back) {
    router.back();
    return;
  }
  void router.push(menuPathFor(activeMenuKey.value));
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
    if (!route.path.startsWith("/feed/")) {
      const target = feedRouteForCurrentScope();
      if (target) {
        void router.push(target);
      }
    }
  } else {
    window.removeEventListener("keydown", onProfileFeedKeydown);
    /** 宏任务延迟：让菜单切换/进主页等并发 push 先落地，仅在 URL 仍停留在 /feed 时补离开 */
    setTimeout(() => {
      if (!profileFeedOpen.value && route.path.startsWith("/feed/")) {
        leaveFeedRoute();
      }
    }, 0);
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
  shortsStore.bumpPlayToken();
  resetShortsFeedProgressUiForSwitch();
  const tokenAtSwitch = shortsStore.shortsFeedPlayToken;
  nextTick(() => {
    const inMainShorts =
      activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed";
    if (!inMainShorts && !profileFeedOpen.value) return;
    if (tokenAtSwitch !== shortsStore.shortsFeedPlayToken) return;
    shortsStore.reloadFeedPlayers();
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

/** 登录表单与验证码在 AuthScene，这里只负责登录后的 App 级首屏装载 */
async function submitLogin(credentials) {
  authStore.authLoading = true;
  authStore.authMessage = "";
  try {
    const responseToken = await loginApi(credentials);
    authStore.signIn(responseToken);
    void notificationStore.refreshNotifyUnreadCount();
    authStore.authMessage = "登录成功。";
    pushToast("登录成功", "success");
    const homeMenu = primaryMenus[0];
    activeMenuLabel.value = "首页";

    showLoginLoadingOverlay.value = true;
    await nextTick();

    const loadAfterLogin = async () => {
      await profileStore.loadUser();
      const vid = route.path.startsWith("/video/") ? String(route.params.id ?? "") : "";
      if (vid) {
        const ok = await restoreVideoDetailFromUrl(vid);
        if (!ok) {
          void router.replace("/");
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
    authStore.authMessage = `登录失败：${error.message}`;
    pushToast(`登录失败：${error.message || "请检查账号信息"}`, "error");
  } finally {
    authStore.authLoading = false;
    showLoginLoadingOverlay.value = false;
  }
}

function logout() {
  behaviorStore.resetShortsHistory();
  authStore.signOut();
  cards.value = [];
  profileStore.close();
  showStudio.value = false;
  uploadModalOpen.value = false;
  showVideoDetail.value = false;
  videoDetail.value = null;
  if (route.path.startsWith("/video/")) {
    void router.replace(menuPathFor(activeMenuKey.value));
  }
  authStore.authMessage = "你已退出登录。";
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
    homeCategoryKey: homeCategoryKey.value,
    homeSortKey: homeSortKey.value,
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
  if (route.path.startsWith("/video/")) {
    void router.replace(menuPathFor(activeMenuKey.value));
  }
  authStore.authMessage = msg;
}

function onWindowPopState() {
  if (profileFeedOpen.value) {
    closeProfileFeed();
    return;
  }
  /** 详情/菜单/创作中心的收敛都交给 route watcher，这里只兜主页的 return context */
  if (!showProfileCenter.value) return;
  /** profile 内部自/他切换（/profile ↔ /profile/:id）后退不关主页，return context 保留 */
  const stillProfile =
    window.location.pathname === "/profile" ||
    window.location.pathname.startsWith("/profile/");
  if (stillProfile) {
    return;
  }
  const returnCtx = profileStore.readReturnContext();
  showProfileCenter.value = false;
  profileTargetUserId.value = null;
  if (returnCtx?.kind === "shortsFeed") {
    profileStore.clearReturnContext();
    void restoreProfileReturnContext(returnCtx);
    return;
  }
  profileStore.clearReturnContext();
  /** 上一个历史条目若是 /video/:id，route watcher 会自动恢复详情 */
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
  setActiveMenu(primaryMenus[0]);
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
    homeCategoryKey.value,
    homeSortKey.value,
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
      await notificationStore.startSession();
    } else {
      notificationStore.stopSession();
      hideShortsBufferingOverlay();
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

/** SearchView 的交互已改经 searchBridgeHooks 取回，不再走 provide/inject */
registerSearchBridgeHooks({
  onSearchUserRowActivate,
  searchUserRowIsSelf,
  searchUserRowIsFollowed,
  toggleSearchUserFollow,
  onSearchFeedLikeClick,
});

/** 搜索页文案与分页常量已移至 stores/searchStore.js */

let searchScrollLoadTimer = null;


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

/** 搜索全屏放大层：播放控制是命令式的，实现已移入 searchStore，close/恢复经 store 同名 action */
const closeSearchExpand = () => searchStore.closeSearchExpand();
const flushSearchExpandPlaybackResume = (reason) => searchStore.flushSearchExpandPlaybackResume(reason);


watch(searchActive, (v) => {
  if (!v && searchExpandOpen.value) closeSearchExpand();
  if (!v && searchScrollLoadTimer) {
    clearTimeout(searchScrollLoadTimer);
    searchScrollLoadTimer = null;
  }
});

/** HomeView 的交互已改经 homeBridgeHooks 取回，不再走 provide/inject */
/** StudioView 的昵称/头像改读 authStore，上传/删除经 studioBridgeHooks 取回，不再走 provide/inject */

// 创作中心 store 的跨域依赖接线(App 拥有 profile 加载与跨列表补丁)
registerStudioHooks({
  getUserId: () => currentUserId.value,
  ensureProfile: () => profileStore.loadUser(),
  patchFeedById: patchFeedVideoById,
  openUploadModal,
  requestDeleteRow: onStudioRowRequestDelete,
});

function loadStudioVideos() {
  return studioStore.load();
}
function closeStudioRowMenu() {
  studioStore.closeRowMenu();
}

registerLikedHooks({ getUserId: () => currentUserId.value });
registerHomeHooks({
  reloadHome: () => loadModuleCards(primaryMenus[0]),
  shortsStripRef: homeShortsStripRef,
  onHomeShortsStripScroll,
  onHomeShortsStripWheel,
  openVideoDetail: onVideoCardClick,
  openShortsFeed: onHomeShortCardClick,
  isProfileCenterOpen: () => showProfileCenter.value,
});
/** menuOrchestrationStore 的宿主依赖：搜索预览观察器与上传弹窗开关仍在 App */
registerMenuOrchestrationHooks({
  teardownSearchVideoObserver: () => teardownSearchVideoObserver(),
  closeUploadModal: () => {
    uploadModalOpen.value = false;
  },
});
/** navigationStore 的宿主依赖：上传弹窗 / 创作中心 / 竖屏短播放器的开关仍在 App */
registerNavigationHooks({
  closeAppOverlays: () => {
    uploadModalOpen.value = false;
    showStudio.value = false;
  },
});
/** searchStore 的宿主依赖：搜索结果列表的内联预览观察器与跨列表补丁仍在 App */
registerSearchExpandHooks({
  teardownSearchVideoObserver: () => teardownSearchVideoObserver(),
  setupSearchVideoAutoplay: () => setupSearchVideoAutoplay(),
  patchFeedVideoById: (id, patch) => patchFeedVideoById(id, patch),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
});
/** shortsStore 的宿主依赖：评论数变更跨多份列表打补丁，菜单归属与播放代次仍在 App */
registerShortsHooks({
  patchFeedVideoById: (id, patch) => patchFeedVideoById(id, patch),
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
  isShortsMenuActive: () => activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed",
  isSubscribedMenuActive: () => activeMenuKey.value === "subscribed",
  removeVideoById: (id) => feedPatch.removeVideoById(id),
  notify: (msg) => {
    pageHint.value = msg;
  },
  resolveUrlFallback: (id, item) => resolveVideoUrlForListItem(id, item),
});

/** detailStore 的宿主依赖：打开详情要收起 App 级遮罩，路由进出来自 App（router as history manager） */
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
  patchAuthorFollow: (authorId, isFollow) => patchIsFollowForAuthor(authorId, isFollow),
  onSearchTagNavigate: (tag) => onSearchTagNavigate(tag),
  navigateToVideo: (id, { replace = false } = {}) => {
    const target = `/video/${id}`;
    if (route.path === target) return;
    if (replace) {
      void router.replace(target);
    } else {
      void router.push(target);
    }
  },
  leaveVideoDetail: () => {
    if (route.path.startsWith("/video/") && window.history.state?.back) {
      router.back();
      return;
    }
    profileStore.clearReturnContext();
    void router.replace(menuPathFor(activeMenuKey.value));
  },
});

/** profileStore 的宿主依赖：打开主页要收起 App 级遮罩，作品卡要进全屏短视频流 */
registerProfileHooks({
  prepareOpen: () => {
    searchActive.value = false;
    teardownSearchVideoObserver();
    if (activeMenuKey.value === "home") {
      homeStore.saveSnapshot();
    }
    if (activeMenuKey.value === "shorts") {
      void behaviorStore.flushShortsHistory();
    }
    if (activeMenuKey.value === "shorts" || activeMenuKey.value === "subscribed") {
      pauseShortsFeedVideos({ muteForeground: true });
    }
    const returnCtx = captureProfileReturnContext();
    if (profileFeedOpen.value) {
      closeProfileFeed();
    }
    showStudio.value = false;
    uploadModalOpen.value = false;
    showVideoDetail.value = false;
    videoDetail.value = null;
    activeMenuLabel.value = "个人中心";
    return returnCtx;
  },
  pushHistory: (key) => {
    const target = key === "self" ? "/profile" : `/profile/${key}`;
    if (route.path !== target) {
      void router.push(target);
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
    void behaviorStore.flushShortsHistory({ keepalive: true });
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
      void behaviorStore.flushShortsHistory({ keepalive: true });
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
  const vid = route.path.startsWith("/video/") ? String(route.params.id ?? "") : "";
  if (vid && !showVideoDetail.value) {
    const ok = await restoreVideoDetailFromUrl(vid);
    if (!ok) {
      void router.replace("/");
      await loadModuleCards(homeMenu);
    } else {
      await nextTick();
      requestAnimationFrame(() => {
        detailStore.scheduleAutoplayRetries();
        setTimeout(() => detailStore.scheduleAutoplayRetries(), 400);
        setTimeout(() => detailStore.scheduleAutoplayRetries(), 1200);
      });
    }
  } else if (!vid) {
    if (showProfileCenter.value || showStudio.value) {
      /** 深链/刷新落在 /profile、/studio：view 已由 route watcher 打开，底层菜单数据在离开叠层时再加载 */
      return;
    }
    const state = loadUiStateFromSession();
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
        shortsStore.reloadFeedPlayers();
      });
    }
  }
});

onBeforeUnmount(() => {
  teardownSearchVideoObserver();
  searchStore.detachSearchExpandEscape();
  shortsStore.clearShortsBufferingUITimer();
  notificationStore.stopSession();
  stopCardHoverPreview();
  window.removeEventListener("resize", onWindowResize);
  window.removeEventListener("scroll", onWindowScroll);
  window.removeEventListener("keydown", onGlobalKeydown);
  window.removeEventListener("popstate", onWindowPopState);
  window.removeEventListener("pageshow", onWindowPageShow);
  window.removeEventListener(getAuthExpiredEventName(), onAuthExpired);
  void behaviorStore.flushShortsHistory({ keepalive: true });
});
</script>

<template>
    <AuthScene v-if="!isAuthed" @submit-login="submitLogin" />

  <div v-else class="app-layout">
    <SideNavbar
      v-if="!showStudio && !showVideoDetail"
      @select-menu="setActiveMenu"
      @open-my-profile="onSidebarOpenMyProfile"
    />

    <div class="main-wrapper">
      <header class="topbar">
        <TopbarSearch ref="topbarSearchRef" @search="onSearch" />

        <div class="top-actions">
          <button v-if="showVideoDetail" type="button" class="action-btn" @click="closeVideoDetail">返回</button>
          <button v-else-if="showStudio" type="button" class="action-btn" @click="closeStudio">返回</button>
          <button class="action-btn" @click="onCreate">{{ showStudio ? "上传视频" : "创建" }}</button>
          <NotifyPanel />
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

      <ToastStack />

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
        <!-- 主区视图由路由解析（菜单 / studio / profile）；/upload 归底到来路视图 -->
        <component :is="mainView" />
        <SearchView v-if="searchActive && !showStudio" :scroll-ref="searchFeedScrollRef" />
        <!-- 常驻：搜索结果页复用同一 feed 实例防双开 -->
        <ShortsView v-if="!showStudio" />
      </main>

      <!-- 视频详情：路由命名视图，渲染在 main 之外（main 被 v-show 隐藏） -->
      <RouterView name="detail" />

      <UploadModal v-if="uploadModalOpen" />

      <BaseModal
        :open="selfVideoDeleteConfirmOpen"
        overlay-class="shorts-delete-modal-overlay"
        card-class="modal-card shorts-delete-modal"
        :close-on-esc="false"
        aria-label="确认删除视频"
        @close="cancelDeleteCurrentSelfVideo"
      >
        <h3>删除视频</h3>
        <p class="shorts-delete-modal-text">确定删除该视频吗？删除后将无法恢复。</p>
        <div class="shorts-delete-modal-actions">
          <button type="button" class="action-btn" @click="cancelDeleteCurrentSelfVideo">取消</button>
          <button type="button" class="action-btn shorts-delete-btn" @click="confirmDeleteCurrentSelfVideo">确认删除</button>
        </div>
      </BaseModal>

    </div>

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
