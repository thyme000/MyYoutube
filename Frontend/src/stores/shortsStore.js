/**
 * 短视频播放器域状态：沉浸 feed 播放态、评论侧栏，
 * 以及被历史/赞过/个人主页/关注复用的全屏队列（feedScope 区分数据源）。
 * 跨域能力（跨列表补丁）经 registerShortsHooks 注入；播放器 DOM ref 经 registerShortsFeedPlayerRefs 注入。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { resolveVideoUrl } from "../utils/videoCard.js";
import { parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { commentDisplayNickname as commentDisplayNicknameText } from "../utils/comment.js";
import {
  fetchCommentPage,
  deleteComment as deleteCommentApi,
  publishComment as publishCommentApi,
} from "../api/commentApi.js";
import { toast as pushToast } from "../utils/toast.js";
import { useAuthStore } from "./authStore.js";
import { useCommentStore } from "./commentStore.js";
import { useHomeStore } from "./homeStore.js";
import { useSearchStore } from "./searchStore.js";

const SHORTS_COMMENT_PAGE_SIZE = 10;

const playerRefs = { fg: null, bg: null };

/** 宿主注册全屏播放器 DOM ref（app 内单例，模块级持有不入 store） */
export function registerShortsFeedPlayerRefs({ fg, bg }) {
  if (fg) playerRefs.fg = fg;
  if (bg) playerRefs.bg = bg;
}

const hooks = {
  /** 跨多份列表同步点赞数/评论数 */
  patchFeedVideoById: () => {},
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
    shortsCommentPanelOpen: false,
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
    /** 顺播累计秒数 + 当前条 id */
    shortsPlaybackAccSec: 0,
    shortsLastTimeSec: null,
    shortsAccVideoId: "",
  }),

  getters: {
    /** 全屏流当前队列：feedScope 决定数据源，首页/默认流读 homeStore */
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
    shortsFeedCurrent() {
      const list = this.unifiedFeedList;
      if (!list.length) return null;
      const i = Math.min(Math.max(0, this.shortsFeedIndex), list.length - 1);
      return list[i];
    },
    /** 评论侧栏：短视频流、搜索全屏放大或搜索列表共用同一套列表与接口 */
    activeCommentVideo() {
      const search = useSearchStore();
      if (search.searchExpandCommentOpen && search.searchExpandCard) {
        return search.searchExpandCard;
      }
      if (search.searchListCommentOpen && search.searchListCommentCard) {
        return search.searchListCommentCard;
      }
      return this.shortsFeedCurrent;
    },
    activeCommentVideoAllowsComment() {
      return Number(this.activeCommentVideo?.allowComment ?? 1) === 1;
    },
  },

  actions: {
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
        playerRefs.fg?.value?.load();
        playerRefs.bg?.value?.load();
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
      const search = useSearchStore();
      search.searchExpandCommentOpen = false;
      search.searchListCommentOpen = false;
      search.searchListCommentCard = null;
      this.shortsCommentPanelOpen = !this.shortsCommentPanelOpen;
    },

    closeShortsCommentPanel() {
      const search = useSearchStore();
      this.shortsCommentPanelOpen = false;
      search.searchExpandCommentOpen = false;
      search.searchListCommentOpen = false;
      search.searchListCommentCard = null;
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
