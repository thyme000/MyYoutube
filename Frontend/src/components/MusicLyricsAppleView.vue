<script setup>
import { ref, computed, watch, nextTick, onUnmounted } from "vue";
import { extractCoverPalette } from "../utils/coverPalette.js";
import { musicSearch } from "../api/musicApi.js";
import {
  playQueue,
  currentQueueKey,
  lyricsLines,
  currentSec,
  durationSec,
  playing,
  karaokeLineIndex,
  displayCover,
  titleText,
  artistText,
  volumePct,
  volumeBeforeMute,
  currentQueueIndex,
  playCloudTrackFromSearch,
  removeFromQueue,
  playQueueItem,
  playPrevInQueue,
  playNextInQueue,
  togglePlay,
  toggleMute,
  onSeekInput,
  seekToLyricLine,
  formatTime,
  seekPct,
  lyricsRenderItems,
  lineKaraokeDisplayText,
  lineSweepMaskStyle,
  lineSweepWidthPct,
} from "../stores/musicPlayerStore.js";

const lyricsScrollRef = ref(null);
const marqueeEnabled = ref(false);
const marqueeShiftPx = ref(0);
const marqueeEpoch = ref(0);

const coverPalette = ref(null);
const blurMode = ref("soft");
const frostedOn = computed(() => blurMode.value === "frosted");
const blurAmount = computed(() => (blurMode.value === "deep" || blurMode.value === "frosted" ? 72 : 40));
const shimmerOn = ref(true);
const lyricsFullscreen = ref(false);

const hubOpen = ref(false);
const hubTab = ref("cloud");
const cloudSearchQuery = ref("");
const cloudResults = ref([]);
const searchLoading = ref(false);
const cloudError = ref("");
let searchDebounce = null;

const resolvingSongId = ref("");

const volumePanelOpen = ref(false);
const volWrapRef = ref(null);

function toggleVolumePanel(ev) {
  ev?.stopPropagation?.();
  volumePanelOpen.value = !volumePanelOpen.value;
}

function onDocCloseVolume(ev) {
  if (!volumePanelOpen.value) return;
  const el = volWrapRef.value;
  if (el && !el.contains(ev.target)) {
    volumePanelOpen.value = false;
  }
}

watch(volumePanelOpen, (open) => {
  if (open) {
    nextTick(() => {
      setTimeout(() => document.addEventListener("click", onDocCloseVolume, true), 0);
    });
  } else {
    document.removeEventListener("click", onDocCloseVolume, true);
  }
});

onUnmounted(() => {
  document.removeEventListener("click", onDocCloseVolume, true);
  document.removeEventListener("keydown", onFsKeydown);
  document.body.style.overflow = "";
});

/** 避免把 Nginx/HTML 整页塞进界面 */
function sanitizeMusicError(err) {
  const raw = err?.message != null ? String(err.message) : String(err ?? "");
  if (/<\s*html[\s>]|<!DOCTYPE|<\s*head[\s>]|<title>\s*405/i.test(raw) || (raw.includes("nginx") && raw.includes("<"))) {
    return "无法连接解析服务：请确认本机 5000 端口已启动，且 Nginx 已配置 /music-svc/ 反代后执行 reload。";
  }
  return raw.replace(/<[^>]*>/g, "").trim().slice(0, 240) || "请求失败";
}

/** 无封面时：偏 Apple Music 的雾面蓝 + 暖棕 + 米白极光感 */
const auroraFallbackStyle = computed(() => ({
  background: `
    radial-gradient(ellipse 100% 80% at 78% 18%, rgba(130, 165, 220, 0.45) 0%, transparent 52%),
    radial-gradient(ellipse 90% 70% at 12% 72%, rgba(180, 120, 85, 0.38) 0%, transparent 55%),
    radial-gradient(ellipse 70% 55% at 48% 48%, rgba(245, 238, 225, 0.12) 0%, transparent 50%),
    linear-gradient(168deg, #0a0a12 0%, #12101c 45%, #0c0a10 100%)
  `,
  opacity: 1,
}));

const meshBackgroundStyle = computed(() => {
  const p = coverPalette.value;
  if (!p) {
    return { ...auroraFallbackStyle.value };
  }
  return {
    background: `
      radial-gradient(ellipse 130% 90% at 12% 88%, ${p.c3} 0%, transparent 54%),
      radial-gradient(ellipse 100% 75% at 6% 8%, ${p.c1} 0%, transparent 50%),
      radial-gradient(ellipse 115% 90% at 94% 32%, ${p.c4} 0%, transparent 54%),
      radial-gradient(ellipse 95% 65% at 52% 58%, ${p.c2} 0%, transparent 48%),
      linear-gradient(165deg, #060508 0%, #0e0c12 100%)
    `,
    opacity: 0.96,
  };
});

const shimmerStyle = computed(() => {
  const p = coverPalette.value;
  const tint = p?.shimmer || "rgba(255,255,255,0.22)";
  return {
    background: `
      linear-gradient(118deg, transparent 12%, ${tint} 38%, rgba(255,255,255,0.58) 50%, ${tint} 62%, transparent 88%),
      linear-gradient(52deg, transparent 40%, rgba(255,255,255,0.08) 50%, transparent 60%)
    `,
  };
});

