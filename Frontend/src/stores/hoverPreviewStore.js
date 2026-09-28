/**
 * 卡片悬停预览：历史/赞过/首页等列表共用。
 * 悬停 500ms 后开始预览；滚动或移出即停止。
 */
import { defineStore } from "pinia";
import { resolveVideoUrl } from "../utils/videoCard.js";

export const useHoverPreviewStore = defineStore("hoverPreview", {
  state: () => ({
    hoveringId: "",
    activeId: "",
    videoUrl: "",
  }),
  actions: {
    enter(card, listKey = "") {
      const url = resolveVideoUrl(card);
      if (!url) return;
      const key = cardPreviewKey(card, listKey);
      clearTimer();
      this.hoveringId = key;
      hoverTimer = setTimeout(() => {
        if (this.hoveringId !== key) return;
        this.activeId = key;
        this.videoUrl = url;
      }, 500);
    },
    leave(card, listKey = "") {
      const key = cardPreviewKey(card, listKey);
      if (this.hoveringId === key) {
        this.hoveringId = "";
      }
      if (this.activeId === key) {
        this.stop();
      } else {
        clearTimer();
      }
    },
    stop() {
      clearTimer();
      this.hoveringId = "";
      this.activeId = "";
      this.videoUrl = "";
    },
  },
});

let hoverTimer = null;

function clearTimer() {
  if (hoverTimer) {
    clearTimeout(hoverTimer);
    hoverTimer = null;
  }
}

export function cardPreviewKey(card, listKey = "") {
  return `${listKey}:${String(card?.id ?? "")}`;
}
