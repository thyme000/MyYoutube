/**
 * 「赞过的视频」域状态：GET /like/{userId}/list 的分页数据。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 LikedView 时随之整体搬出。
 */
import { defineStore } from "pinia";

export const LIKED_PAGE_SIZE = 12;

export const useLikedStore = defineStore("liked", {
  state: () => ({
    likedRows: [],
    likedPageNo: 1,
    likedPages: 0,
    likedTotal: 0,
    likedLoading: false,
    likedLoadingMore: false,
  }),
});
