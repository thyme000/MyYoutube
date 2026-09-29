// @ts-check
/**
 * 首页 feed 域状态：混合流卡片、首页短视频行、长视频分页与分类/排序。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HomeView 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { patchRowById, patchRowsByAuthorId, removeRowById } from "../utils/feedRowPatch.js";

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
  }),
  actions: {
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
