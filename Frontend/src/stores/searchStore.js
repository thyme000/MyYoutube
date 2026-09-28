/**
 * 搜索域状态：关键词、分页结果、下拉联想、列表内预览与全屏放大层。
 * 全屏放大层的播放控制是命令式的（PIP / 全屏 / 音量 / 拖动），所以播放器 DOM ref 由宿主经
 * registerSearchExpandRefs 注入；跨域能力（短视频历史、跨列表补丁、转发弹窗）经 registerSearchExpandHooks 注入。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { fetchSearchHot, fetchSearchVideo, fetchSearchUser, fetchSearchTag } from "../api/searchApi.js";
import { cancelLikeVideo, dislikeVideo, likeVideo } from "../api/likeApi.js";
import { fetchVideoDetail, normalizeVideoDetail } from "../api/videoApi.js";
import { followAuthor, unfollowAuthor } from "../api/followApi.js";
import { parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { isLongVideoItem, readVideoDurationFromVideoEl, resolveVideoUrl } from "../utils/videoCard.js";
import { sendPlaybackResumePut } from "../utils/playbackResume.js";
import { toast as pushToast } from "../utils/toast.js";
import { useAuthStore } from "./authStore.js";
import { useBehaviorStore } from "./behaviorStore.js";
import { useCommentPanelStore } from "./commentPanelStore.js";
import { useFollowStore } from "./followStore.js";

export const SEARCH_PAGE_SIZE = 10;

/** 搜索页文案（与产品约定一致） */
export const SEARCH_UI = {
  placeholder: "搜索视频标题、用户昵称、标签或ID",
  promptTitle: "猜你想搜",
  hotTitle: "热搜榜",
  emptyKeyword: "输入关键词开始搜索",
  noResult: "没有找到相关内容，换个关键词试试",
  promptError: "提示加载失败，请稍后重试",
  searchError: "搜索失败，请检查网络后重试",
};

/** 放大层播放器 DOM ref（app 内单例，模块级持有不入 store） */
const expandRefs = { main: null, blur: null, stage: null };

export function registerSearchExpandRefs({ main, blur, stage } = {}) {
  if (main) expandRefs.main = main;
  if (blur) expandRefs.blur = blur;
  if (stage) expandRefs.stage = stage;
}

let expandEscapeHandler = null;
let lastExpandLikeAt = 0;
/** 全屏长视频续播上报节流（与详情页长视频一致，写入 PUT /videos/playback/resume） */
let expandResumeTickCount = 0;
let expandResumeInFlight = false;
let expandResumeLastSentAtMs = 0;

const hooks = {
  /** 打开放大层要停掉搜索结果列表里的内联预览播放器 */
  teardownSearchVideoObserver: () => {},
  /** 关闭放大层后恢复列表内联自动播放 */
  setupSearchVideoAutoplay: () => {},
  enqueueShortsHistory: () => {},
  flushShortsHistory: () => {},
  /** 跨多份列表同步点赞数 / 关注态 */
  patchFeedVideoById: () => {},
  patchAuthorFollow: () => {},
  openForwardVideo: () => {},
};

export function registerSearchExpandHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

