// @ts-check
/**
 * 播放行为上报（PLAY / FINISH / LIKE / DISLIKE…）。
 * PLAY 按内容去重，只上报一次；去重集合跨详情页、短视频、搜索全屏、动态详情层共用。
 * 本 store 只依赖 authStore，供各域 store 直接 import。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { reportContentBehavior } from "../api/behaviorApi";
import { parseDigitSnowflakeId } from "../utils/snowflakeId.js";
import { useAuthStore } from "./authStore";

function formatWatchTime(date) {
  const pad = (v) => String(v).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export const useBehaviorStore = defineStore("behavior", () => {
  const reportedPlayIds = ref(new Set());

  function hasPlayReported(contentId) {
    return reportedPlayIds.value.has(String(contentId ?? ""));
  }

  function markPlayReported(contentId) {
    const key = String(contentId ?? "");
    if (!key) return;
    const next = new Set(reportedPlayIds.value);
    next.add(key);
    reportedPlayIds.value = next;
  }

  async function report(contentIdLike, event, watchDurationMs = 0) {
    if (!useAuthStore().token) return;
    const contentId = parseDigitSnowflakeId(contentIdLike);
    if (!contentId) return;
    try {
      await reportContentBehavior({
        contentId,
        event,
        watchTime: formatWatchTime(new Date()),
        watchDuration: Math.max(0, Math.floor(Number(watchDurationMs) || 0)),
      });
    } catch {
      // 静默上报，不打断用户体验
    }
  }

  return {
    reportedPlayIds,
    hasPlayReported,
    markPlayReported,
    report,
  };
});
