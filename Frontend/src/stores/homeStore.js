/**
 * 首页 feed 域状态：混合流卡片、首页短视频行、长视频分页与分类/排序。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HomeView 时随之整体搬出。
 */
import { defineStore } from "pinia";

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
});
