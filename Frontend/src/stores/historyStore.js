// @ts-check
/**
 * 观看历史域状态：content 级混排历史(视频/动态同池)的分页数据 + 满阈值写入入口。
 * 各播放域(detail/immersive/search/动态详情层)在累计播放过阈值时调用 recordViewed,
 * 本 store 按会话去重, 一条内容一次会话只写一次。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import {
  recordViewHistory,
  fetchHistoryPage,
  deleteHistoryItem,
  clearMyHistory,
} from "../api/historyApi";
import { useAuthStore } from "./authStore";
import { useImmersiveStore } from "./immersiveStore";

/** 满阈值写历史: 视频与沉浸流动态统一按累计播放 3 秒计(动态详情层按翻到第 2 张单独判) */
export const HISTORY_MIN_PLAY_SEC = 3;

export const useHistoryStore = defineStore("history", () => {
  const historyRows = ref([]);
  const historyLoading = ref(false);
  const historyPageNo = ref(1);
  const historyPages = ref(0);
  const historyExpanded = ref(false);

  /** 会话内已写历史的 content id, 防重复请求 */
  const recordedIds = ref(new Set());

  /**
   * 满阈值写入历史(静默, 失败不打断播放)
   * @param {string|number} contentIdLike
   */
  async function recordViewed(contentIdLike) {
    if (!useAuthStore().token) return;
    const key = String(contentIdLike ?? "");
    if (!key || recordedIds.value.has(key)) return;
    const next = new Set(recordedIds.value);
    next.add(key);
    recordedIds.value = next;
    try {
      await recordViewHistory(key);
    } catch {
      /* 静默失败: 历史缺失可接受, 不重试打扰 */
    }
  }

  async function loadPage({ reset = false } = {}) {
    const auth = useAuthStore();
    if (!auth.isAuthed) return;
    if (historyLoading.value) return;
    historyLoading.value = true;
    try {
      const targetPage = reset ? 1 : historyPageNo.value;
      const pageSize = historyExpanded.value ? 9 : 3;
      const res = await fetchHistoryPage({ pageNo: targetPage, pageSize });
      const nextList = Array.isArray(res.list) ? res.list : [];
      historyRows.value = nextList;
      // 空页即到底: 不信任 pages 提示, 防止分页按钮无限翻
      historyPages.value = nextList.length ? Number(res.pages || 0) : targetPage;
      if (reset) {
        historyPageNo.value = 1;
      }
    } catch {
      historyRows.value = [];
      historyPageNo.value = 1;
      historyPages.value = 0;
    }
    historyLoading.value = false;
  }

  async function toggleExpanded() {
    historyExpanded.value = !historyExpanded.value;
    historyPageNo.value = 1;
    await loadPage({ reset: true });
  }

  async function prevPage() {
    if (historyPageNo.value <= 1 || historyLoading.value) return;
    historyPageNo.value -= 1;
    await loadPage();
  }

  async function nextPage() {
    if (historyPageNo.value >= (historyPages.value || 1) || historyLoading.value) return;
    historyPageNo.value += 1;
    await loadPage();
  }

  /** 视频卡进全屏连播(动态卡由视图层路由到详情层) */
  function openCard(card, index) {
    useImmersiveStore().openProfileFeed(historyRows.value, index);
  }

  async function removeOne(id) {
    await deleteHistoryItem(id);
    historyRows.value = historyRows.value.filter((row) => String(row.id) !== String(id));
    // 尾页删空后回退一页
    if (!historyRows.value.length && historyPageNo.value > 1) {
      historyPageNo.value -= 1;
      await loadPage();
    }
  }

  async function clearAll() {
    await clearMyHistory();
    historyRows.value = [];
    historyPageNo.value = 1;
    historyPages.value = 0;
  }

  return {
    historyRows,
    historyLoading,
    historyPageNo,
    historyPages,
    historyExpanded,
    recordViewed,
    loadPage,
    toggleExpanded,
    prevPage,
    nextPage,
    openCard,
    removeOne,
    clearAll,
  };
});
