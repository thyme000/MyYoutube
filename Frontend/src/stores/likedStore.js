/**
 * 「赞过的视频」域状态：GET /like/{userId}/list 的分页数据。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 LikedView 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { fetchLikedVideosPage } from "../api/likeApi.js";
import { toast as pushToast } from "../utils/toast.js";
import { patchRowById, patchRowsByAuthorId, removeRowById } from "../utils/feedRowPatch.js";

export const LIKED_PAGE_SIZE = 12;

const hooks = { getUserId: () => null };

/** 宿主(App.vue)注册当前用户 id 读取器 */
export function registerLikedHooks({ getUserId }) {
  if (getUserId) hooks.getUserId = getUserId;
}

export const useLikedStore = defineStore("liked", {
  state: () => ({
    likedRows: [],
    likedPageNo: 1,
    likedPages: 0,
    likedTotal: 0,
    likedLoading: false,
    likedLoadingMore: false,
  }),
  actions: {
    /** GET /like/{userId}/list 分页加载 */
    async load({ reset = false } = {}) {
      const uid = hooks.getUserId();
      if (!uid) {
        return;
      }
      const isFirst = reset || this.likedRows.length === 0;
      if (isFirst) {
        if (this.likedLoadingMore) return;
        this.likedLoading = true;
      } else {
        if (this.likedLoading || this.likedLoadingMore) return;
        if (this.likedPageNo >= (this.likedPages || 0)) return;
        this.likedLoadingMore = true;
      }
      try {
        const pageNo = isFirst ? 1 : this.likedPageNo + 1;
        const res = await fetchLikedVideosPage(uid, { pageNo, pageSize: LIKED_PAGE_SIZE });
        if (isFirst) {
          this.likedRows = res.list || [];
        } else {
          this.likedRows = [...this.likedRows, ...(res.list || [])];
        }
        this.likedPageNo = pageNo;
        this.likedPages = res.pages ?? 0;
        this.likedTotal = res.total ?? 0;
      } catch (error) {
        if (isFirst) {
          this.likedRows = [];
        }
        pushToast(error.message || "加载赞过的视频失败", "error");
      } finally {
        this.likedLoading = false;
        this.likedLoadingMore = false;
      }
    },

    // 同一份视频/作者会同时出现在多份列表，feedPatchStore 负责广播，各域只改自己的
    patchVideoById(id, patch) {
      this.likedRows = patchRowById(this.likedRows, id, patch);
    },
    patchAuthorFollow(authorId, isFollow) {
      this.likedRows = patchRowsByAuthorId(this.likedRows, authorId, isFollow);
    },
    removeVideoById(id) {
      this.likedRows = removeRowById(this.likedRows, id);
    },
  },
});