const shimmerStyleB = computed(() => ({
  background: `linear-gradient(-38deg, transparent 28%, rgba(255,255,255,0.1) 48%, rgba(255,255,255,0.42) 51%, rgba(255,255,255,0.1) 54%, transparent 74%)`,
}));

const coverBgStyle = computed(() => {
  if (!displayCover.value) return {};
  return {
    backgroundImage: `url(${displayCover.value})`,
    backgroundSize: "cover",
    backgroundPosition: "center",
  };
});

async function refreshPalette() {
  const url = displayCover.value;
  if (!url) {
    coverPalette.value = null;
    return;
  }
  coverPalette.value = await extractCoverPalette(url);
}

watch(displayCover, () => void refreshPalette(), { immediate: true });

function openHub() {
  hubOpen.value = true;
}
function closeHub() {
  hubOpen.value = false;
}

async function runCloudSearch() {
  const q = cloudSearchQuery.value.trim();
  cloudError.value = "";
  cloudResults.value = [];
  if (!q) return;
  searchLoading.value = true;
  try {
    const data = await musicSearch(q, 30);
    cloudResults.value = Array.isArray(data) ? data : [];
  } catch (e) {
    cloudError.value = sanitizeMusicError(e);
  } finally {
    searchLoading.value = false;
  }
}

watch(cloudSearchQuery, () => {
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => {
    if (hubTab.value === "cloud" && cloudSearchQuery.value.trim().length >= 1) {
      void runCloudSearch();
    }
  }, 420);
});

watch(hubTab, (t) => {
  if (t === "cloud" && cloudSearchQuery.value.trim()) void runCloudSearch();
});

async function playCloudTrack(row) {
  if (!row?.id) return;
  cloudError.value = "";
  const sid = String(row.id);
  resolvingSongId.value = sid;
  await playCloudTrackFromSearch(row, { onError: (msg) => { cloudError.value = msg; } });
  resolvingSongId.value = "";
}

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

function onFsKeydown(e) {
  if (e.key === "Escape") lyricsFullscreen.value = false;
}

watch(lyricsFullscreen, (on) => {
  if (typeof document === "undefined") return;
  document.body.style.overflow = on ? "hidden" : "";
  if (on) {
    document.addEventListener("keydown", onFsKeydown);
  } else {
    document.removeEventListener("keydown", onFsKeydown);
  }
});

watch(
  () => lyricsFullscreen.value,
  () => {
    nextTick(() => requestAnimationFrame(() => refreshActiveMarquee()));
  },
);

</script>

