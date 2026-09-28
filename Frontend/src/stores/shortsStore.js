// @ts-check
/**
 * 短视频播放器域状态：沉浸 feed 播放态、播放控制与进度上报、评论侧栏，
 * 以及被历史/赞过/个人主页/关注复用的全屏队列（feedScope 区分数据源）。
 * 跨域能力（跨列表补丁、菜单归属）经 registerShortsHooks 注入；
 * 播放器 DOM ref 由宿主组件 ShortsView 经 registerShortsFeedPlayerRefs 注册。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import {
  resolveVideoUrl,
  resolveVideoUrlFromLists,
  readVideoDurationFromVideoEl,
} from "../utils/videoCard.js";
import { parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { formatRelativeTimeText } from "../utils/relativeTime.js";
import { commentDisplayNickname as commentDisplayNicknameText } from "../utils/comment.js";
import {
  DEFAULT_PROFILE_AVATAR_URL as defaultAuthorAvatar,
  sanitizeImageUrl,
  stripUserIdLikeName,
} from "../utils/userProfile.js";
import { sendPlaybackResumePut } from "../utils/playbackResume.js";
import { patchRowById, patchRowsByAuthorId, removeRowById } from "../utils/feedRowPatch.js";
import {
  fetchCommentPage,
  deleteComment as deleteCommentApi,
  publishComment as publishCommentApi,
} from "../api/commentApi.js";
import {
  likeVideo as likeVideoApi,
  cancelLikeVideo as cancelLikeVideoApi,
  dislikeVideo as dislikeVideoApi,
} from "../api/likeApi.js";
import {
  fetchVideoDetail,
  normalizeVideoDetail,
  fetchFollowVideoFeedPage,
  fetchOtherUserVideos,
  deleteMyVideo,
  setMyVideoPrivate,
  setMyVideoPublic,
  openMyVideoComment,
  closeMyVideoComment,
} from "../api/videoApi.js";
import { followAuthor, unfollowAuthor } from "../api/followApi.js";
import { toast as pushToast } from "../utils/toast.js";
import { useAuthStore } from "./authStore.js";
import { useBehaviorStore } from "./behaviorStore.js";
import { useCommentPanelStore } from "./commentPanelStore.js";
import { useCommentStore } from "./commentStore.js";
import { useDetailStore } from "./detailStore.js";
import { useFollowStore } from "./followStore.js";
import { useHomeStore } from "./homeStore.js";
import { useLikedStore } from "./likedStore.js";
import { useProfileStore } from "./profileStore.js";
import { useSearchStore } from "./searchStore.js";
import { useShareStore } from "./shareStore.js";

const SHORTS_COMMENT_PAGE_SIZE = 10;
/** 切换很快时不显示加载层，先等这么久再亮出遮罩 */
const SHORTS_BUFFERING_UI_DELAY_MS = 320;
/** PLAY 上报阈值：累计顺播达总时长比例且排除拖动（单次 timeupdate 跳跃过大不计入） */
const BEHAVIOR_PLAY_THRESHOLD = 0.7;
const BEHAVIOR_MAX_PLAY_DELTA_SEC = 3;
const FOLLOW_FEED_PAGE_SIZE = 12;
/** 距队列末尾还剩这么少条时预取下一页 */
const FOLLOW_FEED_PREFETCH_THRESHOLD = 3;

const playerRefs = { fg: null, bg: null, center: null };

/** 宿主注册全屏播放器 DOM ref（app 内单例，模块级持有不入 store） */
export function registerShortsFeedPlayerRefs({ fg, bg, center }) {
  if (fg) playerRefs.fg = fg;
  if (bg) playerRefs.bg = bg;
  if (center) playerRefs.center = center;
}

let bufferingUITimer = null;
let wheelCooldown = false;
let touchStartY = 0;
let lastLikeClickAt = 0;
let followFeedLoadSeq = 0;
let followAuthorLoadSeq = 0;
let longResumeInFlight = false;
let longResumeLastSentAtMs = 0;
let longResumeTickCount = 0;
let longResumeAppliedVideoId = "";

/**
 * @type {{
 *   patchFeedVideoById: (id: any, patch: any) => void,
 *   patchAuthorFollow: (authorId: any, isFollow: boolean) => void,
 *   isShortsMenuActive: () => boolean,
 *   isSubscribedMenuActive: () => boolean,
 *   removeVideoById: (id: string) => void,
 * }}
 */
const hooks = {
  /** 跨多份列表同步点赞数/评论数 */
  patchFeedVideoById: () => {},
  /** 跨列表同步同一作者的 isFollow */
  patchAuthorFollow: () => {},
  /** 当前是否停留在短视频 Tab（shorts / subscribed）——菜单状态机仍在 App */
  isShortsMenuActive: () => false,
  /** 当前是否停留在「关注」Tab——关注态判定要区分全部动态与按作者筛选 */
  isSubscribedMenuActive: () => false,
  /** 删除视频后从各列表移除（feedPatchStore 负责广播） */
  removeVideoById: () => {},
};

export function registerShortsHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

