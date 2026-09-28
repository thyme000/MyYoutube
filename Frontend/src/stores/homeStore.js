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