<template>
  <!-- 全屏时整块音乐界面 Teleport 到 body，避免嵌在侧栏里被裁切、错位 -->
  <Teleport to="body" :disabled="!lyricsFullscreen">
  <div class="am-page" :class="{ 'am-page--fs': lyricsFullscreen, 'am-page--playing': playing }">
    <div class="am-bg">
      <div class="am-bg-blur" :style="{ ...coverBgStyle, filter: `saturate(1.5) blur(${blurAmount}px)` }" />
      <div class="am-bg-mesh" :style="meshBackgroundStyle" />
      <div v-if="shimmerOn" class="am-bg-shimmer am-bg-shimmer--a" :style="shimmerStyle" />
      <div v-if="shimmerOn" class="am-bg-shimmer am-bg-shimmer--b" :style="shimmerStyleB" />
      <div class="am-bg-vignette" />
    </div>

    <button type="button" class="am-hub" aria-label="音乐库" @click="openHub">
      <i class="ri-apps-2-fill" />
    </button>

    <header class="am-topbar">
      <div class="am-topbar-end">
        <button
          v-if="!lyricsFullscreen"
          type="button"
          class="am-topbar-btn"
          aria-label="全屏播放界面"
          @click="lyricsFullscreen = true"
        >
          <i class="ri-fullscreen-line" />
        </button>
        <button
          v-else
          type="button"
          class="am-topbar-btn"
          aria-label="退出全屏"
          @click="lyricsFullscreen = false"
        >
          <i class="ri-contract-up-down-line" />
        </button>
      </div>
    </header>

    <Teleport to="body">
      <div v-if="hubOpen" class="am-modal-bg" @click.self="closeHub">
        <div class="am-modal" role="dialog" aria-modal="true" @click.stop>
          <div class="am-modal-head">
            <div class="am-modal-tabs" role="tablist">
              <button type="button" role="tab" :class="{ on: hubTab === 'queue' }" @click="hubTab = 'queue'">队列</button>
              <button type="button" role="tab" :class="{ on: hubTab === 'cloud' }" @click="hubTab = 'cloud'">搜索</button>
            </div>
            <button type="button" class="am-modal-x" aria-label="关闭" @click="closeHub"><i class="ri-close-line" /></button>
          </div>

          <template v-if="hubTab === 'queue'">
            <div class="am-queue-body">
              <p v-if="!playQueue.length" class="am-queue-empty">暂无曲目，请在「搜索」里播放歌曲</p>
              <ul v-else class="am-queue-list">
                <li
                  v-for="q in playQueue"
                  :key="q.key"
                  class="am-queue-row"
                  :class="{ 'am-queue-row--on': q.key === currentQueueKey }"
                  role="button"
                  tabindex="0"
                  @click="playQueueItem(q)"
                  @keydown.enter.prevent="playQueueItem(q)"
                >
                  <img v-if="q.picUrl" class="am-queue-pic" :src="q.picUrl" alt="" />
                  <div class="am-queue-meta">
                    <span class="am-queue-title">{{ q.title }}</span>
                    <span class="am-queue-artist">{{ q.artist }}</span>
                  </div>
                  <button type="button" class="am-queue-del" aria-label="从队列移除" @click="removeFromQueue(q.key, $event)">
                    <i class="ri-close-line" />
                  </button>
                </li>
              </ul>
            </div>
          </template>

          <template v-else>
            <div class="am-cloud-pane">
            <div class="am-search">
              <i class="ri-search-line" />
              <input v-model="cloudSearchQuery" type="search" placeholder="歌手、歌曲…" autocomplete="off" />
            </div>
            <p v-if="cloudError" class="am-err">{{ cloudError }}</p>
            <div v-if="searchLoading" class="am-loading">搜索中…</div>
            <ul v-else class="am-results">
              <li
                v-for="(it, idx) in cloudResults"
                :key="it.id || idx"
                role="button"
                tabindex="0"
                class="am-result"
                :class="{ 'am-result--busy': resolvingSongId === String(it.id) }"
                @click="playCloudTrack(it)"
                @keydown.enter.prevent="playCloudTrack(it)"
              >
                <img v-if="it.picUrl" class="am-r-pic" :src="it.picUrl" alt="" @error="(e) => (e.target.style.display = 'none')" />
                <div class="am-r-meta">
                  <span class="am-r-name">{{ it.name }}</span>
                  <span class="am-r-sub">{{ it.artists }} · {{ it.album }}</span>
                </div>
                <i v-if="resolvingSongId === String(it.id)" class="am-r-spin ri-loader-4-line" />
              </li>
            </ul>
            </div>
          </template>

          <div class="am-modal-foot">
            <span class="am-foot-lab">流光</span>
            <label class="am-sw"><input v-model="shimmerOn" type="checkbox" /><i /></label>
            <span class="am-foot-lab">背景</span>
            <div class="am-seg">
              <button type="button" :class="{ on: blurMode === 'soft' }" @click="blurMode = 'soft'">柔</button>
              <button type="button" :class="{ on: blurMode === 'deep' }" @click="blurMode = 'deep'">强</button>
              <button type="button" :class="{ on: blurMode === 'frosted' }" @click="blurMode = 'frosted'">玻璃</button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <div class="am-shell" :class="{ 'am-shell--frost': frostedOn }">
      <div class="am-grid">
        <section class="am-now">
          <div class="am-art" :class="{ empty: !displayCover }" :style="displayCover ? { backgroundImage: `url(${displayCover})` } : {}">
            <i v-if="!displayCover" class="ri-music-2-line" />
          </div>
          <h1 class="am-title">{{ titleText }}</h1>
          <p class="am-artist">{{ artistText }}</p>

          <div class="am-wave" aria-hidden="true">
            <span v-for="n in 40" :key="n" class="am-wave-bar" :style="{ animationDelay: `${n * 0.04}s` }" />
          </div>

          <div class="am-scrub">
            <span class="am-scrub-t">{{ formatTime(currentSec) }}</span>
            <div class="am-scrub-track">
              <input
                class="am-scrub-range"
                type="range"
                min="0"
                max="1000"
                :value="seekPct"
                @input="onSeekInput"
              />
            </div>
            <span class="am-scrub-t">{{ formatTime(durationSec) }}</span>
          </div>

          <div ref="volWrapRef" class="am-ctrl-outer">
            <div class="am-ctrl">
              <div class="am-ctrl-left">
                <button
                  type="button"
                  class="am-ic am-ic--ghost"
                  :class="{ 'am-ic--on': volumePanelOpen }"
                  aria-label="音量"
                  :aria-expanded="volumePanelOpen"
                  @click.stop="toggleVolumePanel"
                >
                  <i
                    :class="
                      volumePct === 0
                        ? 'ri-volume-mute-fill'
                        : volumePct < 45
                          ? 'ri-volume-down-fill'
                          : 'ri-volume-up-line'
                    "
                  />
                </button>
              </div>
              <div class="am-ctrl-mid">
              <button type="button" class="am-ic am-ic--ghost" :disabled="!playQueue.length" @click="playPrevInQueue">
                <i class="ri-skip-back-mini-fill" />
              </button>
              <button type="button" class="am-play" @click="togglePlay">
                <i :class="playing ? 'ri-pause-fill' : 'ri-play-fill'" />
              </button>
              <button type="button" class="am-ic am-ic--ghost" :disabled="!playQueue.length" @click="playNextInQueue">
                <i class="ri-skip-forward-mini-fill" />
              </button>
            </div>
              <div class="am-ctrl-right">
                <button type="button" class="am-ic am-ic--ghost" @click="openHub"><i class="ri-play-list-2-line" /></button>
              </div>
            </div>
            <div
              v-show="volumePanelOpen"
              class="am-vol-strip"
              role="group"
              aria-label="音量"
              @click.stop
            >
              <div class="am-vol-track">
                <input
                  v-model.number="volumePct"
                  class="am-vol-range-h"
                  type="range"
                  min="0"
                  max="100"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="volumePct"
                  @click.stop
                />
              </div>
            </div>
          </div>
        </section>

        <section class="am-lyrics" :class="{ 'am-lyrics--fs': lyricsFullscreen }">
          <div
            ref="lyricsScrollRef"
            class="am-ly-scroll"
            :class="{ 'am-ly-scroll--compact': lyricsFullscreen }"
          >
            <p v-if="!lyricsLines.length" class="am-ly-empty">暂无歌词</p>
            <div
              v-for="{ line, idx } in lyricsRenderItems"
              :key="idx + '-' + line.timeMs"
              class="am-line"
              :class="{
                'am-line--on': idx === karaokeLineIndex,
                past: idx < karaokeLineIndex,
                fut: idx > karaokeLineIndex,
              }"
              role="button"
              tabindex="0"
              :aria-label="'跳转到 ' + formatTime(line.timeMs / 1000)"
              @click="seekToLyricLine(line)"
              @keydown.enter.prevent="seekToLyricLine(line)"
            >
              <p class="am-line-t">
                <template v-if="line.words?.length && idx === karaokeLineIndex">
                  <span
                    :key="'mk-w-' + idx + '-' + marqueeEpoch"
                    class="am-line-karaoke"
                    :class="{ 'am-line-karaoke--marquee': marqueeEnabled }"
                    :style="marqueeWrapStyle"
                  >
                    <span class="am-w-base am-line-karaoke-base">{{ lineKaraokeDisplayText(line) }}</span>
                    <span
                      class="am-w-sweep-masked"
                      aria-hidden="true"
                      :style="lineSweepMaskStyle(lineSweepWidthPct(line, idx))"
                      >{{ lineKaraokeDisplayText(line) }}</span>
                  </span>
                </template>
                <template v-else-if="line.words?.length">
                  <span v-for="(w, wi) in line.words" :key="wi" class="am-w am-w-plain">{{ w.text }}</span>
                </template>
                <template v-else-if="idx === karaokeLineIndex">
                  <span class="am-line-plain-slot">
                    <span
                      :key="'mk-p-' + idx + '-' + marqueeEpoch"
                      class="am-line-karaoke"
                      :class="{ 'am-line-karaoke--marquee': marqueeEnabled }"
                      :style="marqueeWrapStyle"
                    >
                      <span class="am-w-base am-line-karaoke-base">{{ line.text }}</span>
                      <span
                        class="am-w-sweep-masked"
                        aria-hidden="true"
                        :style="lineSweepMaskStyle(lineSweepWidthPct(line, idx))"
                        >{{ line.text }}</span>
                    </span>
                  </span>
                </template>
                <template v-else>{{ line.text }}</template>
              </p>
              <p v-if="line.translation" class="am-line-tr">{{ line.translation }}</p>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
  </Teleport>
