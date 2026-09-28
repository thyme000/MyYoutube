// @ts-check
/**
 * 历史播放记录域状态：长视频与短视频两个列表的分页数据。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HistoryView 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { fetchLongHistoryPage, fetchShortHistoryPage } from "../api/videoApi";
import { useAuthStore } from "./authStore";
import { useShortsStore } from "./shortsStore";

export const useHistoryStore = defineStore("history", {
  state: () => ({
    historyLongRows: [],
    historyLongLoading: false,
    historyLongPageNo: 1,
    historyLongPages: 0,
    historyLongExpanded: false,
    historyShortRows: [],
    historyShortLoading: false,
    historyShortPageNo: 1,
    historyShortPages: 0,
    historyShortExpanded: false,
  }),
  actions: {
    async loadLongPage({ reset = false } = {}) {
      const auth = useAuthStore();
      if (!auth.isAuthed) return;
      if (this.historyLongLoading) return;
      this.historyLongLoading = true;
      try {
        const targetPage = reset ? 1 : this.historyLongPageNo;
        const pageSize = this.historyLongExpanded ? 9 : 3;
        const res = await fetchLongHistoryPage({ pageNo: targetPage, pageSize });
        const nextList = Array.isArray(res.list) ? res.list : [];
        this.historyLongRows = nextList;
        this.historyLongPages = Number(res.pages || 0);
        if (reset) {
          this.historyLongPageNo = 1;
        }
      } catch {
        this.historyLongRows = [];
        this.historyLongPageNo = 1;
        this.historyLongPages = 0;
      }
      this.historyLongLoading = false;
    },
    async loadShortPage({ reset = false } = {}) {
      const auth = useAuthStore();
      if (!auth.isAuthed) return;
      if (this.historyShortLoading) return;
      this.historyShortLoading = true;
      try {
        const targetPage = reset ? 1 : this.historyShortPageNo;
        const pageSize = this.historyShortExpanded ? 15 : 5;
        const res = await fetchShortHistoryPage({ pageNo: targetPage, pageSize });
        const nextList = Array.isArray(res.list) ? res.list : [];
        this.historyShortRows = nextList;
        this.historyShortPages = Number(res.pages || 0);
        if (reset) {
          this.historyShortPageNo = 1;
        }
      } catch {
        this.historyShortRows = [];
        this.historyShortPageNo = 1;
        this.historyShortPages = 0;
      } finally {
        this.historyShortLoading = false;
      }
    },
    async toggleLongExpanded() {
      this.historyLongExpanded = !this.historyLongExpanded;
      this.historyLongPageNo = 1;
      await this.loadLongPage({ reset: true });
    },
    async toggleShortExpanded() {
      this.historyShortExpanded = !this.historyShortExpanded;
      this.historyShortPageNo = 1;
      await this.loadShortPage({ reset: true });
    },
    async longPrevPage() {
      if (this.historyLongPageNo <= 1 || this.historyLongLoading) return;
      this.historyLongPageNo -= 1;
      await this.loadLongPage();
    },
    async longNextPage() {
      if (this.historyLongPageNo >= (this.historyLongPages || 1) || this.historyLongLoading) return;
      this.historyLongPageNo += 1;
      await this.loadLongPage();
    },
    async shortPrevPage() {
      if (this.historyShortPageNo <= 1 || this.historyShortLoading) return;
      this.historyShortPageNo -= 1;
      await this.loadShortPage();
    },
    async shortNextPage() {
      if (this.historyShortPageNo >= (this.historyShortPages || 1) || this.historyShortLoading) return;
      this.historyShortPageNo += 1;
      await this.loadShortPage();
    },
    openLongCard(index) {
      useShortsStore().openProfileFeed(this.historyLongRows, index, { forceVideoType: "LONG_VIDEO" });
    },
    openShortCard(index) {
      useShortsStore().openProfileFeed(this.historyShortRows, index, { forceVideoType: "SHORT_VIDEO" });
    },
  },
});
