/**
 * 评论面板开关：短视频流右侧侧栏、搜索放大层、搜索列表内联评论共用同一套面板，
 * 三个入口互斥（开一个必须收起另外两个）。
 * 单独成 store 是为了让 shortsStore 与 searchStore 不再互相 import —— 两边都要读写这几个开关，
 * 谁放在对方身上都会形成循环依赖。
 */
import { defineStore } from "pinia";

export const useCommentPanelStore = defineStore("commentPanel", {
  state: () => ({
    /** 短视频流右侧评论侧栏 */
    shortsCommentPanelOpen: false,
    /** 搜索全屏放大层里的评论面板 */
    searchExpandCommentOpen: false,
    /** 搜索列表内联评论（未进全屏放大层） */
    searchListCommentOpen: false,
    searchListCommentCard: null,
  }),
  actions: {
    /** 搜索列表内联评论持有的卡片副本，同样要跟随跨列表补丁 */
    patchVideoById(id, patch) {
      const card = this.searchListCommentCard;
      if (card && String(card.id) === id) {
        this.searchListCommentCard = { ...card, ...patch };
      }
    },
  },
});