export const useShortsStore = defineStore("shorts", {
  state: () => ({
    /** 全屏竖屏流数据源：shorts / follow / profile（历史、赞过、他人主页共用播放器） */
    feedScope: "shorts",
    profileFeedQueue: [],
    likedFeedQueue: [],
    followFeedQueue: [],
    /** 关注页：选中某位作者时，右侧仅播该作者短视频（GET /videos/{userId}/list） */
    followSubscribedFilterAuthorId: null,
    followAuthorFeedQueue: [],
    followAuthorVideoPageNo: 1,
    followAuthorVideoHasMore: false,
    followAuthorLoadingMore: false,
    followVideoPageNo: 1,
    followVideoHasMore: true,
    followVideoLoadingMore: false,
    profileFeedOpen: false,

    shortsFeedIndex: 0,
    /**
     * 播放代次：切换视频时自增。渲染到 `:data-play-token`，loadedmetadata 时比对，
     * 用来丢弃「旧元素迟到的元数据」触发的自动播放。
     */
    shortsFeedPlayToken: 0,
    shortsFeedIsPortrait: false,
    shortsFeedDuration: 0,
    shortsFeedCurrentTime: 0,
    shortsFeedVolume: 0.85,
    shortsFeedMuted: false,
    /** 暂停时在画面中央显示三角形播放图标 */
    shortsFeedPaused: true,
    /** 切换视频后自动播放（含进入短视频页） */
    shortsFeedAutoplayPending: false,
    /** 短视频流：切换很快时不显示加载层 */
    shortsFeedVideoBuffering: false,

    /** 短视频流右侧评论侧栏（与详情共用 /comment API） */
    shortsPanelTopComments: [],
    shortsPanelCommentsLoading: false,
    shortsPanelCommentsPageNo: 1,
    shortsPanelCommentsPages: 0,
    shortsPanelCommentDraft: "",
    shortsPanelCommentSubmitting: false,
    shortsPanelReplyTarget: null,
    shortsPanelThreadByRootId: {},
    shortsPanelExpandedRoots: {},

    /** 短视频流点赞：切换条时重置 */
    shortsLikeBusy: false,
    /** 自己的视频在全屏短视频里显示更多操作（三点） */
    selfVideoActionOpen: false,
    /** 删除确认弹窗：短视频流与创作中心表格共用同一份确认状态 */
    selfVideoDeleteConfirmOpen: false,
    /** 删除确认弹窗对应的视频 id（创作中心 / 短视频流共用） */
    pendingDeleteVideoId: null,
    /** 顺播累计秒数 + 当前条 id */
    shortsPlaybackAccSec: 0,
    shortsLastTimeSec: null,
    shortsAccVideoId: "",
  }),

  getters: {
    /**
     * 全屏流当前队列：feedScope 决定数据源，首页/默认流读 homeStore
     * @returns {any[]}
     */
    unifiedFeedList: (s) => {
      if (s.feedScope === "profile") return s.profileFeedQueue;
      if (s.feedScope === "liked") return s.likedFeedQueue;
      if (s.feedScope === "follow") {
        const aid = s.followSubscribedFilterAuthorId;
        if (aid != null && aid !== "") return s.followAuthorFeedQueue;
        return s.followFeedQueue;
      }
      const home = useHomeStore();
      if (s.feedScope === "home") return home.homeShortsRows;
      return home.cards;
    },
    /** @returns {any} */
    shortsFeedCurrent() {
      const list = this.unifiedFeedList;
      if (!list.length) return null;
      const i = Math.min(Math.max(0, this.shortsFeedIndex), list.length - 1);
      return list[i];
    },
    /**
     * 评论侧栏：短视频流、搜索全屏放大或搜索列表共用同一套列表与接口
     * @returns {any}
     */
    activeCommentVideo() {
      const panel = useCommentPanelStore();
      const search = useSearchStore();
      if (panel.searchExpandCommentOpen && search.searchExpandCard) {
        return search.searchExpandCard;
      }
      if (panel.searchListCommentOpen && panel.searchListCommentCard) {
        return panel.searchListCommentCard;
      }
      return this.shortsFeedCurrent;
    },
    /** @returns {boolean} */
    activeCommentVideoAllowsComment() {
      return Number(this.activeCommentVideo?.allowComment ?? 1) === 1;
    },

    // ===== 当前条的展示值（模板直接消费，避免在视图里重复推导） =====

    /** @returns {string} */
    shortsFeedVideoSrc() {
      return resolveVideoUrl(this.shortsFeedCurrent);
    },
    /**
     * 普通流看接口 isLike；赞过列表后端可能不传该字段，默认视为已赞，
     * 仅当本地明确 patch 为 isLike === false（取消赞）时显示空心。
     * @returns {boolean}
     */
    shortsFeedLikedDisplay() {
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) {
        return false;
      }
      if (this.feedScope === "liked") {
        return cur.isLike !== false;
      }
      return Boolean(cur.isLike);
    },
    /**
     * 进度条 max：duration 未就绪时若给 0，而 currentTime 仍是上一段或中间值，
     * 原生 range 会把滑块钳到最右，看起来像「暂停就跳到结尾」或闪烁。
     * @returns {number}
     */
    shortsScrubMax() {
      const d = Number(this.shortsFeedDuration);
      if (Number.isFinite(d) && d > 0) {
        return d;
      }
      return 1;
    },
    /** @returns {number} */
    shortsScrubDisplayValue() {
      const t = Number(this.shortsFeedCurrentTime);
      const d = Number(this.shortsFeedDuration);
      if (!Number.isFinite(t) || t < 0) {
        return 0;
      }
      if (Number.isFinite(d) && d > 0) {
        return Math.min(t, d);
      }
      return 0;
    },
    /** @returns {boolean} */
    shortsDurationReady() {
      const d = Number(this.shortsFeedDuration);
      return Number.isFinite(d) && d > 0;
    },
    /** @returns {string} */
    shortsFeedAuthorName() {
      const c = this.shortsFeedCurrent;
      const nick = stripUserIdLikeName(c?.authorNickname) || stripUserIdLikeName(c?.author);
      if (nick) {
        return nick;
      }
      if (this.feedScope === "profile") {
        const profile = useProfileStore();
        const p = stripUserIdLikeName(profile.user?.nickName || profile.user?.nickname);
        if (p) {
          return p;
        }
        if (profile.isViewingSelf) {
          const self = useAuthStore().selfProfile;
          const s = stripUserIdLikeName(self?.nickName || self?.nickname);
          if (s) {
            return s;
          }
        }
      }
      if (this.feedScope === "liked") {
        return nick || "创作者";
      }
      return "创作者";
    },
    /** @returns {string} */
    shortsFeedMetaTimeText() {
      return formatRelativeTimeText(this.shortsFeedCurrent?.createTime || "");
    },
    /** @returns {string} */
    shortsFeedTitleText() {
      return this.shortsFeedCurrent?.title || "未命名主题";
    },
    /** @returns {string} */
    shortsFeedDescText() {
      return this.shortsFeedCurrent?.description || this.shortsFeedCurrent?.title || "";
    },
    /**
     * 关注 Tab：与左侧关注列表同一套 avatarDisplaySrc，避免视频 VO 里作者头像与侧栏不一致/糊片
     * @returns {string}
     */
    shortsFeedAuthorAvatar() {
      const c = this.shortsFeedCurrent;
      if (!c) {
        return defaultAuthorAvatar;
      }
      if (
        hooks.isSubscribedMenuActive() &&
        this.feedScope === "follow" &&
        c.authorId != null &&
        c.authorId !== ""
      ) {
        const key = String(c.authorId);
        const row = useProfileStore().followingRows.find((u) => u && String(u.id) === key);
        if (row?.avatarDisplaySrc) {
          return row.avatarDisplaySrc;
        }
      }
      const fromAuthor =
        sanitizeImageUrl(c.authorAvatarUrl ?? "") || sanitizeImageUrl(c.authorAvatar ?? "");
      if (fromAuthor) {
        return fromAuthor;
      }
      const profile = useProfileStore();
      if (this.feedScope === "profile" && profile.open) {
        if (profile.isViewingSelf) {
          const selfAv = sanitizeImageUrl(useAuthStore().selfProfile?.avatarUrl || "");
          if (selfAv) {
            return selfAv;
          }
          return useAuthStore().selfAvatarUrl;
        }
        const pav = sanitizeImageUrl(profile.user?.avatarUrl || "");
        if (pav) {
          return pav;
        }
      }
      return defaultAuthorAvatar;
    },
    /** @returns {boolean} */
    shortsIsFollowedCurrent() {
      const c = this.shortsFeedCurrent;
      if (!c?.authorId) {
        return false;
      }
      const key = String(c.authorId);
      const override = useFollowStore().overrideByAuthorId;
      if (Object.prototype.hasOwnProperty.call(override, key)) {
        return override[key];
      }
      // 关注 Tab：侧栏点「某作者」时该作者必已关注；全部动态里作者应在关注列表中
      if (hooks.isSubscribedMenuActive() && this.feedScope === "follow") {
        const filtered = this.followSubscribedFilterAuthorId;
        if (filtered != null && filtered !== "" && String(filtered) === key) {
          return true;
        }
        const following = useProfileStore().followingRows;
        if ((filtered == null || filtered === "") && following.some((u) => u && String(u.id) === key)) {
          return true;
        }
      }
      return Boolean(c.isFollow);
    },
    /** @returns {boolean} */
    shortsIsSelfCurrent() {
      const authorId = this.shortsFeedCurrent?.authorId;
      const selfId = useAuthStore().userId;
      if (authorId == null || selfId == null) {
        return false;
      }
      return String(authorId) === String(selfId);
    },
  },

  actions: {
    // 同一份视频/作者会同时出现在多份列表，feedPatchStore 负责广播，各域只改自己的
    patchVideoById(id, patch) {
      this.profileFeedQueue = patchRowById(this.profileFeedQueue, id, patch);
      this.likedFeedQueue = patchRowById(this.likedFeedQueue, id, patch);
      this.followFeedQueue = patchRowById(this.followFeedQueue, id, patch);
      this.followAuthorFeedQueue = patchRowById(this.followAuthorFeedQueue, id, patch);
    },
    patchAuthorFollow(authorId, isFollow) {
      this.profileFeedQueue = patchRowsByAuthorId(this.profileFeedQueue, authorId, isFollow);
      this.likedFeedQueue = patchRowsByAuthorId(this.likedFeedQueue, authorId, isFollow);
      this.followFeedQueue = patchRowsByAuthorId(this.followFeedQueue, authorId, isFollow);
      this.followAuthorFeedQueue = patchRowsByAuthorId(this.followAuthorFeedQueue, authorId, isFollow);
    },
    removeVideoById(id) {
      this.profileFeedQueue = removeRowById(this.profileFeedQueue, id);
      this.likedFeedQueue = removeRowById(this.likedFeedQueue, id);
      this.followFeedQueue = removeRowById(this.followFeedQueue, id);
      this.followAuthorFeedQueue = removeRowById(this.followAuthorFeedQueue, id);
    },

    /**
     * 全屏连播入口：历史/赞过/他人主页等列表以现有行开播（不重复请求详情）。
     * 播放器 DOM ref 由宿主经 registerShortsFeedPlayerRefs 注入。
     */
    openProfileFeed(sourceList, index, { forceVideoType = "" } = {}) {
      const source = Array.isArray(sourceList) ? sourceList : [];
      const playableQueue = source
        .map((x) => ({
          ...x,
          videoType: forceVideoType || x?.videoType || "",
          videoUrl: resolveVideoUrl(x),
        }))
        .filter((x) => x.videoUrl);
      if (!playableQueue.length) return;
      this.feedScope = "profile";
      this.profileFeedQueue = playableQueue;
      this.shortsFeedIndex = Math.min(Math.max(0, Number(index) || 0), playableQueue.length - 1);
      this.shortsFeedAutoplayPending = true;
      this.profileFeedOpen = true;
      nextTick(() => {
        this.reloadFeedPlayers();
      });
    },

    // ===== 播放缓冲遮罩 =====

    clearShortsBufferingUITimer() {
      if (bufferingUITimer != null) {
        clearTimeout(bufferingUITimer);
        bufferingUITimer = null;
      }
    },

    scheduleShortsBufferingOverlay() {
      this.clearShortsBufferingUITimer();
      this.shortsFeedVideoBuffering = false;
      bufferingUITimer = window.setTimeout(() => {
        bufferingUITimer = null;
        this.shortsFeedVideoBuffering = true;
      }, SHORTS_BUFFERING_UI_DELAY_MS);
    },

    hideShortsBufferingOverlay() {
      this.clearShortsBufferingUITimer();
      this.shortsFeedVideoBuffering = false;
    },

    // ===== 切换与播放控制 =====

    shortsFeedPrev() {
      const cur = this.shortsFeedCurrent;
      const dur = Number(this.shortsFeedDuration || 0);
      const now = Number(this.shortsFeedCurrentTime || 0);
      if (this.isLongShortsFeedVideo(cur)) {
        void this.flushShortsLongPlaybackResume("switch");
      }
      if (cur?.id) {
        useBehaviorStore().enqueueShortsHistory(cur.id, cur);
      }
      if (cur?.id && dur > 0 && now / dur < 0.95) {
        void useBehaviorStore().report(cur.id, "SKIP", now * 1000);
      }
      const n = this.unifiedFeedList.length;
      if (!n) {
        return;
      }
      this.shortsFeedIndex = (this.shortsFeedIndex - 1 + n) % n;
    },

    shortsFeedNext() {
      const cur = this.shortsFeedCurrent;
      const dur = Number(this.shortsFeedDuration || 0);
      const now = Number(this.shortsFeedCurrentTime || 0);
      if (this.isLongShortsFeedVideo(cur)) {
        void this.flushShortsLongPlaybackResume("switch");
      }
      if (cur?.id) {
        useBehaviorStore().enqueueShortsHistory(cur.id, cur);
      }
      if (cur?.id && dur > 0 && now / dur < 0.95) {
        void useBehaviorStore().report(cur.id, "SKIP", now * 1000);
      }
      const n = this.unifiedFeedList.length;
      if (!n) {
        return;
      }
      this.shortsFeedIndex = (this.shortsFeedIndex + 1) % n;
    },

    onShortsFeedFgLoadStart() {
      this.scheduleShortsBufferingOverlay();
    },

    onShortsFeedFgCanPlay() {
      this.hideShortsBufferingOverlay();
    },

    onShortsFeedFgMediaError() {
      this.hideShortsBufferingOverlay();
    },

    async onShortsFeedFgLoadedMetadata(e) {
      const v = e.target;
      const tokenOfEl = Number(v?.dataset?.playToken || 0);
      this.applyShortsLongPlaybackResumeIfNeeded(v);
      this.shortsFeedIsPortrait =
        v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
      const dur = readVideoDurationFromVideoEl(v);
      this.shortsFeedDuration = dur;
      let ct = Number(v.currentTime) || 0;
      if (dur > 0) {
        ct = Math.min(ct, dur);
      }
      this.shortsFeedCurrentTime = ct;
      v.volume = this.shortsFeedVolume;
      v.muted = this.shortsFeedMuted;
      this.shortsFeedVolume = v.volume;
      this.shortsFeedMuted = v.muted;
      if (this.shortsFeedAutoplayPending) {
        try {
          if (!hooks.isShortsMenuActive() && !this.profileFeedOpen) {
            return;
          }
            if (tokenOfEl !== this.shortsFeedPlayToken) {
              return;
            }
          await v.play();
          await playerRefs.bg?.value?.play().catch(() => {});
        } catch {
          /* 浏览器策略可能阻止自动播放，此时显示中央播放按钮 */
        } finally {
          this.shortsFeedAutoplayPending = false;
        }
      }
      this.shortsFeedPaused = v.paused;
    },

    onShortsFeedFgTimeUpdate(e) {
      if (!hooks.isShortsMenuActive() && !this.profileFeedOpen) {
        return;
      }
      const fg = e.target;
      const dur = readVideoDurationFromVideoEl(fg);
      if (dur > 0) {
        const prev = this.shortsFeedDuration;
        if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
          this.shortsFeedDuration = dur;
        }
      }
      let ct = Number(fg.currentTime) || 0;
      if (dur > 0) {
        ct = Math.min(ct, dur);
      }
      this.shortsFeedCurrentTime = ct;
      const cur = this.shortsFeedCurrent;
      const duration = dur > 0 ? dur : Number(fg.duration);
      const current = ct;
      if (cur?.id && Number.isFinite(duration) && duration > 0) {
        const behavior = useBehaviorStore();
        const idKey = String(cur.id);
        if (this.shortsAccVideoId !== idKey) {
          this.shortsAccVideoId = idKey;
          this.shortsPlaybackAccSec = 0;
          this.shortsLastTimeSec = null;
        }
        const prev = this.shortsLastTimeSec;
        this.shortsLastTimeSec = current;
        if (prev != null) {
          const delta = current - prev;
          if (delta > 0 && delta <= BEHAVIOR_MAX_PLAY_DELTA_SEC) {
            this.shortsPlaybackAccSec += delta;
          }
        }
        if (this.shortsPlaybackAccSec >= duration * BEHAVIOR_PLAY_THRESHOLD && !behavior.hasPlayReported(cur.id)) {
          behavior.markPlayReported(cur.id);
          void behavior.report(cur.id, "PLAY", current * 1000);
        }
        if (this.isLongShortsFeedVideo(cur) && !fg.paused) {
          longResumeTickCount += 1;
          if (longResumeTickCount >= 30) {
            longResumeTickCount = 0;
            void this.flushShortsLongPlaybackResume("tick");
          }
        }
      }
      const bg = playerRefs.bg?.value;
      if (!bg || !fg) {
        return;
      }
      if (Math.abs(bg.currentTime - fg.currentTime) > 0.2) {
        bg.currentTime = fg.currentTime;
      }
    },

    onShortsFeedFgPlay() {
      this.shortsFeedPaused = false;
      const cur = this.shortsFeedCurrent;
      useBehaviorStore().enqueueShortsHistory(cur?.id, cur);
      if (this.isLongShortsFeedVideo()) {
        longResumeTickCount = 0;
      }
      playerRefs.bg?.value?.play().catch(() => {});
    },

    onShortsFeedFgPause() {
      if (this.shortsFeedAutoplayPending) {
        return;
      }
      this.shortsFeedPaused = true;
      const fg = playerRefs.fg?.value;
      if (fg) {
        const dur = readVideoDurationFromVideoEl(fg);
        if (dur > 0) {
          this.shortsFeedDuration = dur;
        }
        let ct = Number(fg.currentTime) || 0;
        if (dur > 0) {
          ct = Math.min(ct, dur);
        }
        this.shortsFeedCurrentTime = ct;
      }
      if (this.isLongShortsFeedVideo()) {
        void this.flushShortsLongPlaybackResume("pause");
      }
      playerRefs.bg?.value?.pause();
    },

    onShortsFeedFgDurationChange(e) {
      const v = e.target;
      const dur = readVideoDurationFromVideoEl(v);
      if (dur > 0) {
        const prev = this.shortsFeedDuration;
        if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
          this.shortsFeedDuration = dur;
        }
        const ct = Number(v.currentTime) || 0;
        this.shortsFeedCurrentTime = dur > 0 ? Math.min(ct, dur) : ct;
      }
    },

    onShortsFeedFgSeeked(e) {
      this.shortsLastTimeSec = null;
      const fg = e.target;
      const bg = playerRefs.bg?.value;
      if (bg) {
        bg.currentTime = fg.currentTime;
      }
    },

    onShortsFeedFgEnded() {
      const cur = this.shortsFeedCurrent;
      const now = Number(this.shortsFeedCurrentTime || 0);
      if (cur?.id) {
        void useBehaviorStore().report(cur.id, "FINISH", now * 1000);
      }
      if (this.isLongShortsFeedVideo(cur)) {
        void this.flushShortsLongPlaybackResume("ended");
      }
      const n = this.unifiedFeedList.length;
      // 评论打开时循环当前条，避免播完突然切走影响阅读
      if (n <= 1 || useCommentPanelStore().shortsCommentPanelOpen) {
        const v = playerRefs.fg?.value;
        const bg = playerRefs.bg?.value;
        if (v) {
          v.currentTime = 0;
          v.play()
            .then(() => bg?.play().catch(() => {}))
            .catch(() => {});
        }
        return;
      }
      this.shortsFeedNext();
    },

    onShortsScrubInput(e) {
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      const dur = readVideoDurationFromVideoEl(v);
      if (!(dur > 0)) {
        return;
      }
      const t = Math.min(Math.max(0, Number(e.target.value)), dur);
      v.currentTime = t;
      this.shortsFeedCurrentTime = t;
    },

    onShortsVolumeInput(e) {
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      const vol = Number(e.target.value);
      v.volume = vol;
      v.muted = vol === 0;
      this.shortsFeedVolume = vol;
      this.shortsFeedMuted = v.muted;
    },

    shortsToggleMute() {
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      v.muted = !v.muted;
      this.shortsFeedMuted = v.muted;
    },

    shortsTogglePlay() {
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      if (v.paused) {
        v.play().catch(() => {});
      } else {
        v.pause();
      }
    },

    async shortsRequestPip() {
      const v = playerRefs.fg?.value;
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
    },

    async shortsRequestFullscreen() {
      const el = playerRefs.center?.value;
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
    },

    onShortsFeedKeydown(ev) {
      if (useProfileStore().open || !hooks.isShortsMenuActive()) {
        return;
      }
      const tag = ev.target?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable) {
        return;
      }
      const k = ev.key;
      if (k === "ArrowUp" || k === "w" || k === "W") {
        ev.preventDefault();
        this.shortsFeedPrev();
      } else if (k === "ArrowDown" || k === "s" || k === "S") {
        ev.preventDefault();
        this.shortsFeedNext();
      } else if (k === " ") {
        ev.preventDefault();
        this.shortsTogglePlay();
      }
    },

    /** 滚轮 / 触摸上下切换（与常见 App 一致：上滑下一条、下滑上一条） */
    onShortsFeedWheel(e) {
      e.preventDefault();
      if (wheelCooldown) {
        return;
      }
      const dy = e.deltaY;
      if (Math.abs(dy) < 18) {
        return;
      }
      wheelCooldown = true;
      if (dy > 0) {
        this.shortsFeedNext();
      } else {
        this.shortsFeedPrev();
      }
      window.setTimeout(() => {
        wheelCooldown = false;
      }, 420);
    },

    onShortsFeedTouchStart(ev) {
      const t = ev.touches?.[0]?.clientY;
      if (t != null) {
        touchStartY = t;
      }
    },

    onShortsFeedTouchEnd(ev) {
      const y = ev.changedTouches[0]?.clientY ?? touchStartY;
      const dy = touchStartY - y;
      if (Math.abs(dy) < 48) {
        return;
      }
      if (dy > 0) {
        this.shortsFeedNext();
      } else {
        this.shortsFeedPrev();
      }
    },

    pauseShortsFeedVideos({ muteForeground = false } = {}) {
      const fg = playerRefs.fg?.value;
      const bg = playerRefs.bg?.value;
      fg?.pause();
      bg?.pause();
      if (muteForeground && fg) {
        try {
          fg.muted = true;
        } catch {
          /* ignore */
        }
      }
    },

    /** 从详情页等遮罩返回时，把前景音量状态与 UI 同步，避免误静音 */
    restoreShortsFeedForegroundMuteFromState() {
      const fg = playerRefs.fg?.value;
      if (!fg) return;
      try {
        fg.muted = this.shortsFeedMuted;
        fg.volume = this.shortsFeedVolume;
      } catch {
        /* ignore */
      }
    },

    /** 退出全屏流时清掉续播节流状态，避免下次进入沿用上一轮的节流窗口 */
    resetShortsLongResumeState() {
      longResumeTickCount = 0;
      longResumeAppliedVideoId = "";
    },

    /** 切换作者/数据源时重置底部进度条状态（index 仍为 0 时 watch 不会触发） */
    resetShortsFeedProgressUiForSwitch() {
      this.shortsFeedIsPortrait = false;
      this.shortsFeedAutoplayPending = true;
      this.shortsFeedDuration = 0;
      this.shortsFeedCurrentTime = 0;
    },

    bumpPlayToken() {
      this.shortsFeedPlayToken += 1;
    },

    /** 前后台两个 <video> 的 DOM 由 ShortsView 持有，宿主只经注册的 ref 命令式重载 */
    reloadFeedPlayers() {
      try {
        playerRefs.fg?.value?.load();
        playerRefs.bg?.value?.load();
      } catch {
        /* 元素已卸载 */
      }
    },

    resetFeedPlayersToStart() {
      try {
        const fg = playerRefs.fg?.value;
        const bg = playerRefs.bg?.value;
        if (fg) {
          fg.pause();
          fg.currentTime = 0;
        }
        if (bg) {
          bg.pause();
          bg.currentTime = 0;
        }
      } catch {
        /* 元素已卸载 */
      }
    },

    // ===== 点赞 / 不感兴趣 / 关注 =====

    async onShortsLikeClick() {
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录后再点赞", "info");
        return;
      }
      const t = Date.now();
      if (t - lastLikeClickAt < 300) return;
      lastLikeClickAt = t;
      if (this.shortsLikeBusy) return;
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      this.shortsLikeBusy = true;
      try {
        const wasLiked = this.shortsFeedLikedDisplay;
        if (wasLiked) {
          await cancelLikeVideoApi(vid);
          hooks.patchFeedVideoById(cur.id, {
            isLike: false,
            likeCount: Math.max(0, Number(cur.likeCount ?? 0) - 1),
          });
        } else {
          await likeVideoApi(vid);
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
        void useBehaviorStore().report(cur.id, "LIKE", Number(this.shortsFeedCurrentTime || 0) * 1000);
      } catch (error) {
        pushToast(error.message || "点赞失败", "error");
      } finally {
        this.shortsLikeBusy = false;
      }
    },

    async onShortsDislikeClick() {
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录", "info");
        return;
      }
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      try {
        await dislikeVideoApi(vid);
        void useBehaviorStore().report(cur.id, "DISLIKE", Number(this.shortsFeedCurrentTime || 0) * 1000);
        pushToast("已标记为不感兴趣", "success");
      } catch (error) {
        pushToast(error.message || "操作失败", "error");
      }
    },

    onShortsForwardClick() {
      useShareStore().openForwardVideo(this.shortsFeedCurrent);
    },

    async toggleShortsFollow() {
      const c = this.shortsFeedCurrent;
      if (!c?.authorId) {
        return;
      }
      const auth = useAuthStore();
      if (!auth.isAuthed) {
        pushToast("请先登录后再关注", "info");
        return;
      }
      const aid = String(c.authorId);
      if (auth.userId != null && aid === String(auth.userId)) {
        return;
      }
      const follow = useFollowStore();
      const override = follow.overrideByAuthorId;
      const currently = Object.prototype.hasOwnProperty.call(override, aid)
        ? override[aid]
        : Boolean(c.isFollow);
      try {
        if (currently) {
          await unfollowAuthor(aid);
          follow.overrideByAuthorId = { ...override, [aid]: false };
          hooks.patchAuthorFollow(aid, false);
          pushToast("已取消关注", "success");
        } else {
          await followAuthor(aid);
          follow.overrideByAuthorId = { ...override, [aid]: true };
          hooks.patchAuthorFollow(aid, true);
          pushToast("关注成功", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },

    // ===== 自己的视频：三点菜单 =====

    toggleSelfVideoActionMenu() {
      this.selfVideoActionOpen = !this.selfVideoActionOpen;
    },

    closeSelfVideoActionMenu() {
      this.selfVideoActionOpen = false;
    },

    /** 同时把补丁打到详情页副本，避免全屏流与详情页显示不一致 */
    patchCurrentSelfVideo(partial) {
      const id = this.shortsFeedCurrent?.id;
      if (!id) return;
      hooks.patchFeedVideoById(id, partial);
      const detail = useDetailStore();
      if (detail.video?.id && String(detail.video.id) === String(id)) {
        detail.video = { ...detail.video, ...partial };
      }
    },

    async toggleCurrentSelfVideoVisibility() {
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) return;
      const goingPrivate = String(cur.visibility || "").toUpperCase() !== "PRIVATE";
      this.closeSelfVideoActionMenu();
      try {
        if (goingPrivate) {
          await setMyVideoPrivate(cur.id);
          this.patchCurrentSelfVideo({ visibility: "PRIVATE" });
          pushToast("已设为私密", "success");
        } else {
          await setMyVideoPublic(cur.id);
          this.patchCurrentSelfVideo({ visibility: "PUBLIC" });
          pushToast("已设为公开", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },

    async toggleCurrentSelfVideoAllowComment() {
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) return;
      const enable = Number(cur.allowComment ?? 1) !== 1;
      this.closeSelfVideoActionMenu();
      try {
        if (enable) {
          await openMyVideoComment(cur.id);
          this.patchCurrentSelfVideo({ allowComment: 1 });
          pushToast("已开启评论", "success");
        } else {
          await closeMyVideoComment(cur.id);
          this.patchCurrentSelfVideo({ allowComment: 0 });
          pushToast("已关闭评论", "success");
        }
      } catch (e) {
        pushToast(e?.message || "操作失败", "error");
      }
    },

    // ===== 删除当前自视频 / 退出全屏流 =====

    requestDeleteCurrentSelfVideo() {
      if (!this.shortsIsSelfCurrent) {
        pushToast("只能删除自己的视频", "info");
        return;
      }
      const cur = this.shortsFeedCurrent;
      if (!cur?.id) return;
      this.closeSelfVideoActionMenu();
      this.pendingDeleteVideoId = String(cur.id);
      this.selfVideoDeleteConfirmOpen = true;
    },

    cancelDeleteCurrentSelfVideo() {
      this.selfVideoDeleteConfirmOpen = false;
      this.pendingDeleteVideoId = null;
    },

    async confirmDeleteCurrentSelfVideo() {
      const rawId = this.pendingDeleteVideoId;
      if (!rawId) {
        this.selfVideoDeleteConfirmOpen = false;
        return;
      }
      try {
        await deleteMyVideo(rawId);
      } catch (e) {
        pushToast(e?.message || "删除失败", "error");
        return;
      }
      this.selfVideoDeleteConfirmOpen = false;
      this.pendingDeleteVideoId = null;
      hooks.removeVideoById(String(rawId));

      // 当前队列被删空时没有可播的了，直接退出全屏流
      const scopeEmpty =
        (this.feedScope === "profile" && !this.profileFeedQueue.length) ||
        (this.feedScope === "liked" && !this.likedFeedQueue.length);
      if (scopeEmpty) {
        this.closeProfileFeed();
        pushToast("已删除该视频", "success");
        return;
      }
      const list = this.unifiedFeedList;
      if (this.shortsFeedIndex >= list.length) {
        this.shortsFeedIndex = Math.max(0, list.length - 1);
      }
      nextTick(() => {
        this.reloadFeedPlayers();
      });
      pushToast("已删除该视频", "success");
    },

    /** 退出全屏流：停播并清空队列，复位面板与续播节流状态 */
    closeProfileFeed() {
      void this.flushShortsLongPlaybackResume("leave");
      this.pauseShortsFeedVideos({ muteForeground: true });
      try {
        const fg = playerRefs.fg?.value;
        const bg = playerRefs.bg?.value;
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
      this.selfVideoActionOpen = false;
      this.selfVideoDeleteConfirmOpen = false;
      this.pendingDeleteVideoId = null;
      useCommentPanelStore().shortsCommentPanelOpen = false;
      this.profileFeedOpen = false;
      this.feedScope = "shorts";
      this.profileFeedQueue = [];
      this.likedFeedQueue = [];
      this.followFeedQueue = [];
      this.resetShortsLongResumeState();
    },

    // ===== 关注页：全部动态 / 按作者筛选 =====

    /** 列表行 → 可播放行：补齐播放地址与 videoType，剔除取不到地址的 */
    mapToFollowPlayableRows(list) {
      const sources = [
        useProfileStore().worksRows,
        useHomeStore().cards,
        useLikedStore().likedRows,
      ];
      return (Array.isArray(list) ? list : [])
        .map((x) => {
          const url =
            resolveVideoUrl(x) ||
            resolveVideoUrlFromLists(x.id, x, sources) ||
            String(x?.videoUrl || "").trim();
          return { ...x, videoUrl: url || x.videoUrl, videoType: x.videoType || "SHORT_VIDEO" };
        })
        .filter((x) => Boolean(String(resolveVideoUrl(x) || x.videoUrl || "").trim()));
    },

    async loadSubscribedFollowVideos({ reset = false } = {}) {
      if (!useAuthStore().isAuthed) return;
      const seq = ++followFeedLoadSeq;
      if (this.followVideoLoadingMore && !reset) return;
      if (!reset && !this.followVideoHasMore) return;
      this.followVideoLoadingMore = true;
      try {
        const pageNo = reset ? 1 : this.followVideoPageNo;
        const res = await fetchFollowVideoFeedPage({ pageNo, pageSize: FOLLOW_FEED_PAGE_SIZE });
        if (seq !== followFeedLoadSeq) return;
        if (this.followSubscribedFilterAuthorId != null && this.followSubscribedFilterAuthorId !== "") {
          return;
        }
        const list = Array.isArray(res.list) ? res.list : [];
        const playable = this.mapToFollowPlayableRows(list);
        const pages = Number(res.pages || 0);
        if (reset) {
          this.followFeedQueue = playable;
          useHomeStore().cards = list;
        } else {
          const existing = new Set(this.followFeedQueue.map((x) => String(x.id)));
          let merged = this.followFeedQueue;
          for (const p of playable) {
            if (!existing.has(String(p.id))) {
              merged = [...merged, p];
              existing.add(String(p.id));
            }
          }
          this.followFeedQueue = merged;
        }
        this.followVideoPageNo = pageNo + 1;
        this.followVideoHasMore =
          list.length >= FOLLOW_FEED_PAGE_SIZE && (pages <= 0 || pageNo < pages);
        if (list.length && !playable.length) {
          pushToast("接口返回了视频但未解析到播放地址，请检查对象存储签名或 videoUrl 字段", "info");
        }
      } catch (e) {
        if (seq !== followFeedLoadSeq) return;
        if (this.followSubscribedFilterAuthorId != null && this.followSubscribedFilterAuthorId !== "") {
          return;
        }
        if (reset) {
          this.followFeedQueue = [];
          useHomeStore().cards = [];
        }
        this.followVideoHasMore = false;
        pushToast(e?.message || "加载关注视频失败", "error");
      } finally {
        if (seq === followFeedLoadSeq) {
          this.followVideoLoadingMore = false;
        }
      }
    },

    async loadSubscribedAuthorVideos(authorId, { reset = false } = {}) {
      const aid = authorId != null && authorId !== "" ? String(authorId) : "";
      if (!aid || !useAuthStore().isAuthed) return;
      const seq = ++followAuthorLoadSeq;
      if (this.followAuthorLoadingMore && !reset) return;
      if (!reset && !this.followAuthorVideoHasMore) return;
      this.followAuthorLoadingMore = true;
      try {
        const pageNo = reset ? 1 : this.followAuthorVideoPageNo;
        const res = await fetchOtherUserVideos(aid, { pageNo, pageSize: FOLLOW_FEED_PAGE_SIZE });
        if (seq !== followAuthorLoadSeq) return;
        if (String(this.followSubscribedFilterAuthorId || "") !== aid) return;
        const list = Array.isArray(res.list) ? res.list : [];
        const playable = this.mapToFollowPlayableRows(list);
        const pages = Number(res.pages || 0);
        if (reset) {
          this.followAuthorFeedQueue = playable;
        } else {
          const existing = new Set(this.followAuthorFeedQueue.map((x) => String(x.id)));
          let merged = this.followAuthorFeedQueue;
          for (const p of playable) {
            if (!existing.has(String(p.id))) {
              merged = [...merged, p];
              existing.add(String(p.id));
            }
          }
          this.followAuthorFeedQueue = merged;
        }
        this.followAuthorVideoPageNo = pageNo + 1;
        this.followAuthorVideoHasMore =
          list.length >= FOLLOW_FEED_PAGE_SIZE && (pages <= 0 || pageNo < pages);
        if (list.length && !playable.length) {
          pushToast("该作者的作品暂无可用播放地址", "info");
        }
      } catch (e) {
        if (seq !== followAuthorLoadSeq) return;
        if (String(this.followSubscribedFilterAuthorId || "") !== aid) return;
        if (reset) {
          this.followAuthorFeedQueue = [];
        }
        this.followAuthorVideoHasMore = false;
        pushToast(e?.message || "加载作者视频失败", "error");
      } finally {
        if (seq === followAuthorLoadSeq) {
          this.followAuthorLoadingMore = false;
        }
      }
    },

    /** 侧栏选「全部动态」：清掉作者筛选并回到全部关注流 */
    async selectFollowSidebarAll() {
      const hadFilter =
        this.followSubscribedFilterAuthorId != null && this.followSubscribedFilterAuthorId !== "";
      followAuthorLoadSeq += 1;
      this.resetShortsFeedProgressUiForSwitch();
      this.followSubscribedFilterAuthorId = null;
      this.followAuthorFeedQueue = [];
      this.followAuthorVideoPageNo = 1;
      this.followAuthorVideoHasMore = false;
      this.shortsFeedIndex = 0;
      this.bumpPlayToken();
      this.shortsFeedAutoplayPending = true;
      if (hadFilter || this.followFeedQueue.length === 0) {
        await this.loadSubscribedFollowVideos({ reset: true });
      }
      nextTick(() => {
        if (this.followSubscribedFilterAuthorId != null && this.followSubscribedFilterAuthorId !== "") {
          return;
        }
        this.reloadFeedPlayers();
      });
    },

    /** 侧栏选某位作者：右侧只播该作者短视频 */
    async selectFollowSidebarAuthor(user) {
      const id = user?.id ?? user?.followeeId;
      if (id == null || id === "") return;
      if (String(this.followSubscribedFilterAuthorId) === String(id)) {
        return;
      }
      followFeedLoadSeq += 1;
      this.resetShortsFeedProgressUiForSwitch();
      this.followSubscribedFilterAuthorId = id;
      this.followAuthorFeedQueue = [];
      this.followAuthorVideoPageNo = 1;
      this.followAuthorVideoHasMore = true;
      this.shortsFeedIndex = 0;
      this.bumpPlayToken();
      this.shortsFeedAutoplayPending = true;
      await this.loadSubscribedAuthorVideos(id, { reset: true });
      nextTick(() => {
        if (String(this.followSubscribedFilterAuthorId || "") !== String(id)) return;
        this.reloadFeedPlayers();
      });
    },

    maybePrefetchFollowFeed() {
      if (this.feedScope !== "follow" || !hooks.isSubscribedMenuActive()) {
        return;
      }
      const aid = this.followSubscribedFilterAuthorId;
      const list = aid != null && aid !== "" ? this.followAuthorFeedQueue : this.followFeedQueue;
      if (!list.length) {
        return;
      }
      if (list.length - 1 - this.shortsFeedIndex >= FOLLOW_FEED_PREFETCH_THRESHOLD) {
        return;
      }
      if (aid != null && aid !== "") {
        void this.loadSubscribedAuthorVideos(aid, { reset: false });
      } else {
        void this.loadSubscribedFollowVideos({ reset: false });
      }
    },

    // ===== 长视频续播 =====

    isLongShortsFeedVideo(item = this.shortsFeedCurrent) {
      const vt = item?.videoType;
      if (!vt) return false;
      const s = String(vt).toLowerCase().trim();
      return s === "long" || s === "long_video" || s === "longvideo" || s.includes("long");
    },

    getShortsLongCurrentPlayTimeMs() {
      const player = playerRefs.fg?.value;
      if (!player) return 0;
      const ms = Math.floor((Number(player.currentTime || 0) || 0) * 1000);
      return ms > 0 ? ms : 0;
    },

    getShortsLongPlaybackResumePayload(playTimeMs) {
      const userId = useAuthStore().userId ?? useProfileStore().user?.id;
      const videoId = this.shortsFeedCurrent?.id;
      if (!userId || userId === "" || !videoId) return null;
      if (!Number.isFinite(Number(playTimeMs))) return null;
      return { userId, videoId, playTime: playTimeMs };
    },

    applyShortsLongPlaybackResumeIfNeeded(player) {
      const cur = this.shortsFeedCurrent;
      if (!this.isLongShortsFeedVideo(cur)) return;
      if (!cur || !player) return;
      const vid = String(cur.id ?? "");
      if (!vid || longResumeAppliedVideoId === vid) return;
      const playbackMs = Number(cur.playbackTime ?? 0);
      if (!Number.isFinite(playbackMs) || playbackMs <= 0) {
        longResumeAppliedVideoId = vid;
        return;
      }
      const targetSec = playbackMs / 1000;
      const duration = Number(player.duration);
      if (!Number.isFinite(targetSec) || targetSec <= 0 || !Number.isFinite(duration) || duration <= 0) {
        return;
      }
      player.currentTime = Math.max(0, Math.min(targetSec, Math.max(0, duration - 1)));
      longResumeAppliedVideoId = vid;
    },

    flushShortsLongPlaybackResume(reason) {
      const cur = this.shortsFeedCurrent;
      const player = playerRefs.fg?.value;
      if (!this.isLongShortsFeedVideo(cur) || !player) return Promise.resolve();
      if (reason !== "pause" && player.paused) return Promise.resolve();
      const playTimeMs = this.getShortsLongCurrentPlayTimeMs();
      if (reason !== "pause" && reason !== "switch" && reason !== "leave" && reason !== "unload" && playTimeMs <= 0) {
        return Promise.resolve();
      }
      const payload = this.getShortsLongPlaybackResumePayload(playTimeMs);
      if (!payload) return Promise.resolve();
      const now = Date.now();
      if (reason === "tick" && now - longResumeLastSentAtMs < 8_000) {
        return Promise.resolve();
      }
      if (longResumeInFlight) return Promise.resolve();
      longResumeInFlight = true;
      longResumeLastSentAtMs = now;
      const keepalive = reason === "unload" || reason === "leave";
      return sendPlaybackResumePut(payload, { keepalive }).finally(() => {
        longResumeInFlight = false;
      });
    },

    // ===== 评论侧栏 =====

    resetShortsPanelCommentsState() {
      this.shortsPanelTopComments = [];
      this.shortsPanelCommentsLoading = false;
      this.shortsPanelCommentsPageNo = 1;
      this.shortsPanelCommentsPages = 0;
      this.shortsPanelCommentDraft = "";
      this.shortsPanelCommentSubmitting = false;
      this.shortsPanelReplyTarget = null;
      this.shortsPanelThreadByRootId = {};
      this.shortsPanelExpandedRoots = {};
    },

    toggleShortsCommentPanel() {
      if (!this.shortsFeedCurrent?.id) {
        return;
      }
      const panel = useCommentPanelStore();
      panel.searchExpandCommentOpen = false;
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
      panel.shortsCommentPanelOpen = !panel.shortsCommentPanelOpen;
    },

    closeShortsCommentPanel() {
      const panel = useCommentPanelStore();
      panel.shortsCommentPanelOpen = false;
      panel.searchExpandCommentOpen = false;
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
    },

    async loadShortsPanelTopComments(reset, opts = {}) {
      const preserveThreadUi = opts.preserveThreadUi === true;
      const savedExpanded = preserveThreadUi && reset ? { ...this.shortsPanelExpandedRoots } : null;

      const vid = parseDigitSnowflakeId(this.activeCommentVideo?.id);
      if (!vid) {
        return;
      }
      if (reset) {
        this.shortsPanelCommentsPageNo = 1;
        this.shortsPanelTopComments = [];
        if (!preserveThreadUi) {
          this.shortsPanelThreadByRootId = {};
          this.shortsPanelExpandedRoots = {};
        }
      }
      this.shortsPanelCommentsLoading = true;
      try {
        const { pages, list } = await fetchCommentPage({
          videoId: vid,
          parentId: 0,
          pageNo: this.shortsPanelCommentsPageNo,
          pageSize: SHORTS_COMMENT_PAGE_SIZE,
        });
        this.shortsPanelCommentsPages = pages || 0;
        if (reset) {
          this.shortsPanelTopComments = list;
        } else {
          this.shortsPanelTopComments = [...this.shortsPanelTopComments, ...list];
        }

        if (savedExpanded) {
          const topIds = new Set(this.shortsPanelTopComments.map((x) => String(x.id)));
          const nextExpanded = {};
          for (const k of Object.keys(savedExpanded)) {
            if (savedExpanded[k] && topIds.has(k)) {
              nextExpanded[k] = true;
            }
          }
          this.shortsPanelExpandedRoots = nextExpanded;
          const nextThreads = { ...this.shortsPanelThreadByRootId };
          for (const k of Object.keys(nextExpanded)) {
            delete nextThreads[k];
          }
          this.shortsPanelThreadByRootId = nextThreads;
          for (const k of Object.keys(nextExpanded)) {
            void this.ensureShortsPanelThreadLoaded(k);
          }
        }
      } catch (e) {
        pushToast(e?.message || "评论加载失败", "error");
      } finally {
        this.shortsPanelCommentsLoading = false;
      }
    },

    shortsPanelCommentHasMore() {
      return this.shortsPanelCommentsPageNo < this.shortsPanelCommentsPages;
    },

    async loadMoreShortsPanelComments() {
      if (this.shortsPanelCommentsLoading || !this.shortsPanelCommentHasMore()) {
        return;
      }
      this.shortsPanelCommentsPageNo += 1;
      await this.loadShortsPanelTopComments(false);
    },

    async ensureShortsPanelThreadLoaded(rootId) {
      const vid = parseDigitSnowflakeId(this.activeCommentVideo?.id);
      const k = String(rootId);
      if (!vid || this.shortsPanelThreadByRootId[k]?.loaded) {
        return;
      }
      this.shortsPanelThreadByRootId = {
        ...this.shortsPanelThreadByRootId,
        [k]: { ...(this.shortsPanelThreadByRootId[k] || {}), loading: true, list: [] },
      };
      try {
        const { list } = await fetchCommentPage({
          videoId: vid,
          parentId: rootId,
          rootId,
          pageNo: 1,
          pageSize: 100,
        });
        this.shortsPanelThreadByRootId = {
          ...this.shortsPanelThreadByRootId,
          [k]: { loaded: true, loading: false, list },
        };
      } catch (e) {
        this.shortsPanelThreadByRootId = {
          ...this.shortsPanelThreadByRootId,
          [k]: { loaded: false, loading: false, list: [] },
        };
        pushToast(e?.message || "回复加载失败", "error");
      }
    },

    toggleShortsPanelThread(rootId) {
      const k = String(rootId);
      if (this.shortsPanelExpandedRoots[k]) {
        const next = { ...this.shortsPanelExpandedRoots };
        delete next[k];
        this.shortsPanelExpandedRoots = next;
      } else {
        this.shortsPanelExpandedRoots = { ...this.shortsPanelExpandedRoots, [k]: true };
        void this.ensureShortsPanelThreadLoaded(rootId);
      }
    },

    setShortsPanelReplyTarget(row) {
      if (!row) {
        this.shortsPanelReplyTarget = null;
        return;
      }
      this.shortsPanelReplyTarget = {
        id: row.id,
        nickname: row.nickname || "用户",
      };
    },

    async submitShortsPanelComment() {
      const text = this.shortsPanelCommentDraft.trim();
      if (!text) {
        return;
      }
      const cur = this.activeCommentVideo;
      const vid = parseDigitSnowflakeId(cur?.id);
      if (!vid) {
        return;
      }
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录后再评论", "info");
        return;
      }
      if (!this.activeCommentVideoAllowsComment) {
        pushToast("作者已关闭评论", "info");
        return;
      }
      const wasTopLevel = this.shortsPanelReplyTarget == null;
      const parentId = wasTopLevel ? 0 : this.shortsPanelReplyTarget.id;
      this.shortsPanelCommentSubmitting = true;
      try {
        await publishCommentApi({ videoId: vid, content: text, parentId });
        this.shortsPanelCommentDraft = "";
        this.shortsPanelReplyTarget = null;
        if (cur?.id && wasTopLevel) {
          hooks.patchFeedVideoById(cur.id, {
            commentCount: Number(cur.commentCount ?? 0) + 1,
          });
        }
        await this.loadShortsPanelTopComments(true, { preserveThreadUi: true });
      } catch (e) {
        pushToast(e?.message || "发送失败", "error");
      } finally {
        this.shortsPanelCommentSubmitting = false;
      }
    },

    async onDeleteShortsPanelComment(c) {
      const auth = useAuthStore();
      if (!c?.id || !auth.isAuthed) {
        return;
      }
      if (String(c.userId) !== String(auth.userId)) {
        return;
      }
      const cur = this.activeCommentVideo;
      try {
        await deleteCommentApi(c.id);
        pushToast("已删除", "success");
        await this.loadShortsPanelTopComments(true, { preserveThreadUi: true });
        const pid = c.parentId;
        if (cur?.id && (pid === 0 || pid === "0")) {
          const n = Math.max(0, Number(cur.commentCount ?? 0) - 1);
          hooks.patchFeedVideoById(cur.id, { commentCount: n });
        }
      } catch (e) {
        pushToast(e?.message || "删除失败", "error");
      }
    },

    commentDisplayNickname(userId, nickname) {
      return commentDisplayNicknameText(userId, nickname, useAuthStore().userId);
    },

    isCommentLikeBusy(id) {
      return useCommentStore().isLikeBusy(id);
    },

    /** 点赞复用评论域公共层，只把补丁打回本域的两份列表（不反向改别人的数组） */
    async submitCommentLike(row) {
      const patch = await useCommentStore().toggleLike(row);
      if (!patch) return;
      const sid = String(row.id);
      const merge = (r) => (String(r.id) === sid ? { ...r, ...patch } : r);
      this.shortsPanelTopComments = this.shortsPanelTopComments.map(merge);
      const threads = { ...this.shortsPanelThreadByRootId };
      for (const k of Object.keys(threads)) {
        const t = threads[k];
        if (t?.list?.length) {
          threads[k] = { ...t, list: t.list.map(merge) };
        }
      }
      this.shortsPanelThreadByRootId = threads;
    },
  },
});
