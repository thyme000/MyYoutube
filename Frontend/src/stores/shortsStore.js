/**
 * 短视频播放器域状态：沉浸 feed 播放态、评论侧栏，
 * 以及被历史/赞过/个人主页/关注复用的全屏队列（feedScope 区分数据源）。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 ShortsView 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { resolveVideoUrl } from "../utils/videoCard.js";

const playerRefs = { fg: null, bg: null };

/** 宿主注册全屏播放器 DOM ref（app 内单例，模块级持有不入 store） */
export function registerShortsFeedPlayerRefs({ fg, bg }) {
  if (fg) playerRefs.fg = fg;
  if (bg) playerRefs.bg = bg;
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
  },
});