</template>

<style scoped>
.am-page {
  --am-fg: #f5f5f7;
  --am-karaoke-sweep: #ffac5c;
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  color: var(--am-fg);
  font-family:
    system-ui,
    -apple-system,
    "SF Pro Display",
    "Inter",
    "Segoe UI",
    sans-serif;
  border-radius: 0;
  overflow: hidden;
}

/* 整块音乐页全屏：Teleport 到 body，盖住侧栏；顶栏右侧为全屏/退出 */
.am-page--fs {
  position: fixed;
  inset: 0;
  z-index: 9000;
  width: 100vw;
  max-width: 100vw;
  min-height: 100dvh;
  height: 100dvh;
  max-height: 100dvh;
  flex: none;
  overflow: hidden;
  box-sizing: border-box;
}

.am-page--fs .am-shell {
  max-width: min(1420px, 96vw);
  width: 100%;
  margin: 0 auto;
  flex: 1;
  min-height: 0;
  padding-left: max(20px, env(safe-area-inset-left, 20px), 3vw);
  padding-right: max(20px, env(safe-area-inset-right, 20px), 3vw);
  padding-top: max(52px, env(safe-area-inset-top, 0px));
  padding-bottom: max(16px, env(safe-area-inset-bottom, 12px));
  box-sizing: border-box;
}

.am-page--fs .am-grid {
  max-width: 100%;
  margin: 0 auto;
  grid-template-columns: minmax(260px, 1fr) minmax(260px, 1fr);
  gap: clamp(36px, 7vw, 96px);
  align-items: center;
  justify-items: stretch;
}

/* 全屏：略向中间缝靠拢；整体放大左右两栏（封面区 + 歌词） */
.am-page--fs .am-now {
  justify-self: end;
  width: 100%;
  max-width: min(500px, 100%);
  padding-bottom: 24px;
}

.am-page--fs .am-art {
  max-width: min(440px, 100%);
  border-radius: 16px;
  margin: 0 auto 22px;
}

.am-page--fs .am-title {
  font-size: clamp(1.35rem, 2.9vw, 1.85rem);
}

.am-page--fs .am-artist {
  font-size: 1.05rem;
}

.am-page--fs .am-wave {
  height: 40px;
  margin: 22px 0 18px;
}

.am-page--fs .am-scrub {
  font-size: 12px;
  margin-bottom: 22px;
}

.am-page--fs .am-ctrl-outer {
  max-width: 460px;
}

