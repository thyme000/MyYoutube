// @ts-check
/**
 * 历史播放记录域状态：统一观看历史的分页数据。
 */
import { defineStore } from "pinia";
import { fetchLongHistoryPage } from "../api/videoApi";
import { useAuthStore } from "./authStore";
import { useImmersiveStore } from "./immersiveStore";

export const useHistoryStore = defineStore("history", {
  state: () => ({
    historyLongRows: [],
    historyLongLoading: false,
    historyLongPageNo: 1,
    historyLongPages: 0,
    historyLongExpanded: false,
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
    async toggleLongExpanded() {
      this.historyLongExpanded = !this.historyLongExpanded;
      this.historyLongPageNo = 1;
      await this.loadLongPage({ reset: true });
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
    openLongCard(index) {
      useImmersiveStore().openProfileFeed(this.historyLongRows, index);
    },
  },
});
