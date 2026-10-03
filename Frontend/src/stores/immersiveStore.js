// @ts-check
/**
 * 短视频播放器域状态：沉浸 feed 播放态、播放控制与进度上报、评论侧栏，
 * 以及被历史/赞过/个人主页/关注复用的全屏队列（feedScope 区分数据源）。
 * 跨域能力（跨列表补丁、菜单归属）经 registerImmersiveHooks 注入；
 * 播放器 DOM ref 由宿主组件 ImmersiveView 经 registerImmersiveFeedPlayerRefs 注册。
 */
import { defineStore } from "pinia";
import { nextTick, toRaw } from "vue";
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
import { toggleVideoLikeAndSync } from "../utils/videoLike.js";
import {
  fetchCommentPage,
  deleteComment as deleteCommentApi,
  publishComment as publishCommentApi,
} from "../api/commentApi.js";
import { dislikeVideo as dislikeVideoApi } from "../api/likeApi.js";
import {
  fetchFollowVideoFeedPage,
  fetchOtherUserVideos,
  deleteMyVideo,
  setMyVideoPrivate,
  setMyVideoPublic,
  openMyVideoComment,
  closeMyVideoComment,
} from "../api/videoApi.js";
import { deleteMyDynamic } from "../api/dynamicApi.js";
import { followAuthor, unfollowAuthor } from "../api/followApi.js";
import { toast as pushToast } from "../utils/toast.js";
import { downloadRemoteFile, videoDownloadName } from "../utils/downloadFile.js";
import { useAuthStore } from "./authStore.js";
import { useBehaviorStore } from "./behaviorStore.js";
import { useCommentPanelStore } from "./commentPanelStore.js";
import { useCommentStore } from "./commentStore.js";
import { useDetailStore } from "./detailStore.js";
import { useDynamicStore } from "./dynamicStore.js";
import { useFollowStore } from "./followStore.js";
import { fetchDynamicDetail } from "../api/dynamicApi";
import { useHomeStore } from "./homeStore.js";
import { useLikedStore } from "./likedStore.js";
import { useProfileStore } from "./profileStore.js";
import { useSearchStore } from "./searchStore.js";
import { useShareStore } from "./shareStore.js";

const IMMERSIVE_COMMENT_PAGE_SIZE = 10;
/** 切换很快时不显示加载层，先等这么久再亮出遮罩 */
const IMMERSIVE_BUFFERING_UI_DELAY_MS = 320;
/** PLAY 上报阈值：累计顺播达总时长比例且排除拖动（单次 timeupdate 跳跃过大不计入） */
const BEHAVIOR_PLAY_THRESHOLD = 0.7;
const BEHAVIOR_MAX_PLAY_DELTA_SEC = 3;
const FOLLOW_FEED_PAGE_SIZE = 12;
/** 距队列末尾还剩这么少条时预取下一页 */
const FOLLOW_FEED_PREFETCH_THRESHOLD = 3;

/**
 * 宿主注册的播放器句柄（App 内单例，模块级持有不入 store）。
 * dynamic 是受控 DynamicPlayer 的组件实例（含 seek/togglePlay），
 * 与 fg/bg 两个 <video> 互为「当前条」的两种实现 —— 命令入口按内容类型二选一。
 */
const playerRefs = { fg: null, bg: null, center: null, dynamic: null };

/** 宿主注册全屏播放器 ref（app 内单例，模块级持有不入 store） */
export function registerImmersiveFeedPlayerRefs({ fg, bg, center, dynamic }) {
  if (fg) playerRefs.fg = fg;
  if (bg) playerRefs.bg = bg;
  if (center) playerRefs.center = center;
  if (dynamic) playerRefs.dynamic = dynamic;
}

/**
 * 事件是否来自「已被换下」的前景 <video>。
 * 切条时前景元素按 id 重建，浏览器会给旧元素补发 pause / loadedmetadata 等迟到事件；
 * 这些事件写回的是全局播放态，会把正在播的新视频误判成暂停（中央播放键常驻不消）。
 * 判据只用元素身份 —— 一个函数覆盖全部 fg 事件。
 */
function isStaleFgEvent(e) {
  const el = e?.target;
  if (!el) return false;
  // ref 持有对象时会被包成 proxy，比身份前统一取原始值
  return toRaw(el) !== toRaw(playerRefs.fg?.value);
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
 *   isImmersiveMenuActive: () => boolean,
 *   isSubscribedMenuActive: () => boolean,
 *   removeVideoById: (id: string) => void,
 *   notify: (msg: string) => void,
 *   resolveUrlFallback: (id: any, item: any) => string,
 * }}
 */
const hooks = {
  /** 跨多份列表同步点赞数/评论数 */
  patchFeedVideoById: () => {},
  /** 跨列表同步同一作者的 isFollow */
  patchAuthorFollow: () => {},
  /** 当前是否停留在短视频 Tab（immersive / subscribed）——菜单状态机仍在 App */
  isImmersiveMenuActive: () => false,
  /** 当前是否停留在「关注」Tab——关注态判定要区分全部动态与按作者筛选 */
  isSubscribedMenuActive: () => false,
  /** 删除视频后从各列表移除（feedPatchStore 负责广播） */
  removeVideoById: () => {},
  /** 页面提示文案：pageHint 在 navigationStore，而它反向依赖本 store，只能经宿主回写 */
  notify: () => {},
  /** 跨列表兜底取播放地址：赞过/历史等列表的行可能缺 url，宿主还看得见作品列表与首页卡片 */
  resolveUrlFallback: () => "",
};

export function registerImmersiveHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