.am-page--fs .am-ctrl {
  max-width: none;
}

.am-page--fs .am-play {
  width: 64px;
  height: 64px;
  font-size: 32px;
}

.am-page--fs .am-ic {
  width: 44px;
  height: 44px;
  font-size: 21px;
}

.am-page--fs .am-lyrics {
  justify-self: start;
  margin-left: 0;
  margin-right: 0;
  max-width: min(44rem, 100%);
  padding-left: clamp(8px, 1.5vw, 20px);
  padding-right: clamp(8px, 1.5vw, 20px);
}

.am-page--fs .am-line-t {
  font-size: clamp(1.28rem, 2.8vw, 1.9rem);
}

.am-page--fs .am-line--on .am-line-t {
  font-size: clamp(1.65rem, 3.8vw, 2.45rem);
}

.am-page--fs .am-line-tr {
  font-size: 1rem;
}

.am-page--fs .am-line--on .am-line-tr {
  font-size: 1.08rem;
}

.am-topbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 12;
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  padding: max(10px, env(safe-area-inset-top, 10px)) max(12px, env(safe-area-inset-right, 12px)) 0;
  padding-left: max(56px, env(safe-area-inset-left, 12px));
  pointer-events: none;
  box-sizing: border-box;
}

.am-topbar-end {
  pointer-events: auto;
}

.am-topbar-btn {
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 12px;
  background: transparent;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  color: rgba(255, 255, 255, 0.88);
  font-size: 22px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: none;
}

.am-topbar-btn:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.am-ly-scroll--compact {
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  max-height: min(46vh, 400px);
  padding-bottom: 16px;
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    #000 12%,
    #000 88%,
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    180deg,
    transparent 0%,
    #000 12%,
    #000 88%,
    transparent 100%
  );
}

/* 全屏：完整歌词可滚轮查看；覆盖 compact 的 overflow:hidden + 居中 flex */
.am-page--fs .am-ly-scroll--compact {
  display: block;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
  max-height: min(62vh, 600px);
  min-height: min(36vh, 320px);
  padding-top: min(22vh, 180px);
  padding-bottom: min(22vh, 180px);
  padding-left: 2px;
  padding-right: 4px;
  scroll-padding-block: min(20vh, 160px);
  justify-content: unset;
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    #000 4%,
    #000 96%,
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    180deg,
    transparent 0%,
    #000 4%,
    #000 96%,
    transparent 100%
  );
}

.am-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.am-bg-blur {
  position: absolute;
  inset: -18%;
  transform: scale(1.12);
  opacity: 0.72;
  transition: filter 0.9s ease;
}

.am-bg-mesh {
  position: absolute;
  inset: 0;
  transition: background 1s ease;
}

.am-bg-shimmer {
  position: absolute;
  inset: -55%;
  pointer-events: none;
}

.am-bg-shimmer--a {
  opacity: 0.92;
  mix-blend-mode: overlay;
  animation: am-sh 4s ease-in-out infinite;
  filter: saturate(1.35) brightness(1.08);
}

.am-bg-shimmer--b {
  opacity: 0.72;
  mix-blend-mode: soft-light;
  animation: am-sh2 6.8s ease-in-out infinite;
  filter: saturate(1.2);
}

@keyframes am-sh {
  0% {
    transform: translate(-18%, -12%) rotate(10deg) scale(1.02);
  }
  50% {
    transform: translate(20%, 14%) rotate(10deg) scale(1.05);
  }
  100% {
    transform: translate(-18%, -12%) rotate(10deg) scale(1.02);
  }
}

@keyframes am-sh2 {
  0% {
    transform: translate(8%, 18%) rotate(-6deg) scale(1.03);
  }
  50% {
    transform: translate(-16%, -10%) rotate(-6deg) scale(1.08);
  }
  100% {
    transform: translate(8%, 18%) rotate(-6deg) scale(1.03);
  }
}

.am-bg-vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse 130% 90% at 50% 20%, transparent 0%, rgba(0, 0, 0, 0.18) 50%, rgba(0, 0, 0, 0.55) 100%);
  pointer-events: none;
}

.am-hub {
  position: absolute;
  left: 12px;
  top: 12px;
  z-index: 5;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  border: none;
  background: transparent;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  color: rgba(255, 255, 255, 0.88);
  font-size: 22px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: none;
}

.am-hub:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.am-modal-bg {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(6, 4, 12, 0.45);
  -webkit-backdrop-filter: blur(18px) saturate(1.25);
  backdrop-filter: blur(18px) saturate(1.25);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(12px, env(safe-area-inset-top));
}

.am-modal {
  width: min(480px, 96vw);
  max-height: min(76vh, 640px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  background: linear-gradient(
    155deg,
    rgba(255, 255, 255, 0.14) 0%,
    rgba(40, 36, 58, 0.38) 42%,
    rgba(14, 12, 24, 0.42) 100%
  );
  -webkit-backdrop-filter: blur(36px) saturate(1.85);
  backdrop-filter: blur(36px) saturate(1.85);
  padding: 0;
  color: #eee;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.1) inset,
    0 28px 72px rgba(0, 0, 0, 0.5),
    0 12px 40px rgba(0, 0, 0, 0.28);
}

.am-modal-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 10px 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.am-modal-tabs {
  flex: 1;
  display: inline-flex;
  padding: 3px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.22);
  border: 1px solid rgba(255, 255, 255, 0.14);
  gap: 0;
  -webkit-backdrop-filter: blur(14px) saturate(1.3);
  backdrop-filter: blur(14px) saturate(1.3);
}

