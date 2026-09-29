// @ts-check
/**
 * 首页 feed 域状态：混合流卡片、首页短视频行、长视频分页与分类/排序。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HomeView 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { fetchShortsFeedPage, fetchVideoListPage } from "../api/videoApi";
import { VIDEO_CATEGORY_CHIPS } from "../constants/videoCategories";
import { patchRowById, patchRowsByAuthorId, removeRowById } from "../utils/feedRowPatch.js";
import { useNavigationStore } from "./navigationStore";
import { useDetailStore } from "./detailStore";
import { useProfileStore } from "./profileStore";
import { useStudioStore } from "./studioStore";

/** 桌面/移动端首屏长视频张数阈值 */
export const HOME_LONG_HEAD_COUNT_DESKTOP = 9;
export const HOME_LONG_HEAD_COUNT_MOBILE = 6;

export const homeSortOptions = [
  { value: "RECOMMENDED", label: "推荐优先" },
  { value: "LATEST", label: "最新发布" },
  { value: "MOST_PLAYED", label: "最多播放" },
  { value: "MOST_LIKED", label: "最多点赞" },
];

const hooks = {
  /** 首页重载(走 loadModuleCards 的 home 分支) */
  reloadHome: null,
  /** 首页 Shorts 横条 DOM 容器：模板 ref 经此回写，App 的滚轮处理读它 */
  shortsStripRef: null,
  /** 横条滚动/滚轮处理(App 级，依赖 hoverPreview 与预拉下一页) */
  onHomeShortsStripScroll: () => {},
  onHomeShortsStripWheel: () => {},
  /** 首页卡片进详情(App 级，含菜单分支) */
  openVideoDetail: () => {},
  /** 首页 Shorts 卡片进全屏短视频流(App 级) */
  openShortsFeed: () => {},
  /** 个人中心是否展开(App 级状态，HomeView 据此隐藏分类条) */
  isProfileCenterOpen: () => false,
};

/** 宿主(App.vue)注册首页依赖：重载、横条 DOM、进详情/短视频流、个人中心开合 */
export function registerHomeHooks({
  reloadHome,
  shortsStripRef,
  onHomeShortsStripScroll,
  onHomeShortsStripWheel,
  openVideoDetail,
  openShortsFeed,
  isProfileCenterOpen,
}) {
  if (reloadHome) hooks.reloadHome = reloadHome;
  if (shortsStripRef) hooks.shortsStripRef = shortsStripRef;
  if (onHomeShortsStripScroll) hooks.onHomeShortsStripScroll = onHomeShortsStripScroll;
  if (onHomeShortsStripWheel) hooks.onHomeShortsStripWheel = onHomeShortsStripWheel;
  if (openVideoDetail) hooks.openVideoDetail = openVideoDetail;
  if (openShortsFeed) hooks.openShortsFeed = openShortsFeed;
  if (isProfileCenterOpen) hooks.isProfileCenterOpen = isProfileCenterOpen;
}

/** HomeView 经此取回 App 级交互，避免反向 import App */
export const homeBridgeHooks = hooks;

