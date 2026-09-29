import { ref, computed, watch, nextTick } from "vue";
import { lyricsLines, karaokeLineIndex, lineSweepWidthPct } from "../stores/musicPlayerStore.js";

/** 歌词跑马灯：高亮行居中滚动、溢出测量与随演唱进度推进的位移 */
export function useLyricsMarquee() {
  const lyricsScrollRef = ref(null);
  const marqueeEnabled = ref(false);
  const marqueeShiftPx = ref(0);
  const marqueeEpoch = ref(0);

  /** 将当前高亮行滚到略偏上（相对「几何中心」上移约一行间距，更符合阅读习惯） */
  function scrollActiveLyricToCenter() {
    const wrap = lyricsScrollRef.value;
    const el = wrap?.querySelector(".am-line--on");
    if (!wrap || !el || karaokeLineIndex.value < 0) return;
    const elTop = el.getBoundingClientRect().top - wrap.getBoundingClientRect().top + wrap.scrollTop;
    let oneLineUp = 52;
    const prev = el.previousElementSibling;
    if (prev?.classList?.contains?.("am-line")) {
      const pr = prev.getBoundingClientRect();
      const er = el.getBoundingClientRect();
      const gap = er.top + er.height / 2 - (pr.top + pr.height / 2);
      if (gap > 12 && gap < 200) oneLineUp = gap;
    }
    const desired = elTop + el.offsetHeight / 2 - wrap.clientHeight / 2 + oneLineUp;
    const maxScroll = Math.max(0, wrap.scrollHeight - wrap.clientHeight);
    wrap.scrollTo({
      top: Math.max(0, Math.min(desired, maxScroll)),
      behavior: "smooth",
    });
  }

  function refreshActiveMarquee() {
    const wrap = lyricsScrollRef.value;
    const lineTextEl = wrap?.querySelector(".am-line--on .am-line-t");
    const baseEl = wrap?.querySelector(".am-line--on .am-line-karaoke-base");
    if (!lineTextEl || !baseEl) {
      marqueeEnabled.value = false;
      marqueeShiftPx.value = 0;
      return;
    }
    const containerW = lineTextEl.clientWidth || 0;
    const textW = baseEl.scrollWidth || 0;
    const overflow = Math.max(0, textW - containerW);
    if (overflow <= 8) {
      marqueeEnabled.value = false;
      marqueeShiftPx.value = 0;
      return;
    }
    marqueeEnabled.value = true;
    marqueeShiftPx.value = overflow + 20;
  }

  const marqueeWrapStyle = computed(() => ({
    "--am-marquee-x": `${(() => {
      const i = karaokeLineIndex.value;
      const line = i >= 0 && i < lyricsLines.value.length ? lyricsLines.value[i] : null;
      if (!line) return 0;
      const p = lineSweepWidthPct(line, i);
      // 跑马灯位移放缓：前段基本不动，中后段再推进，避免“未唱完先滚完”
      const eased = Math.max(0, (p - 28) / 72);
      return Math.max(0, marqueeShiftPx.value * 0.82 * Math.pow(eased, 1.7));
    })()}px`,
  }));

  watch(karaokeLineIndex, () => {
    marqueeEpoch.value += 1;
    nextTick(() => {
      requestAnimationFrame(() => {
        scrollActiveLyricToCenter();
        refreshActiveMarquee();
      });
    });
  });

  watch(lyricsLines, () => {
    nextTick(() => requestAnimationFrame(() => refreshActiveMarquee()));
  });

  return {
    lyricsScrollRef,
    marqueeEnabled,
    marqueeShiftPx,
    marqueeEpoch,
    scrollActiveLyricToCenter,
    refreshActiveMarquee,
    marqueeWrapStyle,
  };
}