.am-modal-tabs button {
  flex: 1;
  padding: 6px 12px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: rgba(255, 255, 255, 0.45);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease;
}

.am-modal-tabs button.on {
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  box-shadow: 0 1px 10px rgba(0, 0, 0, 0.22);
}

.am-modal-x {
  width: 32px;
  height: 32px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.1);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.am-modal-x:hover {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}

.am-queue-body {
  flex: 1;
  min-height: 0;
  padding: 8px 10px 10px;
  overflow-y: auto;
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.am-queue-body::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.am-queue-empty {
  margin: 0;
  padding: 20px 12px;
  text-align: center;
  font-size: 12px;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.42);
}

.am-queue-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.am-queue-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 8px 10px;
  margin-bottom: 4px;
  border-radius: 12px;
  cursor: pointer;
  border: 1px solid transparent;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.am-queue-row:hover {
  background: rgba(255, 255, 255, 0.06);
}

.am-queue-row--on {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.12);
}

.am-queue-pic {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.am-queue-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.am-queue-title {
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-queue-artist {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-queue-del {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.55);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}

.am-queue-del:hover {
  background: rgba(248, 113, 113, 0.35);
  color: #fff;
}

.am-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin: 8px 10px 8px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.16);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  backdrop-filter: blur(16px) saturate(1.4);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.06) inset;
}

.am-search input {
  flex: 1;
  border: none;
  background: transparent;
  color: #fff;
  font-size: 14px;
  outline: none;
}

.am-cloud-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.am-err {
  color: #fca5a5;
  font-size: 11px;
  line-height: 1.45;
  margin: 0 10px 6px;
  word-break: break-word;
}

.am-loading {
  font-size: 12px;
  opacity: 0.55;
  padding: 6px 10px 8px;
}

