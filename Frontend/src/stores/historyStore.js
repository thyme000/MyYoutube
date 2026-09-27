/**
 * 历史播放记录域状态：长视频与短视频两个列表的分页数据。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 HistoryView 时随之整体搬出。
 */
import { defineStore } from "pinia";

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
});
