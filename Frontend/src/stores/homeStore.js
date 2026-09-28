/**
 * 首页 feed 域状态：混合流卡片、首页短视频行、长视频分页与分类/排序。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HomeView 时随之整体搬出。
 */
import { defineStore } from "pinia";

/** 桌面/移动端首屏长视频张数阈值 */
export const HOME_LONG_HEAD_COUNT_DESKTOP = 9;
export const HOME_LONG_HEAD_COUNT_MOBILE = 6;

export const homeSortOptions = [
  { value: "RECOMMENDED", label: "推荐优先" },
  { value: "LATEST", label: "最新发布" },
  { value: "MOST_PLAYED", label: "最多播放" },
  { value: "MOST_LIKED", label: "最多点赞" },
];

const hooks = { reloadHome: null };

/** 宿主(App.vue)注册首页重载(走 loadModuleCards 的 home 分支) */
export function registerHomeHooks({ reloadHome }) {
  if (reloadHome) hooks.reloadHome = reloadHome;
}

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
  },
});