.am-results {
  list-style: none;
  margin: 0 8px 6px;
  padding: 0 2px 0 0;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.am-results::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.am-result {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 6px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.am-result--busy {
  pointer-events: none;
  opacity: 0.72;
}

.am-result:hover {
  background: rgba(255, 255, 255, 0.08);
}

.am-r-pic {
  width: 36px;
  height: 36px;
  border-radius: 7px;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.06);
}

.am-r-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.am-r-name {
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-r-sub {
  font-size: 11px;
  opacity: 0.55;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-r-spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.am-modal-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 10px;
  margin-top: auto;
  background: rgba(0, 0, 0, 0.18);
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  -webkit-backdrop-filter: blur(14px) saturate(1.3);
  backdrop-filter: blur(14px) saturate(1.3);
  font-size: 11px;
  flex-shrink: 0;
}

.am-foot-lab {
  opacity: 0.5;
}

.am-sw {
  position: relative;
  width: 38px;
  height: 20px;
}

.am-sw input {
  opacity: 0;
  width: 0;
  height: 0;
}

.am-sw i {
  position: absolute;
  inset: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  transition: 0.25s;
}

.am-sw i::after {
  content: "";
  position: absolute;
  width: 14px;
  height: 14px;
  left: 3px;
  top: 3px;
  background: #fff;
  border-radius: 50%;
  transition: 0.25s;
}

.am-sw input:checked + i {
  background: rgba(99, 102, 241, 0.7);
}

.am-sw input:checked + i::after {
  transform: translateX(16px);
}

.am-seg {
  display: inline-flex;
  gap: 4px;
}

.am-seg button {
  border: none;
  padding: 4px 9px;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.45);
  cursor: pointer;
  font-size: 12px;
}

.am-seg button.on {
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
}

.am-done {
  margin-left: auto;
  border: none;
  padding: 8px 16px;
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-weight: 650;
  cursor: pointer;
}

.am-shell {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 52px clamp(16px, 3vw, 40px) 20px;
  box-sizing: border-box;
  overflow: visible;
  max-width: 1440px;
  margin: 0 auto;
  width: 100%;
}

/* 仅在设置里选「玻璃」时出现磨砂与描边；默认无框、无玻璃底 */
.am-shell--frost .am-now {
  border-radius: 20px;
}

.am-shell--frost .am-lyrics {
  border-radius: 20px;
}

.am-shell--frost .am-now,
.am-shell--frost .am-lyrics {
  background: rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(48px) saturate(1.65);
  -webkit-backdrop-filter: blur(48px) saturate(1.65);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.am-grid {
  display: grid;
  grid-template-columns: minmax(260px, 42%) minmax(0, 58%);
  gap: clamp(16px, 2.5vw, 40px);
  align-items: center;
  width: 100%;
  max-width: none;
  margin: 0;
  flex: 1;
  min-height: 0;
}

@media (max-width: 960px) {
  .am-grid {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .am-shell {
    padding-left: 12px;
    padding-right: 8px;
    padding-top: 48px;
  }
  .am-now {
    align-self: start;
    justify-self: stretch;
  }
  .am-lyrics {
    max-width: none;
    margin: 0 auto;
    justify-self: stretch;
    padding-left: 16px;
    padding-right: 16px;
  }

  .am-page--fs .am-shell {
    max-width: 100%;
    padding-left: max(20px, env(safe-area-inset-left, 20px), 4vw);
    padding-right: max(20px, env(safe-area-inset-right, 20px), 4vw);
  }

  .am-page--fs .am-grid {
    grid-template-columns: 1fr;
    gap: 28px;
    justify-items: stretch;
  }

  .am-page--fs .am-now {
    justify-self: center;
    max-width: min(400px, 100%);
  }

  .am-page--fs .am-lyrics {
    justify-self: center;
    max-width: min(36rem, 100%);
    margin-left: auto;
    margin-right: auto;
  }
}

.am-now {
  padding: 8px 12px 20px;
  border-radius: 20px;
  min-height: 0;
  align-self: center;
  justify-self: stretch;
  position: relative;
  z-index: 4;
  overflow: visible;
  width: 100%;
  max-width: 420px;
}

.am-art {
  width: 100%;
  max-width: min(360px, 100%);
  margin: 0 auto 20px;
  aspect-ratio: 1;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
  background-size: cover;
  background-position: center;
  box-shadow:
    0 24px 64px rgba(0, 0, 0, 0.45),
    0 0 0 1px rgba(255, 255, 255, 0.08) inset;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 72px;
  color: rgba(255, 255, 255, 0.18);
}

.am-art.empty {
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.am-title {
  margin: 0;
  text-align: center;
  font-size: clamp(1.15rem, 2.2vw, 1.45rem);
  font-weight: 650;
  letter-spacing: -0.028em;
  line-height: 1.2;
  color: #fff;
}

.am-artist {
  margin: 8px 0 0;
  text-align: center;
  font-size: 0.95rem;
  font-weight: 450;
  letter-spacing: 0.01em;
  color: rgba(255, 255, 255, 0.72);
}

.am-wave {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
  height: 36px;
  margin: 20px 0 16px;
  opacity: 0.92;
}

.am-wave-bar {
  width: 2px;
  height: 6px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.92);
  animation: am-wv 0.85s ease-in-out infinite alternate;
}

.am-page:not(.am-page--playing) .am-wave-bar {
  animation-play-state: paused;
  opacity: 0.3;
  transform: scaleY(0.35);
}

@keyframes am-wv {
  from {
    transform: scaleY(0.35);
    opacity: 0.4;
  }
  to {
    transform: scaleY(1.75);
    opacity: 1;
  }
}

.am-scrub {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: rgba(255, 255, 255, 0.55);
  margin-bottom: 20px;
}

.am-scrub-t {
  min-width: 2.5rem;
  text-align: center;
}

.am-scrub-track {
  flex: 1;
  min-width: 0;
  height: 10px;
  display: flex;
  align-items: center;
}

.am-scrub-range {
  width: 100%;
  height: 10px;
  margin: 0;
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: none;
  outline: none;
  box-shadow: none;
  -webkit-appearance: none;
  appearance: none;
  color: transparent;
}

.am-scrub-range:focus {
  outline: none;
}

.am-scrub-range::-webkit-slider-runnable-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
  box-shadow: none;
}

.am-scrub-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 10px;
  height: 10px;
  margin-top: -4px;
  border: none;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: grab;
}

.am-scrub-range::-moz-range-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
}

.am-scrub-range::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: grab;
}

.am-scrub-range::-moz-range-progress {
  height: 2px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.55);
}

.am-scrub-range::-ms-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
  color: transparent;
}

.am-scrub-range::-ms-fill-lower {
  background: rgba(255, 255, 255, 0.55);
  border-radius: 2px;
}

.am-scrub-range::-ms-fill-upper {
  background: rgba(255, 255, 255, 0.28);
  border-radius: 2px;
}

.am-scrub-range::-ms-thumb {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  background: #fff;
  cursor: grab;
}

.am-ctrl {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 4px;
}

.am-ctrl-left,
.am-ctrl-mid,
.am-ctrl-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.am-ctrl-mid {
  gap: 10px;
}

.am-ctrl-outer {
  width: 100%;
  max-width: 400px;
  margin: 0 auto;
}

.am-vol-strip {
  margin-top: 10px;
  padding: 0 2px 2px;
  animation: am-vol-in 0.18s ease;
}

@keyframes am-vol-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.am-vol-track {
  flex: 1;
  min-width: 0;
  height: 10px;
  display: flex;
  align-items: center;
}

.am-vol-range-h {
  width: 100%;
  height: 10px;
  margin: 0;
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: none;
  outline: none;
  box-shadow: none;
  -webkit-appearance: none;
  appearance: none;
  color: transparent;
}

.am-vol-range-h:focus {
  outline: none;
}

.am-vol-range-h::-webkit-slider-runnable-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
  box-shadow: none;
}

.am-vol-range-h::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 10px;
  height: 10px;
  margin-top: -4px;
  border: none;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: grab;
}

.am-vol-range-h::-moz-range-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
}

.am-vol-range-h::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: grab;
}

.am-vol-range-h::-moz-range-progress {
  height: 2px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.55);
}

.am-vol-range-h::-ms-track {
  height: 2px;
  border: none;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.28);
  color: transparent;
}

