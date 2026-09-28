// @ts-check
/**
 * 播放行为上报（PLAY / FINISH / LIKE / DISLIKE…）与短视频观看历史批量上报。
 * PLAY 按视频去重，累计顺播达阈值只上报一次；去重集合跨详情页、短视频、搜索全屏共用。
 * 观看历史队列不参与渲染，故用模块级变量而非 state；本 store 只依赖 authStore，供各域 store 直接 import。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { reportVideoBehavior } from "../api/behaviorApi";
import { reportShortHistoryBatch } from "../api/videoApi";
import { getToken } from "../api/http";
import { parseDigitSnowflakeId, safeSnowflakeId } from "../utils/snowflakeId.js";
import { isLongVideoItem } from "../utils/videoCard.js";
import { useAuthStore } from "./authStore";

const SHORTS_HISTORY_BATCH_SIZE = 10;

let shortsHistoryQueue = [];
const shortsHistoryQueueSet = new Set();
const shortsHistorySeenSet = new Set();
let shortsHistoryFlushInFlight = false;

function formatWatchTime(date) {
  const pad = (v) => String(v).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export const useBehaviorStore = defineStore("behavior", () => {
  const reportedPlayIds = ref(new Set());

  function hasPlayReported(videoId) {
    return reportedPlayIds.value.has(String(videoId ?? ""));
  }

  function markPlayReported(videoId) {
    const key = String(videoId ?? "");
    if (!key) return;
    const next = new Set(reportedPlayIds.value);
    next.add(key);
    reportedPlayIds.value = next;
  }

  async function report(videoIdLike, event, watchDurationMs = 0) {
    if (!useAuthStore().token) return;
    const videoId = parseDigitSnowflakeId(videoIdLike);
    if (!videoId) return;
    try {
      await reportVideoBehavior({
        videoId,
        event,
        watchTime: formatWatchTime(new Date()),
        watchDuration: Math.max(0, Math.floor(Number(watchDurationMs) || 0)),
      });
    } catch {
      // 静默上报，不打断用户体验
    }
  }

  /** 仅短视频写入 /videos/shorts/play；长视频在竖屏流里走续播，不得混入 shortIds */
  function enqueueShortsHistory(videoIdLike, feedItem) {
    if (!useAuthStore().isAuthed) return;
    if (feedItem == null || isLongVideoItem(feedItem)) return;
    const videoId = safeSnowflakeId(videoIdLike, "");
    if (!videoId) return;
    if (shortsHistorySeenSet.has(videoId) || shortsHistoryQueueSet.has(videoId)) return;
    shortsHistoryQueueSet.add(videoId);
    shortsHistoryQueue.push(videoId);
    if (shortsHistoryQueue.length >= SHORTS_HISTORY_BATCH_SIZE) {
      void flushShortsHistory();
    }
  }

  async function sendShortsHistoryBatch(videoIds, { keepalive = false } = {}) {
    if (!Array.isArray(videoIds) || !videoIds.length) return;
    const shortIds = videoIds
      .map((id) => safeSnowflakeId(id))
      .filter((id) => id !== "" && id != null);
    if (!shortIds.length) return;
    const payload = {
      userId: useAuthStore().userId || undefined,
      shortIds,
    };
    if (payload.userId != null && payload.userId !== "") {
      payload.userId = safeSnowflakeId(payload.userId);
    }
    if (keepalive) {
      // 页面卸载期间普通请求会被中断，只能走 fetch keepalive 自带鉴权头
      const headers = { "Content-Type": "application/json" };
      const authTok = getToken();
      if (authTok) {
        headers.Authorization = authTok;
      }
      await fetch("/videos/shorts/play", {
        method: "PUT",
        headers,
        body: JSON.stringify(payload),
        keepalive: true,
      });
      return;
    }
    await reportShortHistoryBatch(payload);
  }

  async function flushShortsHistory({ keepalive = false } = {}) {
    if (!shortsHistoryQueue.length || shortsHistoryFlushInFlight) return;
    shortsHistoryFlushInFlight = true;
    const batch = [...shortsHistoryQueue];
    shortsHistoryQueue = [];
    shortsHistoryQueueSet.clear();
    try {
      await sendShortsHistoryBatch(batch, { keepalive });
      batch.forEach((id) => shortsHistorySeenSet.add(id));
    } catch {
      batch.forEach((id) => {
        if (!shortsHistoryQueueSet.has(id)) {
          shortsHistoryQueueSet.add(id);
          shortsHistoryQueue.push(id);
        }
      });
    } finally {
      shortsHistoryFlushInFlight = false;
    }
  }

  /** 退出登录：先把待发队列冲掉（此时仍带旧 token），再清空去重集，避免下次登录重复上报 */
  function resetShortsHistory() {
    void flushShortsHistory();
    shortsHistoryQueue = [];
    shortsHistoryQueueSet.clear();
    shortsHistorySeenSet.clear();
  }

  return {
    reportedPlayIds,
    hasPlayReported,
    markPlayReported,
    report,
    enqueueShortsHistory,
    flushShortsHistory,
    resetShortsHistory,
  };
});