export const useSearchStore = defineStore("search", {
  state: () => ({
    searchKeyword: "",
    searchActive: false,
    searchTab: "all",
    searchSubmittedKeyword: "",
    searchInputFocused: false,
    searchDropdownOpen: false,
    searchPromptRows: [],
    searchHotRows: [],
    searchPromptLoading: false,
    searchHotLoading: false,

    searchVideoRows: [],
    searchVideoPageNo: 1,
    searchVideoPages: 0,
    searchVideoLoading: false,
    searchVideoLoadingMore: false,
    searchVideoSort: "relevance",
    searchUserRows: [],
    searchUserPageNo: 1,
    searchUserPages: 0,
    searchUserLoading: false,
    searchUserLoadingMore: false,
    searchTagRows: [],
    searchTagPageNo: 1,
    searchTagPages: 0,
    searchTagLoading: false,
    searchTagLoadingMore: false,
    searchAllUserStrip: [],
    searchResultError: "",

    /** 搜索流：放大层播放（不跳转详情） */
    searchExpandOpen: false,
    searchExpandCard: null,
    searchExpandIndex: 0,
    searchFeedProgressMap: {},
    searchFeedLikeBusyId: null,
    searchFeedPausedMap: {},
    searchFeedListPrefs: {},
    searchFeedMainVideoById: {},
    searchFeedMediaWrapById: {},

    searchExpandCurrentTime: 0,
    searchExpandDuration: 0,
    searchExpandPaused: true,
    /** 切换全屏搜索视频过程中忽略 pause / timeupdate，避免中央播放钮闪动 */
    searchExpandAutoplayPending: false,
    searchExpandMuted: false,
    searchExpandVolume: 0.85,
    searchExpandLikeBusy: false,
    searchExpandIsPortrait: true,
  }),

  getters: {
    /** 放大层可切换的列表：与搜索结果当前 tab 保持一致 */
    searchFeedVideoList: (s) => {
      if (s.searchTab === "tag") return s.searchTagRows;
      if (s.searchTab === "all" || s.searchTab === "video") return s.searchVideoRows;
      return [];
    },
    searchExpandScrubMax: (s) => {
      const d = Number(s.searchExpandDuration);
      if (Number.isFinite(d) && d > 0) return d;
      return 1;
    },
    searchExpandScrubDisplayValue: (s) => {
      const t = Number(s.searchExpandCurrentTime);
      const d = Number(s.searchExpandDuration);
      if (!Number.isFinite(t) || t < 0) return 0;
      if (Number.isFinite(d) && d > 0) return Math.min(t, d);
      return 0;
    },
    searchExpandDurationReady: (s) => {
      const d = Number(s.searchExpandDuration);
      return Number.isFinite(d) && d > 0;
    },
    searchExpandLikedDisplay: (s) => {
      const c = s.searchExpandCard;
      if (!c?.id) return false;
      return Boolean(c.isLike);
    },
    searchExpandIsSelf: (s) => {
      const authorId = s.searchExpandCard?.authorId;
      const selfId = useAuthStore().userId;
      if (authorId == null || selfId == null) return false;
      return String(authorId) === String(selfId);
    },
    searchExpandIsFollowed: (s) => {
      const c = s.searchExpandCard;
      if (!c?.authorId) return false;
      const key = String(c.authorId);
      const override = useFollowStore().overrideByAuthorId;
      if (Object.prototype.hasOwnProperty.call(override, key)) {
        return override[key];
      }
      return Boolean(c.isFollow);
    },
  },

  actions: {
    /** 列表加载完成后的视图层钩子（观察器依赖 App.vue 的 DOM 引用），由宿主注册 */
    registerLoadedHook(fn) {
      this._loadedHook = typeof fn === "function" ? fn : null;
    },
    _notifyLoaded() {
      if (this._loadedHook) {
        nextTick(() => this._loadedHook());
      }
    },

    async loadHotIfNeeded() {
      if (this.searchHotRows.length || this.searchHotLoading) return;
      this.searchHotLoading = true;
      try {
        const raw = await fetchSearchHot();
        this.searchHotRows = Array.isArray(raw) ? raw.slice(0, 7) : [];
      } catch {
        this.searchHotRows = [];
      } finally {
        this.searchHotLoading = false;
      }
    },

    async loadVideoPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchVideoPageNo = 1;
        this.searchVideoRows = [];
      }
      const nextPage = reset ? 1 : this.searchVideoPageNo + 1;
      const maxP = this.searchVideoPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (!reset && this.searchVideoLoadingMore) return;
      if (this.searchVideoLoading || this.searchVideoLoadingMore) return;
      if (reset) this.searchVideoLoading = true;
      else this.searchVideoLoadingMore = true;
      try {
        const res = await fetchSearchVideo({
          keyword: kw,
          sort: this.searchVideoSort,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchVideoRows = reset ? list : [...this.searchVideoRows, ...list];
        this.searchVideoPageNo = nextPage;
        this.searchVideoPages = res.pages ?? 0;
        this.searchResultError = "";
        this._notifyLoaded();
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchVideoRows = [];
      } finally {
        this.searchVideoLoading = false;
        this.searchVideoLoadingMore = false;
      }
    },

    async loadUserStrip({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw || !reset) return;
      try {
        const res = await fetchSearchUser({ keyword: kw, pageNo: 1, pageSize: 8 });
        this.searchAllUserStrip = res.list || [];
      } catch {
        this.searchAllUserStrip = [];
      }
    },

    async loadUserPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchUserPageNo = 1;
        this.searchUserRows = [];
      }
      const nextPage = reset ? 1 : this.searchUserPageNo + 1;
      const maxP = this.searchUserPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (this.searchUserLoading || this.searchUserLoadingMore) return;
      if (reset) this.searchUserLoading = true;
      else this.searchUserLoadingMore = true;
      try {
        const res = await fetchSearchUser({
          keyword: kw,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchUserRows = reset ? list : [...this.searchUserRows, ...list];
        this.searchUserPageNo = nextPage;
        this.searchUserPages = res.pages ?? 0;
        this.searchResultError = "";
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchUserRows = [];
      } finally {
        this.searchUserLoading = false;
        this.searchUserLoadingMore = false;
      }
    },

    async loadTagPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchTagPageNo = 1;
        this.searchTagRows = [];
      }
      const nextPage = reset ? 1 : this.searchTagPageNo + 1;
      const maxP = this.searchTagPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (this.searchTagLoading || this.searchTagLoadingMore) return;
      if (reset) this.searchTagLoading = true;
      else this.searchTagLoadingMore = true;
      try {
        const res = await fetchSearchTag({
          keyword: kw,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchTagRows = reset ? list : [...this.searchTagRows, ...list];
        this.searchTagPageNo = nextPage;
        this.searchTagPages = res.pages ?? 0;
        this.searchResultError = "";
        this._notifyLoaded();
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchTagRows = [];
      } finally {
        this.searchTagLoading = false;
        this.searchTagLoadingMore = false;
      }
    },

    async loadTabResults({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      this.searchResultError = "";
      if (this.searchTab === "all") {
        await this.loadUserStrip({ reset: true });
        await this.loadVideoPage({ reset: true });
        return;
      }
      if (this.searchTab === "video") {
        await this.loadVideoPage({ reset: true });
        return;
      }
      if (this.searchTab === "user") {
        await this.loadUserPage({ reset: true });
        return;
      }
      if (this.searchTab === "tag") {
        await this.loadTagPage({ reset: true });
      }
    },

    selectTab(key) {
      if (this.searchTab === key) return;
      this.searchTab = key;
      void this.loadTabResults({ reset: true });
    },

    // ===== 全屏放大层 =====

    /** 摘除放大层的 Esc 监听（组件卸载时也要调，避免监听泄漏） */
    detachSearchExpandEscape() {
      if (expandEscapeHandler) {
        window.removeEventListener("keydown", expandEscapeHandler);
        expandEscapeHandler = null;
      }
    },

    searchExpandVideoUrl() {
      const card = this.searchExpandCard;
      if (!card) return "";
      return resolveVideoUrl(card);
    },

    openSearchExpand(card, index) {
      const panel = useCommentPanelStore();
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
      expandResumeTickCount = 0;
      expandResumeInFlight = false;
      expandResumeLastSentAtMs = 0;
      this.searchExpandCard = card;
      this.searchExpandIndex = index;
      this.searchExpandOpen = true;
      hooks.teardownSearchVideoObserver();
      this.detachSearchExpandEscape();
      expandEscapeHandler = (e) => {
        if (e.key === "Escape") this.closeSearchExpand();
      };
      window.addEventListener("keydown", expandEscapeHandler);
      nextTick(() => {
        document.querySelectorAll(".search-feed-slide .search-feed-video").forEach((v) => {
          try {
            v.pause();
          } catch {
            /* ignore */
          }
        });
        const main = expandRefs.main?.value;
        const blur = expandRefs.blur?.value;
        this.searchExpandCurrentTime = 0;
        this.searchExpandDuration = 0;
        this.searchExpandPaused = true;
        this.searchExpandIsPortrait = true;
        if (main) {
          try {
            main.volume = this.searchExpandVolume;
            main.muted = this.searchExpandMuted;
          } catch {
            /* ignore */
          }
        }
        if (blur) blur.play().catch(() => {});
        if (main) main.play().catch(() => {});
      });
    },

    onSearchExpandFgPlay() {
      this.searchExpandPaused = false;
      expandRefs.blur?.value?.play?.().catch(() => {});
      /** 搜索列表内预览不写入历史；仅在全屏放大且实际开始播放时上报短视频观看（PUT /videos/shorts/play） */
      if (this.searchActive) {
        const card = this.searchExpandCard;
        if (card) {
          hooks.enqueueShortsHistory(card.id, card);
        }
      }
    },

    onSearchExpandFgPause() {
      if (this.searchExpandAutoplayPending) {
        return;
      }
      this.searchExpandPaused = true;
      try {
        expandRefs.blur?.value?.pause?.();
      } catch {
        /* ignore */
      }
      if (isLongVideoItem(this.searchExpandCard)) {
        void this.flushSearchExpandPlaybackResume("pause");
      }
    },

    getSearchExpandCurrentPlayTimeMs() {
      const el = expandRefs.main?.value;
      if (!el) return 0;
      const ms = Math.floor((Number(el.currentTime || 0) || 0) * 1000);
      return ms > 0 ? ms : 0;
    },

    getSearchExpandPlaybackPayload(playTimeMs) {
      const userId = useAuthStore().userId;
      const videoId = this.searchExpandCard?.id;
      if (!userId || userId === "" || !videoId) return null;
      if (!Number.isFinite(Number(playTimeMs))) return null;
      return {
        videoId,
        playTime: playTimeMs,
        userId,
      };
    },

    flushSearchExpandPlaybackResume(reason) {
      if (!isLongVideoItem(this.searchExpandCard)) return Promise.resolve();
      const player = expandRefs.main?.value;
      if (!player) return Promise.resolve();

      if (reason !== "pause" && player.paused) {
        return Promise.resolve();
      }

      const playTimeMs = this.getSearchExpandCurrentPlayTimeMs();
      if (
        reason !== "pause" &&
        reason !== "switch" &&
        reason !== "leave" &&
        reason !== "unload" &&
        playTimeMs <= 0
      ) {
        return Promise.resolve();
      }

      const payload = this.getSearchExpandPlaybackPayload(playTimeMs);
      if (!payload) return Promise.resolve();

      const now = Date.now();
      if (reason === "tick" && now - expandResumeLastSentAtMs < 8_000) {
        return Promise.resolve();
      }
      if (expandResumeInFlight) {
        return Promise.resolve();
      }
      expandResumeInFlight = true;
      expandResumeLastSentAtMs = now;

      const keepalive = reason === "unload" || reason === "leave";
      return sendPlaybackResumePut(payload, { keepalive }).finally(() => {
        expandResumeInFlight = false;
      });
    },

    closeSearchExpand() {
      this.detachSearchExpandEscape();
      useCommentPanelStore().searchExpandCommentOpen = false;
      void this.flushSearchExpandPlaybackResume("leave");
      expandResumeTickCount = 0;
      this.searchExpandOpen = false;
      this.searchExpandCard = null;
      try {
        expandRefs.main?.value?.pause?.();
      } catch {
        /* ignore */
      }
      try {
        expandRefs.blur?.value?.pause?.();
      } catch {
        /* ignore */
      }
      hooks.flushShortsHistory();
      nextTick(() => {
        if (this.searchActive) hooks.setupSearchVideoAutoplay();
      });
    },

    toggleSearchExpandPlay() {
      const v = expandRefs.main?.value;
      if (!v) return;
      if (v.paused) v.play().catch(() => {});
      else v.pause();
    },

    onSearchExpandVideoTimeUpdate(e) {
      const v = e.target;
      this.searchExpandCurrentTime = Number(v.currentTime) || 0;
      if (!this.searchExpandAutoplayPending) {
        this.searchExpandPaused = v.paused;
      }
      const blur = expandRefs.blur?.value;
      if (blur && v && Math.abs(blur.currentTime - v.currentTime) > 0.2) {
        blur.currentTime = v.currentTime;
      }
      if (isLongVideoItem(this.searchExpandCard) && v && !v.paused) {
        expandResumeTickCount += 1;
        if (expandResumeTickCount >= 30) {
          expandResumeTickCount = 0;
          void this.flushSearchExpandPlaybackResume("tick");
        }
      }
    },

    onSearchExpandFgSeeked(e) {
      const fg = e.target;
      const blur = expandRefs.blur?.value;
      if (blur && fg) {
        blur.currentTime = fg.currentTime;
      }
    },

    onSearchExpandVideoLoadedMetadata(e) {
      const v = e.target;
      this.searchExpandIsPortrait =
        v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
      this.searchExpandDuration = readVideoDurationFromVideoEl(v);
    },

    onSearchExpandScrubInput(e) {
      const v = expandRefs.main?.value;
      if (!v) return;
      const t = Number(e.target.value);
      if (!Number.isFinite(t)) return;
      v.currentTime = t;
      this.searchExpandCurrentTime = t;
    },

    async searchExpandRequestPip() {
      const v = expandRefs.main?.value;
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
    },

    async searchExpandRequestFullscreen() {
      const el = expandRefs.stage?.value;
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
    },

    searchExpandToggleMute() {
      const v = expandRefs.main?.value;
      if (!v) return;
      v.muted = !v.muted;
      this.searchExpandMuted = v.muted;
    },

    onSearchExpandVolumeInput(e) {
      const v = expandRefs.main?.value;
      if (!v) return;
      const vol = Number(e.target.value);
      v.volume = vol;
      v.muted = vol === 0;
      this.searchExpandVolume = vol;
      this.searchExpandMuted = v.muted;
    },

    syncSearchExpandPlayerAfterSwitch() {
      const main = expandRefs.main?.value;
      const blur = expandRefs.blur?.value;
      this.searchExpandAutoplayPending = true;
      try {
        main?.load?.();
        blur?.load?.();
      } catch {
        /* ignore */
      }
      this.searchExpandCurrentTime = 0;
      this.searchExpandDuration = 0;
      this.searchExpandIsPortrait = true;
      nextTick(() => {
        try {
          main.volume = this.searchExpandVolume;
          main.muted = this.searchExpandMuted;
        } catch {
          /* ignore */
        }
        blur?.play?.().catch(() => {});
        const p = main?.play?.();
        if (p && typeof p.then === "function") {
          p.then(
            () => {
              this.searchExpandAutoplayPending = false;
            },
            () => {
              this.searchExpandAutoplayPending = false;
              this.searchExpandPaused = true;
            }
          );
        } else {
          this.searchExpandAutoplayPending = false;
        }
      });
    },

    searchExpandPrev() {
      const list = this.searchFeedVideoList;
      const i = this.searchExpandIndex;
      if (i <= 0 || !list.length) return;
      void this.flushSearchExpandPlaybackResume("switch");
      expandResumeTickCount = 0;
      this.searchExpandCard = list[i - 1];
      this.searchExpandIndex = i - 1;
      useCommentPanelStore().searchExpandCommentOpen = false;
      nextTick(() => this.syncSearchExpandPlayerAfterSwitch());
    },

    searchExpandNext() {
      const list = this.searchFeedVideoList;
      const i = this.searchExpandIndex;
      if (!list.length || i >= list.length - 1) return;
      void this.flushSearchExpandPlaybackResume("switch");
      expandResumeTickCount = 0;
      this.searchExpandCard = list[i + 1];
      this.searchExpandIndex = i + 1;
      useCommentPanelStore().searchExpandCommentOpen = false;
      nextTick(() => this.syncSearchExpandPlayerAfterSwitch());
    },

    async onSearchExpandLikeClick() {
      const cur = this.searchExpandCard;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录后再点赞", "info");
        return;
      }
      const t = Date.now();
      if (t - lastExpandLikeAt < 300) return;
      lastExpandLikeAt = t;
      if (this.searchExpandLikeBusy) return;
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      this.searchExpandLikeBusy = true;
      try {
        const wasLiked = Boolean(cur.isLike);
        if (wasLiked) {
          await cancelLikeVideo(vid);
          hooks.patchFeedVideoById(cur.id, {
            isLike: false,
            likeCount: Math.max(0, Number(cur.likeCount ?? 0) - 1),
          });
        } else {
          await likeVideo(vid);
          hooks.patchFeedVideoById(cur.id, {
            isLike: true,
            likeCount: Number(cur.likeCount ?? 0) + 1,
          });
        }
        const raw = await fetchVideoDetail(vid);
        const merged = normalizeVideoDetail(raw);
        if (merged && merged.likeCount != null) {
          hooks.patchFeedVideoById(cur.id, { likeCount: merged.likeCount });
        }
        void useBehaviorStore().report(
          cur.id,
          "LIKE",
          Number(this.searchExpandCurrentTime || 0) * 1000
        );
      } catch (error) {
        pushToast(error.message || "点赞失败", "error");
      } finally {
        this.searchExpandLikeBusy = false;
      }
    },

    async onSearchExpandDislikeClick() {
      const cur = this.searchExpandCard;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录", "info");
        return;
      }
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      try {
        await dislikeVideo(vid);
        void useBehaviorStore().report(
          cur.id,
          "DISLIKE",
          Number(this.searchExpandCurrentTime || 0) * 1000
        );
        pushToast("已标记为不感兴趣", "success");
      } catch (error) {
        pushToast(error.message || "操作失败", "error");
      }
    },

    async toggleSearchExpandFollow() {
      const c = this.searchExpandCard;
      if (!c?.authorId) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录后再关注", "info");
        return;
      }
      const auth = useAuthStore();
      const aid = String(c.authorId);
      if (auth.userId != null && aid === String(auth.userId)) {
        return;
      }
      const follow = useFollowStore();
      const currently = follow.getOverride(aid) ?? Boolean(c.isFollow);
      try {
        if (currently) {
          await unfollowAuthor(aid);
          follow.setOverride(aid, false);
          hooks.patchAuthorFollow(aid, false);
          pushToast("已取消关注", "success");
        } else {
          await followAuthor(aid);
          follow.setOverride(aid, true);
          hooks.patchAuthorFollow(aid, true);
          pushToast("关注成功", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },

    async onSearchExpandShareClick() {
      const cur = this.searchExpandCard;
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
    },

    onSearchExpandForwardClick() {
      hooks.openForwardVideo(this.searchExpandCard);
    },

    toggleSearchExpandCommentPanel() {
      if (!this.searchExpandCard?.id) {
        return;
      }
      const panel = useCommentPanelStore();
      panel.shortsCommentPanelOpen = false;
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
      panel.searchExpandCommentOpen = !panel.searchExpandCommentOpen;
    },
  },
});