.am-vol-range-h::-ms-fill-lower {
  background: rgba(255, 255, 255, 0.55);
  border-radius: 2px;
}

.am-vol-range-h::-ms-fill-upper {
  background: rgba(255, 255, 255, 0.28);
  border-radius: 2px;
}

.am-vol-range-h::-ms-thumb {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  background: #fff;
  cursor: grab;
}

.am-ic {
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 19px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.2s ease,
    transform 0.15s ease;
}

/* 除播放键外：辅助控制为扁平图标，无圆形描边（全屏/嵌入、播放前后一致） */
.am-ic--ghost {
  border-radius: 10px;
  background: transparent;
  border: none;
  box-shadow: none;
  color: rgba(255, 255, 255, 0.92);
}

.am-ic--ghost:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  border: none;
}

.am-ic--ghost.am-ic--on {
  background: rgba(255, 255, 255, 0.14);
  box-shadow: none;
  border: none;
}

.am-ic:disabled {
  opacity: 0.32;
  cursor: default;
}

.am-play {
  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;
  background: #fff;
  color: #0a0a0c;
  font-size: 28px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
  transition: transform 0.15s ease;
}

.am-play:hover {
  transform: scale(1.04);
}

.am-lyrics {
  width: 100%;
  max-width: min(38rem, 100%);
  margin: 0 0 0 auto;
  padding: 12px clamp(16px, 4vw, 48px) 18px clamp(24px, 5vw, 64px);
  border-radius: 0;
  min-height: 0;
  max-height: min(78vh, 780px);
  height: auto;
  align-self: center;
  justify-self: end;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  background: transparent;
  border: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.am-ly-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  /* 上下留白对称，任意一行都能滚到视区几何中心，避免高亮行贴在下半屏 */
  padding: min(26vh, 200px) 4px min(26vh, 200px) 2px;
  scroll-padding-block: min(24vh, 180px);
  mask-image: linear-gradient(to bottom, transparent, #000 5%, #000 92%, transparent);
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.am-ly-scroll::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.am-ly-empty {
  margin: 0;
  opacity: 0.45;
  font-size: 15px;
}

.am-line {
  margin: 0 0 clamp(1.25rem, 3vh, 1.75rem);
  max-width: 36rem;
  text-align: left;
  cursor: pointer;
  transition:
    opacity 0.45s ease,
    filter 0.45s ease,
    transform 0.45s ease;
}

.am-line:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.4);
  outline-offset: 3px;
  border-radius: 6px;
}

.am-line-t {
  margin: 0;
  font-size: clamp(1.15rem, 2.4vw, 1.65rem);
  font-weight: 600;
  line-height: 1.42;
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-line-tr {
  margin: 8px 0 0;
  font-size: 0.92rem;
  font-weight: 450;
  opacity: 0.5;
  line-height: 1.5;
  letter-spacing: 0.02em;
}

/* 已播 / 未播：略淡 + 轻模糊，嵌入与全屏一致，保证可读 */
.am-line.past {
  opacity: 0.38;
  filter: blur(1px);
  transform: none;
}

.am-line.fut {
  opacity: 0.48;
  filter: blur(0.5px);
  transform: none;
}

.am-line--on {
  opacity: 1;
  filter: none;
  transform: none;
}

.am-line--on .am-line-t {
  font-size: clamp(1.45rem, 3.2vw, 2.05rem);
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.035em;
  line-height: 1.28;
  text-shadow: 0 2px 24px rgba(0, 0, 0, 0.25);
}

.am-line--on .am-line-tr {
  font-size: 1rem;
  opacity: 0.75;
  filter: none;
}

/* 扫光：底层灰字 + 全宽叠层 + mask 渐变（与 clip 窄盒不同宽，避免英文换行重影） */
.am-w-base {
  color: rgba(255, 255, 255, 0.34);
  visibility: visible;
}

.am-line--on .am-w-base {
  color: rgba(255, 255, 255, 0.4);
}

.am-w-base,
.am-w-sweep-masked {
  font: inherit;
  letter-spacing: inherit;
  line-height: inherit;
  text-shadow: inherit;
}

.am-w-sweep-masked {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  display: block;
  box-sizing: border-box;
  white-space: nowrap;
  color: var(--am-karaoke-sweep);
  pointer-events: none;
  transform: translateZ(0);
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-size: 100% 100%;
  mask-size: 100% 100%;
}

/* 整行合并逐字：一条扫光从左向右连续推进（可穿过单字中部） */
.am-line-karaoke {
  position: relative;
  display: block;
  width: 100%;
  overflow: hidden;
}

.am-line-karaoke-base {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.am-line-karaoke--marquee .am-line-karaoke-base,
.am-line-karaoke--marquee .am-w-sweep-masked {
  will-change: transform;
  display: inline-block;
  width: max-content;
  min-width: 100%;
  transform: translateX(calc(-1 * var(--am-marquee-x)));
}

.am-line-karaoke--marquee .am-line-karaoke-base {
  overflow: visible;
  text-overflow: clip;
}

.am-line-karaoke--marquee .am-w-sweep-masked {
  width: max-content;
}

.am-w.am-w-plain {
  margin-right: 0.28em;
}

.am-line-plain-slot {
  display: block;
  width: 100%;
}
</style>