export const useImmersiveStore = defineStore("immersive", {
  state: () => ({
    /** 全屏流数据源：immersive(沉浸菜单/首页横条) / follow / profile / liked */
    feedScope: "immersive",
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

    immersiveFeedIndex: 0,
    /** 连播动态条目的详情缓存: id(String) -> DynamicVO(含时间轴) */
    dynamicDetailCache: {},
    /**
     * 播放代次：切换视频时自增。渲染到 `:data-play-token`，loadedmetadata 时比对，
     * 用来丢弃「旧元素迟到的元数据」触发的自动播放。
     */
    immersiveFeedPlayToken: 0,
    immersiveFeedIsPortrait: false,
    immersiveFeedDuration: 0,
    immersiveFeedCurrentTime: 0,
    immersiveFeedVolume: 0.85,
    immersiveFeedMuted: false,
    /** 暂停时在画面中央显示三角形播放图标 */
    immersiveFeedPaused: true,
    /** 切换视频后自动播放（含进入短视频页） */
    immersiveFeedAutoplayPending: false,
    /** 短视频流：切换很快时不显示加载层 */
    immersiveFeedVideoBuffering: false,

    /** 短视频流右侧评论侧栏（与详情共用 /comment API） */
    immersivePanelTopComments: [],
    immersivePanelCommentsLoading: false,
    immersivePanelCommentsPageNo: 1,
    immersivePanelCommentsPages: 0,
    immersivePanelCommentDraft: "",
    immersivePanelCommentSubmitting: false,
    immersivePanelReplyTarget: null,
    immersivePanelThreadByRootId: {},
    immersivePanelExpandedRoots: {},

    /** 短视频流点赞：切换条时重置 */
    immersiveLikeBusy: false,
    /** 自己的视频在全屏短视频里显示更多操作（三点） */
    selfVideoActionOpen: false,
    /** 删除确认弹窗：短视频流与创作中心表格共用同一份确认状态 */
    selfVideoDeleteConfirmOpen: false,
    /** 删除确认弹窗对应的视频 id（创作中心 / 短视频流共用） */
    pendingDeleteVideoId: null,
    /** 待删除内容类型: video/dynamic(创作中心混排两域) */
    pendingDeleteRowType: null,
    /** 顺播累计秒数 + 当前条 id */
    immersivePlaybackAccSec: 0,
    immersiveLastTimeSec: null,
    immersiveAccVideoId: "",
  }),

  getters: {
    /**
     * 全屏流当前队列：feedScope 决定数据源。
     * immersive 是首页沉浸横条与「沉浸」菜单共用的那一份（homeStore.immersiveRows），
     * 其余 scope 各持自己的队列（赞过 / 个人主页 / 关注）。
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
      return useHomeStore().immersiveRows;
    },
    /** @returns {any} */
    immersiveFeedCurrent() {
      const list = this.unifiedFeedList;
      if (!list.length) return null;
      const i = Math.min(Math.max(0, this.immersiveFeedIndex), list.length - 1);
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
      return this.immersiveFeedCurrent;
    },
    /** @returns {boolean} */
    activeCommentVideoAllowsComment() {
      return Number(this.activeCommentVideo?.allowComment ?? 1) === 1;
    },

    // ===== 当前条的展示值（模板直接消费，避免在视图里重复推导） =====

    /** @returns {string} */
    immersiveFeedVideoSrc() {
      return resolveVideoUrl(this.immersiveFeedCurrent);
    },
    /**
     * 当前条是动态(统一内容池)时渲染 DynamicPlayer 而非 video 元素。
     * 本 getter 用了 `this.`，必须显式标注返回类型，否则 vue-tsc 走 `this` → `G` 成环。
     * @returns {boolean}
     */
    immersiveFeedIsDynamic() {
      return String(this.immersiveFeedCurrent?.type ?? "") === "dynamic";
    },
    /**
     * 普通流看接口 isLike；赞过列表后端可能不传该字段，默认视为已赞，
     * 仅当本地明确 patch 为 isLike === false（取消赞）时显示空心。
     * @returns {boolean}
     */
    immersiveFeedLikedDisplay() {
      const cur = this.immersiveFeedCurrent;
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
    immersiveScrubMax() {
      const d = Number(this.immersiveFeedDuration);
      if (Number.isFinite(d) && d > 0) {
        return d;
      }
      return 1;
    },
    /** @returns {number} */
    immersiveScrubDisplayValue() {
      const t = Number(this.immersiveFeedCurrentTime);
      const d = Number(this.immersiveFeedDuration);
      if (!Number.isFinite(t) || t < 0) {
        return 0;
      }
      if (Number.isFinite(d) && d > 0) {
        return Math.min(t, d);
      }
      return 0;
    },
    /** @returns {boolean} */
    immersiveDurationReady() {
      const d = Number(this.immersiveFeedDuration);
      return Number.isFinite(d) && d > 0;
    },
    /** @returns {string} */
    immersiveFeedAuthorName() {
      const c = this.immersiveFeedCurrent;
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
    immersiveFeedMetaTimeText() {
      return formatRelativeTimeText(this.immersiveFeedCurrent?.createTime || "");
    },
    /** @returns {string} */
    immersiveFeedTitleText() {
      return this.immersiveFeedCurrent?.title || "未命名主题";
    },
    /** @returns {string} */
    immersiveFeedDescText() {
      return this.immersiveFeedCurrent?.description || this.immersiveFeedCurrent?.title || "";
    },
    /**
     * 关注 Tab：与左侧关注列表同一套 avatarDisplaySrc，避免视频 VO 里作者头像与侧栏不一致/糊片
     * @returns {string}
     */
    immersiveFeedAuthorAvatar() {
      const c = this.immersiveFeedCurrent;
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
    immersiveIsFollowedCurrent() {
      const c = this.immersiveFeedCurrent;
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
    immersiveIsSelfCurrent() {
      const authorId = this.immersiveFeedCurrent?.authorId;
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
     * 播放器 DOM ref 由宿主经 registerImmersiveFeedPlayerRefs 注入。
     */
    openProfileFeed(sourceList, index) {
      const source = Array.isArray(sourceList) ? sourceList : [];
      const playableQueue = source
        .map((x) => ({
          ...x,
          videoUrl: resolveVideoUrl(x),
        }))
        .filter((x) => x.videoUrl);
      if (!playableQueue.length) return;
      this.feedScope = "profile";
      this.profileFeedQueue = playableQueue;
      this.immersiveFeedIndex = Math.min(Math.max(0, Number(index) || 0), playableQueue.length - 1);
      this.immersiveFeedAutoplayPending = true;
      this.profileFeedOpen = true;
      nextTick(() => {
        this.reloadFeedPlayers();
      });
    },

    /** 「赞过的视频」连播：队列与开关都在本 store，跨列表取址与提示文案由宿主提供 */
    openLikedFeed(item) {
      const source = useLikedStore().likedRows;
      if (!source.length) {
        hooks.notify("暂无赞过的视频");
        return;
      }
      const selectedUrl = resolveVideoUrl(item) || hooks.resolveUrlFallback(item?.id, item);
      if (!selectedUrl) {
        hooks.notify("该内容暂无视频地址");
        return;
      }
      const playableQueue = source
        .map((x) => ({ ...x, videoUrl: resolveVideoUrl(x) || hooks.resolveUrlFallback(x.id, x) }))
        .filter((x) => x.videoUrl);
      if (!playableQueue.find((x) => String(x.id) === String(item.id))) {
        playableQueue.unshift({ ...item, videoUrl: selectedUrl });
      }
      if (!playableQueue.length && selectedUrl) {
        playableQueue.push({ ...item, videoUrl: selectedUrl });
      }
      const idx = playableQueue.findIndex((x) => String(x.id) === String(item.id));
      this.feedScope = "liked";
      this.likedFeedQueue = playableQueue;
      this.immersiveFeedIndex = idx >= 0 ? idx : 0;
      this.immersiveFeedAutoplayPending = true;
      this.profileFeedOpen = true;
      nextTick(() => {
        this.reloadFeedPlayers();
      });
    },

    // ===== 播放缓冲遮罩 =====

    clearImmersiveBufferingUITimer() {
      if (bufferingUITimer != null) {
        clearTimeout(bufferingUITimer);
        bufferingUITimer = null;
      }
    },

    scheduleImmersiveBufferingOverlay() {
      this.clearImmersiveBufferingUITimer();
      this.immersiveFeedVideoBuffering = false;
      bufferingUITimer = window.setTimeout(() => {
        bufferingUITimer = null;
        this.immersiveFeedVideoBuffering = true;
      }, IMMERSIVE_BUFFERING_UI_DELAY_MS);
    },

    hideImmersiveBufferingOverlay() {
      this.clearImmersiveBufferingUITimer();
      this.immersiveFeedVideoBuffering = false;
    },

    // ===== 切换与播放控制 =====

    immersiveFeedPrev() {
      const cur = this.immersiveFeedCurrent;
      const dur = Number(this.immersiveFeedDuration || 0);
      const now = Number(this.immersiveFeedCurrentTime || 0);
      if (this.isLongImmersiveFeedVideo(cur)) {
        void this.flushImmersiveLongPlaybackResume("switch");
      }
      if (cur?.id && dur > 0 && now / dur < 0.95) {
        void useBehaviorStore().report(cur.id, "SKIP", now * 1000);
      }
      const n = this.unifiedFeedList.length;
      if (!n) {
        return;
      }
      this.immersiveFeedIndex = (this.immersiveFeedIndex - 1 + n) % n;
    },

    immersiveFeedNext() {
      const cur = this.immersiveFeedCurrent;
      const dur = Number(this.immersiveFeedDuration || 0);
      const now = Number(this.immersiveFeedCurrentTime || 0);
      if (this.isLongImmersiveFeedVideo(cur)) {
        void this.flushImmersiveLongPlaybackResume("switch");
      }
      if (cur?.id && dur > 0 && now / dur < 0.95) {
        void useBehaviorStore().report(cur.id, "SKIP", now * 1000);
      }
      const n = this.unifiedFeedList.length;
      if (!n) {
        return;
      }
      this.immersiveFeedIndex = (this.immersiveFeedIndex + 1) % n;
    },

    onImmersiveFeedFgLoadStart() {
      this.scheduleImmersiveBufferingOverlay();
    },

    onImmersiveFeedFgCanPlay() {
      this.hideImmersiveBufferingOverlay();
    },

    onImmersiveFeedFgMediaError() {
      this.hideImmersiveBufferingOverlay();
    },

    async onImmersiveFeedFgLoadedMetadata(e) {
      if (isStaleFgEvent(e)) return;
      const v = e.target;
      const tokenOfEl = Number(v?.dataset?.playToken || 0);
      this.applyImmersiveLongPlaybackResumeIfNeeded(v);
      this.immersiveFeedIsPortrait =
        v.videoWidth > 0 && v.videoHeight > 0 && v.videoHeight > v.videoWidth;
      const dur = readVideoDurationFromVideoEl(v);
      this.immersiveFeedDuration = dur;
      let ct = Number(v.currentTime) || 0;
      if (dur > 0) {
        ct = Math.min(ct, dur);
      }
      this.immersiveFeedCurrentTime = ct;
      v.volume = this.immersiveFeedVolume;
      v.muted = this.immersiveFeedMuted;
      this.immersiveFeedVolume = v.volume;
      this.immersiveFeedMuted = v.muted;
      if (this.immersiveFeedAutoplayPending) {
        try {
          if (!hooks.isImmersiveMenuActive() && !this.profileFeedOpen) {
            return;
          }
            if (tokenOfEl !== this.immersiveFeedPlayToken) {
              return;
            }
          await v.play();
          await playerRefs.bg?.value?.play().catch(() => {});
        } catch {
          /* 浏览器策略可能阻止自动播放，此时显示中央播放按钮 */
        } finally {
          this.immersiveFeedAutoplayPending = false;
        }
      }
      this.immersiveFeedPaused = v.paused;
    },

    /**
     * 播放时长累计 + PLAY 行为上报（<video> timeupdate 与动态进度回报共用）。
     * 只按相邻两次回报的增量累加，拖拽/切条造成的跳变不计入。
     * @param {any} cur 当前条
     * @param {number} current 当前秒
     * @param {number} duration 总秒
     * @param {boolean} isPlaying 当前是否在播（长视频续播节流用）
     */
    accImmersiveFeedPlayback(cur, current, duration, isPlaying) {
      if (!cur?.id || !Number.isFinite(duration) || duration <= 0) {
        return;
      }
      const behavior = useBehaviorStore();
      const idKey = String(cur.id);
      if (this.immersiveAccVideoId !== idKey) {
        this.immersiveAccVideoId = idKey;
        this.immersivePlaybackAccSec = 0;
        this.immersiveLastTimeSec = null;
      }
      const prev = this.immersiveLastTimeSec;
      this.immersiveLastTimeSec = current;
      if (prev != null) {
        const delta = current - prev;
        if (delta > 0 && delta <= BEHAVIOR_MAX_PLAY_DELTA_SEC) {
          this.immersivePlaybackAccSec += delta;
        }
      }
      if (this.immersivePlaybackAccSec >= duration * BEHAVIOR_PLAY_THRESHOLD && !behavior.hasPlayReported(cur.id)) {
        behavior.markPlayReported(cur.id);
        void behavior.report(cur.id, "PLAY", current * 1000);
      }
      if (isPlaying && this.isLongImmersiveFeedVideo(cur)) {
        longResumeTickCount += 1;
        if (longResumeTickCount >= 30) {
          longResumeTickCount = 0;
          void this.flushImmersiveLongPlaybackResume("tick");
        }
      }
    },

    onImmersiveFeedFgTimeUpdate(e) {
      if (isStaleFgEvent(e)) return;
      if (!hooks.isImmersiveMenuActive() && !this.profileFeedOpen) {
        return;
      }
      const fg = e.target;
      const dur = readVideoDurationFromVideoEl(fg);
      if (dur > 0) {
        const prev = this.immersiveFeedDuration;
        if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
          this.immersiveFeedDuration = dur;
        }
      }
      let ct = Number(fg.currentTime) || 0;
      if (dur > 0) {
        ct = Math.min(ct, dur);
      }
      this.immersiveFeedCurrentTime = ct;
      this.accImmersiveFeedPlayback(
        this.immersiveFeedCurrent,
        ct,
        dur > 0 ? dur : Number(fg.duration),
        !fg.paused
      );
      const bg = playerRefs.bg?.value;
      if (!bg || !fg) {
        return;
      }
      if (Math.abs(bg.currentTime - fg.currentTime) > 0.2) {
        bg.currentTime = fg.currentTime;
      }
    },

    /**
     * 受控 DynamicPlayer 就绪：写时长与画布朝向，并消费自动播放待办。
     * 与 <video> 的 loadedmetadata 对位 —— 切条后由这里决定要不要播。
     * @param {number} durationSec
     * @param {string} canvasType
     */
    onImmersiveFeedDynamicReady(durationSec, canvasType) {
      this.immersiveFeedDuration = Number(durationSec) || 0;
      this.immersiveFeedCurrentTime = 0;
      this.immersiveFeedIsPortrait = String(canvasType ?? "") !== "horizontal";
      if (!this.immersiveFeedAutoplayPending) {
        return;
      }
      if (!hooks.isImmersiveMenuActive() && !this.profileFeedOpen) {
        return;
      }
      this.immersiveFeedAutoplayPending = false;
      this.immersiveFeedPaused = false;
    },

    /** 受控 DynamicPlayer 的进度回报：与视频 timeupdate 同一套累计口径 */
    onImmersiveFeedDynamicTimeUpdate(sec) {
      if (!hooks.isImmersiveMenuActive() && !this.profileFeedOpen) {
        return;
      }
      const total = Number(this.immersiveFeedDuration || 0);
      let t = Number(sec) || 0;
      if (total > 0) {
        t = Math.min(t, total);
      }
      this.immersiveFeedCurrentTime = t;
      this.accImmersiveFeedPlayback(this.immersiveFeedCurrent, t, total, !this.immersiveFeedPaused);
    },

    onImmersiveFeedFgPlay(e) {
      if (isStaleFgEvent(e)) return;
      this.immersiveFeedPaused = false;
      if (this.isLongImmersiveFeedVideo()) {
        longResumeTickCount = 0;
      }
      playerRefs.bg?.value?.play().catch(() => {});
    },

    onImmersiveFeedFgPause(e) {
      if (isStaleFgEvent(e)) return;
      if (this.immersiveFeedAutoplayPending) {
        return;
      }
      this.immersiveFeedPaused = true;
      const fg = playerRefs.fg?.value;
      if (fg) {
        const dur = readVideoDurationFromVideoEl(fg);
        if (dur > 0) {
          this.immersiveFeedDuration = dur;
        }
        let ct = Number(fg.currentTime) || 0;
        if (dur > 0) {
          ct = Math.min(ct, dur);
        }
        this.immersiveFeedCurrentTime = ct;
      }
      if (this.isLongImmersiveFeedVideo()) {
        void this.flushImmersiveLongPlaybackResume("pause");
      }
      playerRefs.bg?.value?.pause();
    },

    onImmersiveFeedFgDurationChange(e) {
      if (isStaleFgEvent(e)) return;
      const v = e.target;
      const dur = readVideoDurationFromVideoEl(v);
      if (dur > 0) {
        const prev = this.immersiveFeedDuration;
        if (!Number.isFinite(prev) || prev <= 0 || Math.abs(prev - dur) > 0.35) {
          this.immersiveFeedDuration = dur;
        }
        const ct = Number(v.currentTime) || 0;
        this.immersiveFeedCurrentTime = dur > 0 ? Math.min(ct, dur) : ct;
      }
    },

    onImmersiveFeedFgSeeked(e) {
      if (isStaleFgEvent(e)) return;
      this.immersiveLastTimeSec = null;
      const fg = e.target;
      const bg = playerRefs.bg?.value;
      if (bg) {
        bg.currentTime = fg.currentTime;
      }
    },

    /** 动态条目按需拉详情(时间轴), 供 DynamicPlayer 播放; 已缓存直接返回 */
    async ensureDynamicDetail(row) {
      const id = String(row?.id ?? "");
      if (!id) return null;
      if (this.dynamicDetailCache[id]) return this.dynamicDetailCache[id];
      try {
        const detail = await fetchDynamicDetail(id);
        if (detail && detail.items) {
          this.dynamicDetailCache[id] = detail;
        }
        return detail ?? null;
      } catch {
        return null;
      }
    },

    onImmersiveFeedFgEnded(e) {
      // DynamicPlayer 的 @ended 无 payload，不受元素身份判据影响
      if (isStaleFgEvent(e)) return;
      const cur = this.immersiveFeedCurrent;
      const now = Number(this.immersiveFeedCurrentTime || 0);
      if (cur?.id) {
        void useBehaviorStore().report(cur.id, "FINISH", now * 1000);
      }
      // 动态行没有 video 元素, 轮播一轮即切下一条(评论打开时停留)
      if (String(cur?.type ?? "") === "dynamic") {
        if (this.unifiedFeedList.length > 1 && !useCommentPanelStore().immersiveCommentPanelOpen) {
          this.immersiveFeedNext();
        }
        return;
      }
      if (this.isLongImmersiveFeedVideo(cur)) {
        void this.flushImmersiveLongPlaybackResume("ended");
      }
      const n = this.unifiedFeedList.length;
      // 评论打开时循环当前条，避免播完突然切走影响阅读
      if (n <= 1 || useCommentPanelStore().immersiveCommentPanelOpen) {
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
      this.immersiveFeedNext();
    },

    onImmersiveScrubInput(e) {
      const t = Number(e.target.value);
      if (this.immersiveFeedIsDynamic) {
        playerRefs.dynamic?.value?.seek(t);
        this.immersiveFeedCurrentTime = Math.max(0, t);
        return;
      }
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      const dur = readVideoDurationFromVideoEl(v);
      if (!(dur > 0)) {
        return;
      }
      const clamped = Math.min(Math.max(0, t), dur);
      v.currentTime = clamped;
      this.immersiveFeedCurrentTime = clamped;
    },

    onImmersiveVolumeInput(e) {
      const vol = Number(e.target.value);
      this.immersiveFeedVolume = vol;
      this.immersiveFeedMuted = vol === 0;
      if (this.immersiveFeedIsDynamic) {
        playerRefs.dynamic?.value?.setVolume(vol);
        return;
      }
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      v.volume = vol;
      v.muted = vol === 0;
    },

    immersiveToggleMute() {
      const next = !this.immersiveFeedMuted;
      this.immersiveFeedMuted = next;
      if (this.immersiveFeedIsDynamic) {
        playerRefs.dynamic?.value?.setMuted(next);
        return;
      }
      const v = playerRefs.fg?.value;
      if (!v) {
        return;
      }
      v.muted = next;
    },

    immersiveTogglePlay() {
      if (this.immersiveFeedIsDynamic) {
        playerRefs.dynamic?.value?.togglePlay();
        return;
      }
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

    /** 画中画只对 <video> 有意义，动态条目无操作 */
    async immersiveRequestPip() {
      if (this.immersiveFeedIsDynamic) {
        return;
      }
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

    /** 下载当前条: 视频走前端直链, 动态走懒转码任务(结果经 SSE 回来) */
    async immersiveDownloadCurrent() {
      const cur = this.immersiveFeedCurrent;
      if (!cur?.id) {
        return;
      }
      if (this.immersiveFeedIsDynamic) {
        void useDynamicStore().requestDownload(cur);
        return;
      }
      const url = this.immersiveFeedVideoSrc;
      if (!url) {
        pushToast("当前内容没有可下载的地址", "info");
        return;
      }
      try {
        await downloadRemoteFile(url, videoDownloadName(cur));
        pushToast("视频已开始下载", "success");
      } catch (e) {
        pushToast(e?.message || "下载失败", "error");
      }
    },

    async immersiveRequestFullscreen() {
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

    onImmersiveFeedKeydown(ev) {
      if (useProfileStore().open || !hooks.isImmersiveMenuActive()) {
        return;
      }
      const tag = ev.target?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || ev.target?.isContentEditable) {
        return;
      }
      const k = ev.key;
      if (k === "ArrowUp" || k === "w" || k === "W") {
        ev.preventDefault();
        this.immersiveFeedPrev();
      } else if (k === "ArrowDown" || k === "s" || k === "S") {
        ev.preventDefault();
        this.immersiveFeedNext();
      } else if (k === " ") {
        ev.preventDefault();
        this.immersiveTogglePlay();
      }
    },

    /** 滚轮 / 触摸上下切换（与常见 App 一致：上滑下一条、下滑上一条） */
    onImmersiveFeedWheel(e) {
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
        this.immersiveFeedNext();
      } else {
        this.immersiveFeedPrev();
      }
      window.setTimeout(() => {
        wheelCooldown = false;
      }, 420);
    },

    onImmersiveFeedTouchStart(ev) {
      const t = ev.touches?.[0]?.clientY;
      if (t != null) {
        touchStartY = t;
      }
    },

    onImmersiveFeedTouchEnd(ev) {
      const y = ev.changedTouches[0]?.clientY ?? touchStartY;
      const dy = touchStartY - y;
      if (Math.abs(dy) < 48) {
        return;
      }
      if (dy > 0) {
        this.immersiveFeedNext();
      } else {
        this.immersiveFeedPrev();
      }
    },

    pauseImmersiveFeedVideos({ muteForeground = false } = {}) {
      const fg = playerRefs.fg?.value;
      const bg = playerRefs.bg?.value;
      fg?.pause();
      bg?.pause();
      playerRefs.dynamic?.value?.pause();
      if (muteForeground) {
        try {
          if (fg) fg.muted = true;
          playerRefs.dynamic?.value?.setMuted(true);
        } catch {
          /* ignore */
        }
      }
    },

    /** 从详情页等遮罩返回时，把前景音量状态与 UI 同步，避免误静音 */
    restoreImmersiveFeedForegroundMuteFromState() {
      const dynamicPlayer = playerRefs.dynamic?.value;
      if (dynamicPlayer) {
        dynamicPlayer.setMuted(this.immersiveFeedMuted);
        dynamicPlayer.setVolume(this.immersiveFeedVolume);
      }
      const fg = playerRefs.fg?.value;
      if (!fg) return;
      try {
        fg.muted = this.immersiveFeedMuted;
        fg.volume = this.immersiveFeedVolume;
      } catch {
        /* ignore */
      }
    },

    /** 退出全屏流时清掉续播节流状态，避免下次进入沿用上一轮的节流窗口 */
    resetImmersiveLongResumeState() {
      longResumeTickCount = 0;
      longResumeAppliedVideoId = "";
    },

    /** 切换作者/数据源时重置底部进度条状态（index 仍为 0 时 watch 不会触发） */
    resetImmersiveFeedProgressUiForSwitch() {
      this.immersiveFeedIsPortrait = false;
      this.immersiveFeedAutoplayPending = true;
      this.immersiveFeedDuration = 0;
      this.immersiveFeedCurrentTime = 0;
    },

    bumpPlayToken() {
      this.immersiveFeedPlayToken += 1;
    },

    /** 前后台两个 <video> 的 DOM 由 ImmersiveView 持有，宿主只经注册的 ref 命令式重载 */
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
        const dynamicPlayer = playerRefs.dynamic?.value;
        if (dynamicPlayer) {
          dynamicPlayer.pause();
          dynamicPlayer.seek(0);
        }
      } catch {
        /* 元素已卸载 */
      }
    },

    // ===== 点赞 / 不感兴趣 / 关注 =====

    async onImmersiveLikeClick() {
      const cur = this.immersiveFeedCurrent;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录后再点赞", "info");
        return;
      }
      const t = Date.now();
      if (t - lastLikeClickAt < 300) return;
      lastLikeClickAt = t;
      if (this.immersiveLikeBusy) return;
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      this.immersiveLikeBusy = true;
      try {
        await toggleVideoLikeAndSync(vid, {
          wasLiked: this.immersiveFeedLikedDisplay,
          likeCount: cur.likeCount,
          patch: (p) => hooks.patchFeedVideoById(cur.id, p),
        });
        void useBehaviorStore().report(cur.id, "LIKE", Number(this.immersiveFeedCurrentTime || 0) * 1000);
      } catch (error) {
        pushToast(error.message || "点赞失败", "error");
      } finally {
        this.immersiveLikeBusy = false;
      }
    },

    async onImmersiveDislikeClick() {
      const cur = this.immersiveFeedCurrent;
      if (!cur?.id) return;
      if (!useAuthStore().isAuthed) {
        pushToast("请先登录", "info");
        return;
      }
      const vid = parseDigitSnowflakeId(cur.id);
      if (vid == null) return;
      try {
        await dislikeVideoApi(vid);
        void useBehaviorStore().report(cur.id, "DISLIKE", Number(this.immersiveFeedCurrentTime || 0) * 1000);
        pushToast("已标记为不感兴趣", "success");
      } catch (error) {
        pushToast(error.message || "操作失败", "error");
      }
    },

    onImmersiveForwardClick() {
      useShareStore().openForwardVideo(this.immersiveFeedCurrent);
    },

    async toggleImmersiveFollow() {
      const c = this.immersiveFeedCurrent;
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
      const id = this.immersiveFeedCurrent?.id;
      if (!id) return;
      hooks.patchFeedVideoById(id, partial);
      const detail = useDetailStore();
      if (detail.video?.id && String(detail.video.id) === String(id)) {
        detail.video = { ...detail.video, ...partial };
      }
    },

    async toggleCurrentSelfVideoVisibility() {
      const cur = this.immersiveFeedCurrent;
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
      const cur = this.immersiveFeedCurrent;
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
      if (!this.immersiveIsSelfCurrent) {
        pushToast("只能删除自己的视频", "info");
        return;
      }
      const cur = this.immersiveFeedCurrent;
      if (!cur?.id) return;
      this.closeSelfVideoActionMenu();
      this.pendingDeleteVideoId = String(cur.id);
      this.selfVideoDeleteConfirmOpen = true;
    },

    cancelDeleteCurrentSelfVideo() {
      this.selfVideoDeleteConfirmOpen = false;
      this.pendingDeleteVideoId = null;
      this.pendingDeleteRowType = null;
    },

    async confirmDeleteCurrentSelfVideo() {
      const rawId = this.pendingDeleteVideoId;
      const isDynamic = this.pendingDeleteRowType === "dynamic";
      if (!rawId) {
        this.selfVideoDeleteConfirmOpen = false;
        return;
      }
      try {
        await (isDynamic ? deleteMyDynamic(rawId) : deleteMyVideo(rawId));
      } catch (e) {
        pushToast(e?.message || "删除失败", "error");
        return;
      }
      this.selfVideoDeleteConfirmOpen = false;
      this.pendingDeleteVideoId = null;
      this.pendingDeleteRowType = null;
      hooks.removeVideoById(String(rawId));

      const deletedTip = isDynamic ? "已删除该动态" : "已删除该视频";
      // 当前队列被删空时没有可播的了，直接退出全屏流
      const scopeEmpty =
        (this.feedScope === "profile" && !this.profileFeedQueue.length) ||
        (this.feedScope === "liked" && !this.likedFeedQueue.length);
      if (scopeEmpty) {
        this.closeProfileFeed();
        pushToast(deletedTip, "success");
        return;
      }
      const list = this.unifiedFeedList;
      if (this.immersiveFeedIndex >= list.length) {
        this.immersiveFeedIndex = Math.max(0, list.length - 1);
      }
      nextTick(() => {
        this.reloadFeedPlayers();
      });
      pushToast(deletedTip, "success");
    },

    /** 退出全屏流：停播并清空队列，复位面板与续播节流状态 */
    closeProfileFeed() {
      void this.flushImmersiveLongPlaybackResume("leave");
      this.pauseImmersiveFeedVideos({ muteForeground: true });
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
      this.pendingDeleteRowType = null;
      useCommentPanelStore().immersiveCommentPanelOpen = false;
      this.profileFeedOpen = false;
      this.feedScope = "immersive";
      this.profileFeedQueue = [];
      this.likedFeedQueue = [];
      this.followFeedQueue = [];
      this.resetImmersiveLongResumeState();
    },

    // ===== 关注页：全部动态 / 按作者筛选 =====

    /** 列表行 → 可播放行：补齐播放地址，剔除取不到地址的 */
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
          return { ...x, videoUrl: url || x.videoUrl };
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
      this.resetImmersiveFeedProgressUiForSwitch();
      this.followSubscribedFilterAuthorId = null;
      this.followAuthorFeedQueue = [];
      this.followAuthorVideoPageNo = 1;
      this.followAuthorVideoHasMore = false;
      this.immersiveFeedIndex = 0;
      this.bumpPlayToken();
      this.immersiveFeedAutoplayPending = true;
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
      this.resetImmersiveFeedProgressUiForSwitch();
      this.followSubscribedFilterAuthorId = id;
      this.followAuthorFeedQueue = [];
      this.followAuthorVideoPageNo = 1;
      this.followAuthorVideoHasMore = true;
      this.immersiveFeedIndex = 0;
      this.bumpPlayToken();
      this.immersiveFeedAutoplayPending = true;
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
      if (list.length - 1 - this.immersiveFeedIndex >= FOLLOW_FEED_PREFETCH_THRESHOLD) {
        return;
      }
      if (aid != null && aid !== "") {
        void this.loadSubscribedAuthorVideos(aid, { reset: false });
      } else {
        void this.loadSubscribedFollowVideos({ reset: false });
      }
    },

    // ===== 长视频续播 =====

    isLongImmersiveFeedVideo(item = this.immersiveFeedCurrent) {
      // videoType 已退役: 达到原短视频时长上限(5分钟)即按长视频记录续播
      const dur = Number(item?.duration);
      return Number.isFinite(dur) && dur >= 300;
    },

    getImmersiveLongCurrentPlayTimeMs() {
      const player = playerRefs.fg?.value;
      if (!player) return 0;
      const ms = Math.floor((Number(player.currentTime || 0) || 0) * 1000);
      return ms > 0 ? ms : 0;
    },

    getImmersiveLongPlaybackResumePayload(playTimeMs) {
      const userId = useAuthStore().userId ?? useProfileStore().user?.id;
      const videoId = this.immersiveFeedCurrent?.id;
      if (!userId || userId === "" || !videoId) return null;
      if (!Number.isFinite(Number(playTimeMs))) return null;
      return { userId, videoId, playTime: playTimeMs };
    },

    applyImmersiveLongPlaybackResumeIfNeeded(player) {
      const cur = this.immersiveFeedCurrent;
      if (!this.isLongImmersiveFeedVideo(cur)) return;
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

    flushImmersiveLongPlaybackResume(reason) {
      const cur = this.immersiveFeedCurrent;
      const player = playerRefs.fg?.value;
      if (!this.isLongImmersiveFeedVideo(cur) || !player) return Promise.resolve();
      if (reason !== "pause" && player.paused) return Promise.resolve();
      const playTimeMs = this.getImmersiveLongCurrentPlayTimeMs();
      if (reason !== "pause" && reason !== "switch" && reason !== "leave" && reason !== "unload" && playTimeMs <= 0) {
        return Promise.resolve();
      }
      const payload = this.getImmersiveLongPlaybackResumePayload(playTimeMs);
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

    resetImmersivePanelCommentsState() {
      this.immersivePanelTopComments = [];
      this.immersivePanelCommentsLoading = false;
      this.immersivePanelCommentsPageNo = 1;
      this.immersivePanelCommentsPages = 0;
      this.immersivePanelCommentDraft = "";
      this.immersivePanelCommentSubmitting = false;
      this.immersivePanelReplyTarget = null;
      this.immersivePanelThreadByRootId = {};
      this.immersivePanelExpandedRoots = {};
    },

    toggleImmersiveCommentPanel() {
      if (!this.immersiveFeedCurrent?.id) {
        return;
      }
      const panel = useCommentPanelStore();
      panel.searchExpandCommentOpen = false;
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
      panel.immersiveCommentPanelOpen = !panel.immersiveCommentPanelOpen;
    },

    closeImmersiveCommentPanel() {
      const panel = useCommentPanelStore();
      panel.immersiveCommentPanelOpen = false;
      panel.searchExpandCommentOpen = false;
      panel.searchListCommentOpen = false;
      panel.searchListCommentCard = null;
    },

    async loadImmersivePanelTopComments(reset, opts = {}) {
      const preserveThreadUi = opts.preserveThreadUi === true;
      const savedExpanded = preserveThreadUi && reset ? { ...this.immersivePanelExpandedRoots } : null;

      const vid = parseDigitSnowflakeId(this.activeCommentVideo?.id);
      if (!vid) {
        return;
      }
      if (reset) {
        this.immersivePanelCommentsPageNo = 1;
        this.immersivePanelTopComments = [];
        if (!preserveThreadUi) {
          this.immersivePanelThreadByRootId = {};
          this.immersivePanelExpandedRoots = {};
        }
      }
      this.immersivePanelCommentsLoading = true;
      try {
        const { pages, list } = await fetchCommentPage({
          videoId: vid,
          parentId: 0,
          pageNo: this.immersivePanelCommentsPageNo,
          pageSize: IMMERSIVE_COMMENT_PAGE_SIZE,
        });
        this.immersivePanelCommentsPages = pages || 0;
        if (reset) {
          this.immersivePanelTopComments = list;
        } else {
          this.immersivePanelTopComments = [...this.immersivePanelTopComments, ...list];
        }

        if (savedExpanded) {
          const topIds = new Set(this.immersivePanelTopComments.map((x) => String(x.id)));
          const nextExpanded = {};
          for (const k of Object.keys(savedExpanded)) {
            if (savedExpanded[k] && topIds.has(k)) {
              nextExpanded[k] = true;
            }
          }
          this.immersivePanelExpandedRoots = nextExpanded;
          const nextThreads = { ...this.immersivePanelThreadByRootId };
          for (const k of Object.keys(nextExpanded)) {
            delete nextThreads[k];
          }
          this.immersivePanelThreadByRootId = nextThreads;
          for (const k of Object.keys(nextExpanded)) {
            void this.ensureImmersivePanelThreadLoaded(k);
          }
        }
      } catch (e) {
        pushToast(e?.message || "评论加载失败", "error");
      } finally {
        this.immersivePanelCommentsLoading = false;
      }
    },

    immersivePanelCommentHasMore() {
      return this.immersivePanelCommentsPageNo < this.immersivePanelCommentsPages;
    },

    async loadMoreImmersivePanelComments() {
      if (this.immersivePanelCommentsLoading || !this.immersivePanelCommentHasMore()) {
        return;
      }
      this.immersivePanelCommentsPageNo += 1;
      await this.loadImmersivePanelTopComments(false);
    },

    async ensureImmersivePanelThreadLoaded(rootId) {
      const vid = parseDigitSnowflakeId(this.activeCommentVideo?.id);
      const k = String(rootId);
      if (!vid || this.immersivePanelThreadByRootId[k]?.loaded) {
        return;
      }
      this.immersivePanelThreadByRootId = {
        ...this.immersivePanelThreadByRootId,
        [k]: { ...(this.immersivePanelThreadByRootId[k] || {}), loading: true, list: [] },
      };
      try {
        const { list } = await fetchCommentPage({
          videoId: vid,
          parentId: rootId,
          rootId,
          pageNo: 1,
          pageSize: 100,
        });
        this.immersivePanelThreadByRootId = {
          ...this.immersivePanelThreadByRootId,
          [k]: { loaded: true, loading: false, list },
        };
      } catch (e) {
        this.immersivePanelThreadByRootId = {
          ...this.immersivePanelThreadByRootId,
          [k]: { loaded: false, loading: false, list: [] },
        };
        pushToast(e?.message || "回复加载失败", "error");
      }
    },

    toggleImmersivePanelThread(rootId) {
      const k = String(rootId);
      if (this.immersivePanelExpandedRoots[k]) {
        const next = { ...this.immersivePanelExpandedRoots };
        delete next[k];
        this.immersivePanelExpandedRoots = next;
      } else {
        this.immersivePanelExpandedRoots = { ...this.immersivePanelExpandedRoots, [k]: true };
        void this.ensureImmersivePanelThreadLoaded(rootId);
      }
    },

    setImmersivePanelReplyTarget(row) {
      if (!row) {
        this.immersivePanelReplyTarget = null;
        return;
      }
      this.immersivePanelReplyTarget = {
        id: row.id,
        nickname: row.nickname || "用户",
      };
    },

    async submitImmersivePanelComment() {
      const text = this.immersivePanelCommentDraft.trim();
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
      const wasTopLevel = this.immersivePanelReplyTarget == null;
      const parentId = wasTopLevel ? 0 : this.immersivePanelReplyTarget.id;
      this.immersivePanelCommentSubmitting = true;
      try {
        await publishCommentApi({ videoId: vid, content: text, parentId });
        this.immersivePanelCommentDraft = "";
        this.immersivePanelReplyTarget = null;
        if (cur?.id && wasTopLevel) {
          hooks.patchFeedVideoById(cur.id, {
            commentCount: Number(cur.commentCount ?? 0) + 1,
          });
        }
        await this.loadImmersivePanelTopComments(true, { preserveThreadUi: true });
      } catch (e) {
        pushToast(e?.message || "发送失败", "error");
      } finally {
        this.immersivePanelCommentSubmitting = false;
      }
    },

    async onDeleteImmersivePanelComment(c) {
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
        await this.loadImmersivePanelTopComments(true, { preserveThreadUi: true });
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
      this.immersivePanelTopComments = this.immersivePanelTopComments.map(merge);
      const threads = { ...this.immersivePanelThreadByRootId };
      for (const k of Object.keys(threads)) {
        const t = threads[k];
        if (t?.list?.length) {
          threads[k] = { ...t, list: t.list.map(merge) };
        }
      }
      this.immersivePanelThreadByRootId = threads;
    },
  },
});