export const useHomeStore = defineStore("home", {
  state: () => ({
    loading: false,
    cards: [],
    homeShortsRows: [],
    homeShortsPageNo: 1,
    homeShortsPages: 0,
    homeShortsLoadingMore: false,
    homeLongPageNo: 1,
    homeLongPages: 0,
    homeLongLoadingMore: false,
    homeCategoryKey: "all",
    homeSortKey: "RECOMMENDED",
    /** 离开首页/进详情前的列表快照，回首页无请求恢复 */
    homeFeedSnapshot: null,
    homeLongLoadLock: false,
  }),
  actions: {
    /** 当前是否停在首页主区（其他叠层打开时暂停分页与快照） */
    isOnHome() {
      const detail = useDetailStore();
      return (
        useNavigationStore().activeMenuKey === "home" &&
        !detail.open &&
        !useProfileStore().open &&
        !useStudioStore().showStudio
      );
    },
    saveSnapshot() {
      if (useNavigationStore().activeMenuKey !== "home") {
        return;
      }
      this.homeFeedSnapshot = {
        cards: [...this.cards],
        homeShortsRows: [...this.homeShortsRows],
        homeLongPageNo: this.homeLongPageNo,
        homeLongPages: this.homeLongPages,
        homeShortsPageNo: this.homeShortsPageNo,
        homeShortsPages: this.homeShortsPages,
        homeCategoryKey: this.homeCategoryKey,
        homeSortKey: this.homeSortKey,
      };
    },
    /** @returns {boolean} 是否有快照可恢复 */
    restoreSnapshot() {
      const s = this.homeFeedSnapshot;
      if (!s) {
        return false;
      }
      this.cards = [...s.cards];
      this.homeShortsRows = [...s.homeShortsRows];
      this.homeLongPageNo = s.homeLongPageNo;
      this.homeLongPages = s.homeLongPages;
      this.homeShortsPageNo = s.homeShortsPageNo;
      this.homeShortsPages = s.homeShortsPages;
      this.homeCategoryKey = s.homeCategoryKey;
      this.homeSortKey = s.homeSortKey;
      return true;
    },
    clearSnapshot() {
      this.homeFeedSnapshot = null;
    },
    async loadShortsMore() {
      if (this.homeShortsLoadingMore) return;
      if (this.homeShortsPageNo >= (this.homeShortsPages || 0)) return;
      this.homeShortsLoadingMore = true;
      try {
        const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === this.homeCategoryKey);
        const nextPage = this.homeShortsPageNo + 1;
        const res = await fetchShortsFeedPage({
          pageNo: nextPage,
          pageSize: 18,
          category: chip?.code || undefined,
        });
        const nextList = Array.isArray(res.list) ? res.list : [];
        if (nextList.length) {
          this.homeShortsRows = [...this.homeShortsRows, ...nextList];
        }
        this.homeShortsPages = res.pages ?? this.homeShortsPages;
        this.homeShortsPageNo = nextPage;
      } catch {
        // 保持当前列表，避免影响首页主流
      } finally {
        this.homeShortsLoadingMore = false;
        if (useNavigationStore().activeMenuKey === "home") {
          this.saveSnapshot();
        }
      }
    },
    /** 横条接近末端时续拉下一页 */
    maybeLoadMoreShortsByElement(el) {
      if (!el) return;
      const remain = el.scrollWidth - el.scrollLeft - el.clientWidth;
      if (remain < 260) {
        void this.loadShortsMore();
      }
    },
    async loadLongMore() {
      if (this.homeLongLoadLock || this.homeLongLoadingMore) return;
      if (this.homeLongPageNo >= (this.homeLongPages || 0)) return;
      if (!this.isOnHome()) return;
      this.homeLongLoadLock = true;
      this.homeLongLoadingMore = true;
      try {
        const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === this.homeCategoryKey);
        const nextPage = this.homeLongPageNo + 1;
        const res = await fetchVideoListPage({
          pageNo: nextPage,
          pageSize: 24,
          category: chip?.code || undefined,
          sort: this.homeSortKey,
          videoType: "LONG_VIDEO",
        });
        const nextList = Array.isArray(res.list) ? res.list : [];
        if (nextList.length) {
          this.cards = [...this.cards, ...nextList];
        }
        this.homeLongPages = res.pages ?? this.homeLongPages;
        this.homeLongPageNo = nextPage;
      } catch {
        // 静默失败，不影响当前浏览
      } finally {
        this.homeLongLoadingMore = false;
        this.homeLongLoadLock = false;
        if (useNavigationStore().activeMenuKey === "home") {
          this.saveSnapshot();
        }
      }
    },
    maybeLoadMoreLongByWindow() {
      if (useNavigationStore().activeMenuKey !== "home") return;
      if (this.loading || this.homeLongLoadingMore) return;
      const doc = document.documentElement;
      const remain = doc.scrollHeight - window.scrollY - window.innerHeight;
      if (remain < 280) {
        void this.loadLongMore();
      }
    },

    selectCategory(key) {
      this.homeCategoryKey = key;
      hooks.reloadHome?.();
    },
    selectSort(value) {
      this.homeSortKey = value;
      hooks.reloadHome?.();
    },

    // 同一份视频/作者会同时出现在多份列表，feedPatchStore 负责广播，各域只改自己的
    patchVideoById(id, patch) {
      this.cards = patchRowById(this.cards, id, patch);
      this.homeShortsRows = patchRowById(this.homeShortsRows, id, patch);
    },
    patchAuthorFollow(authorId, isFollow) {
      this.cards = patchRowsByAuthorId(this.cards, authorId, isFollow);
      this.homeShortsRows = patchRowsByAuthorId(this.homeShortsRows, authorId, isFollow);
    },
    removeVideoById(id) {
      this.cards = removeRowById(this.cards, id);
      this.homeShortsRows = removeRowById(this.homeShortsRows, id);
    },
  },
});
