<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from "vue";
import {
  playQueue,
  currentQueueKey,
  remoteAudioUrl,
  displayCover,
  playing,
  lyricsLines,
  karaokeLineIndex,
  playQueueItem,
  playPrevInQueue,
  playNextInQueue,
  togglePlay,
  lineKaraokeDisplayText,
  lineSweepMaskStyle,
  lineSweepWidthPct,
} from "../stores/musicPlayerStore.js";

const props = defineProps({
  /** 当前不在「音乐」全屏页时为 true，显示小窗 */
  show: { type: Boolean, default: false },
});

const rootRef = ref(null);
const posLeft = ref(0);
const posTop = ref(0);
const dockedEdge = ref(null);
const collapsed = ref(false);
const headHover = ref(false);
const listExpanded = ref(true);
const dragging = ref(false);

let leaveTimer = 0;
let drag = { x: 0, y: 0, l: 0, t: 0 };

const EDGE_SNAP_PX = 44;
const COLLAPSE_DELAY_MS = 720;

const eligible = computed(() => playQueue.value.length > 0 || !!remoteAudioUrl.value);
const visible = computed(() => props.show && eligible.value);

const rootStyle = computed(() => ({
  left: `${posLeft.value}px`,
  top: `${posTop.value}px`,
  right: "auto",
  bottom: "auto",
}));

function measurePanel() {
  const el = rootRef.value;
  if (!el) return { w: 320, h: 400 };
  const r = el.getBoundingClientRect();
  return { w: r.width || 320, h: r.height || 400 };
}

function clampPos(l, t) {
  const { w, h } = measurePanel();
  const maxL = Math.max(8, window.innerWidth - w - 8);
  const maxT = Math.max(8, window.innerHeight - h - 8);
  return {
    left: Math.min(maxL, Math.max(8, l)),
    top: Math.min(maxT, Math.max(8, t)),
  };
}

function placeDefault() {
  const { w, h } = measurePanel();
  posLeft.value = Math.max(8, window.innerWidth - w - 12);
  posTop.value = Math.max(8, window.innerHeight - h - 24);
}

onMounted(() => {
  placeDefault();
  window.addEventListener("resize", onWinResize);
});

function onWinResize() {
  const c = clampPos(posLeft.value, posTop.value);
  posLeft.value = c.left;
  posTop.value = c.top;
}

watch(
  () => props.show,
  (v) => {
    if (v) {
      collapsed.value = false;
      nextTick(() => placeDefault());
    }
  },
);

function clearLeaveTimer() {
  if (leaveTimer) {
    clearTimeout(leaveTimer);
    leaveTimer = 0;
  }
}

function expandDock() {
  clearLeaveTimer();
  collapsed.value = false;
}

function scheduleCollapseIfDocked() {
  clearLeaveTimer();
  if (!dockedEdge.value) return;
  leaveTimer = window.setTimeout(() => {
    collapsed.value = true;
    headHover.value = false;
    leaveTimer = 0;
  }, COLLAPSE_DELAY_MS);
}

function onRootEnter() {
  expandDock();
}

function onRootLeave() {
  scheduleCollapseIfDocked();
}

function onHitEnter() {
  expandDock();
}

function onPointerDown(e) {
  if (e.button !== 0) return;
  const t = e.target;
  if (t && typeof t.closest === "function" && t.closest("button")) return;
  dragging.value = true;
  dockedEdge.value = null;
  clearLeaveTimer();
  drag = { x: e.clientX, y: e.clientY, l: posLeft.value, t: posTop.value };
  window.addEventListener("pointermove", onPointerMove);
  window.addEventListener("pointerup", onPointerUp);
  window.addEventListener("pointercancel", onPointerUp);
}

function onPointerMove(e) {
  if (!dragging.value) return;
  const dx = e.clientX - drag.x;
  const dy = e.clientY - drag.y;
  const c = clampPos(drag.l + dx, drag.t + dy);
  posLeft.value = c.left;
  posTop.value = c.top;
}

function onPointerUp() {
  if (!dragging.value) return;
  dragging.value = false;
  window.removeEventListener("pointermove", onPointerMove);
  window.removeEventListener("pointerup", onPointerUp);
  window.removeEventListener("pointercancel", onPointerUp);

  const el = rootRef.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  const iw = window.innerWidth;

  if (rect.right >= iw - EDGE_SNAP_PX) {
    dockedEdge.value = "right";
    posLeft.value = clampPos(iw - rect.width - 12, posTop.value).left;
  } else if (rect.left <= EDGE_SNAP_PX) {
    dockedEdge.value = "left";
    posLeft.value = clampPos(12, posTop.value).left;
  } else {
    dockedEdge.value = null;
  }
  const c = clampPos(posLeft.value, posTop.value);
  posLeft.value = c.left;
  posTop.value = c.top;
}

function toggleListExpanded() {
  listExpanded.value = !listExpanded.value;
}

const currentLyricLine = computed(() => {
  const i = karaokeLineIndex.value;
  if (i < 0 || i >= lyricsLines.value.length) return null;
  return lyricsLines.value[i];
});

const hasLyrics = computed(() => lyricsLines.value.length > 0);

const rootClass = computed(() => ({
  "mp-root--collapsed": collapsed.value && !!dockedEdge.value,
  "mp-root--dock-left": dockedEdge.value === "left",
  "mp-root--dock-right": dockedEdge.value === "right",
  "mp-root--dragging": dragging.value,
}));

onUnmounted(() => {
  clearLeaveTimer();
  window.removeEventListener("resize", onWinResize);
  window.removeEventListener("pointermove", onPointerMove);
  window.removeEventListener("pointerup", onPointerUp);
  window.removeEventListener("pointercancel", onPointerUp);
});
</script>

<template>
  <Teleport to="body">
    <div
      v-show="visible"
      ref="rootRef"
      class="mp-root"
      :class="rootClass"
      :style="rootStyle"
      @mouseenter="onRootEnter"
      @mouseleave="onRootLeave"
    >
      <div class="mp-hit" aria-hidden="true" @mouseenter="onHitEnter" />

      <div class="mp-panel">
        <header class="mp-head">
          <div
            class="mp-cover"
            :class="{ 'mp-cover--empty': !displayCover }"
            :style="displayCover ? { backgroundImage: `url(${displayCover})` } : {}"
          >
            <i v-if="!displayCover" class="ri-music-2-line" />
          </div>

          <div class="mp-head-main" @pointerdown="onPointerDown" @mouseenter="headHover = true" @mouseleave="headHover = false">
            <div v-if="headHover" class="mp-head-controls">
              <button
                type="button"
                class="mp-cbtn"
                :disabled="!playQueue.length"
                aria-label="上一首"
                @click.stop="playPrevInQueue"
              >
                <i class="ri-skip-back-mini-fill" />
              </button>
              <button type="button" class="mp-cbtn mp-cbtn--play" aria-label="播放或暂停" @click.stop="togglePlay">
                <i :class="playing ? 'ri-pause-fill' : 'ri-play-fill'" />
              </button>
              <button
                type="button"
                class="mp-cbtn"
                :disabled="!playQueue.length"
                aria-label="下一首"
                @click.stop="playNextInQueue"
              >
                <i class="ri-skip-forward-mini-fill" />
              </button>
              <button
                type="button"
                class="mp-cbtn"
                :aria-label="listExpanded ? '收起列表' : '展开列表'"
                @click.stop="toggleListExpanded"
              >
                <i :class="listExpanded ? 'ri-arrow-down-s-line' : 'ri-arrow-up-s-line'" />
              </button>
            </div>
            <div v-else class="mp-head-lyric-wrap">
              <p v-if="hasLyrics && currentLyricLine" class="mp-head-lyric">
                <span class="mp-karaoke">
                  <span class="mp-w-base">{{ lineKaraokeDisplayText(currentLyricLine) }}</span>
                  <span
                    class="mp-w-sweep"
                    aria-hidden="true"
                    :style="lineSweepMaskStyle(lineSweepWidthPct(currentLyricLine, karaokeLineIndex))"
                    >{{ lineKaraokeDisplayText(currentLyricLine) }}</span>
                </span>
              </p>
              <p v-else class="mp-head-empty">暂无歌词</p>
            </div>
          </div>
        </header>

        <div class="mp-list-section">
          <button type="button" class="mp-list-toggle" @click="toggleListExpanded">
            <span>播放列表</span>
            <i :class="listExpanded ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" />
          </button>
          <ul v-show="listExpanded" class="mp-queue mp-queue--below">
            <li
              v-for="q in playQueue"
              :key="q.key"
              class="mp-row"
              :class="{ 'mp-row--on': q.key === currentQueueKey }"
              role="button"
              tabindex="0"
              @click="playQueueItem(q)"
              @keydown.enter.prevent="playQueueItem(q)"
            >
              <span class="mp-row-t">{{ q.title }}</span>
              <span class="mp-row-a">{{ q.artist }}</span>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mp-root {
  --mp-bg: #ffffff;
  --mp-fg: #111827;
  --mp-muted: #6b7280;
  --mp-border: #e5e7eb;
  --mp-accent: #0ea5e9;
  --mp-sweep: #ea580c;

  position: fixed;
  z-index: 12020;
  width: min(320px, calc(100vw - 24px));
  max-height: min(480px, calc(100vh - 48px));
  display: flex;
  flex-direction: column;
  align-items: stretch;
  pointer-events: auto;
  transition: transform 0.32s cubic-bezier(0.25, 0.8, 0.25, 1);
  transform-origin: right center;
}

.mp-root--dock-left {
  transform-origin: left center;
}

.mp-root--dock-right {
  transform-origin: right center;
}

.mp-root--dock-right.mp-root--collapsed {
  transform: translateX(calc(100% - 18px));
}

.mp-root--dock-left.mp-root--collapsed {
  transform: translateX(calc(-100% + 18px));
}

.mp-root--dragging {
  transition: none;
  cursor: grabbing;
}

.mp-hit {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 20px;
  z-index: 3;
  cursor: pointer;
}

.mp-root--dock-right .mp-hit {
  left: 0;
}

.mp-root--dock-left .mp-hit {
  right: 0;
  left: auto;
}

.mp-root:not(.mp-root--dock-left) .mp-hit {
  left: 0;
}

.mp-panel {
  width: 100%;
  border-radius: 14px;
  background: var(--mp-bg);
  color: var(--mp-fg);
  box-shadow: 0 10px 40px rgba(15, 23, 42, 0.12), 0 0 0 1px var(--mp-border);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.mp-head {
  display: flex;
  gap: 10px;
  padding: 12px;
  align-items: center;
  border-bottom: 1px solid var(--mp-border);
  flex-shrink: 0;
}

.mp-cover {
  position: relative;
  width: 52px;
  height: 52px;
  border-radius: 10px;
  background: #f3f4f6 center / cover;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.mp-cover--empty {
  color: #9ca3af;
  font-size: 1.35rem;
}

.mp-cover-left-hit {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 46%;
  z-index: 2;
  cursor: pointer;
}

.mp-cover-controls {
  position: absolute;
  inset: 0;
  left: 0;
  width: 52%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 6px 4px;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.94) 0%, rgba(255, 255, 255, 0.75) 70%, transparent 100%);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.18s ease;
  z-index: 3;
}

.mp-cover-controls--on {
  opacity: 1;
  pointer-events: auto;
}

.mp-cbtn {
  border: none;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: rgba(17, 24, 39, 0.06);
  color: var(--mp-fg);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1rem;
  flex-shrink: 0;
}

.mp-cbtn:hover {
  background: rgba(17, 24, 39, 0.1);
}

.mp-cbtn:disabled {
  opacity: 0.35;
  cursor: default;
}

.mp-cbtn--play {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--mp-accent);
  color: #fff;
  font-size: 1.05rem;
  padding-left: 2px;
}

.mp-cbtn--play:hover {
  filter: brightness(1.05);
}

.mp-head-main {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  cursor: grab;
  touch-action: none;
  padding: 2px 2px 2px 0;
}

.mp-root--dragging .mp-head-main {
  cursor: grabbing;
}

.mp-head-controls {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.mp-head-lyric-wrap {
  width: 100%;
  min-width: 0;
}

.mp-head-lyric,
.mp-head-empty {
  margin: 0;
  font-size: 0.84rem;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-head-lyric {
  color: var(--mp-fg);
  font-weight: 600;
}

.mp-head-empty {
  color: var(--mp-muted);
}

.mp-list-section {
  border-top: 1px solid var(--mp-border);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: 260px;
}

.mp-list-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: #f9fafb;
  color: var(--mp-muted);
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.02em;
  cursor: pointer;
}

.mp-list-toggle:hover {
  background: #f3f4f6;
}

.mp-queue {
  list-style: none;
  margin: 0;
  padding: 4px 0 8px;
  overflow-y: auto;
  flex: 1;
}

.mp-queue--below {
  max-height: 160px;
}

.mp-queue::-webkit-scrollbar {
  width: 5px;
}

.mp-queue::-webkit-scrollbar-thumb {
  background: rgba(17, 24, 39, 0.15);
  border-radius: 4px;
}

.mp-row {
  padding: 8px 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transition: background 0.12s ease;
}

.mp-row:hover {
  background: #f3f4f6;
}

.mp-row--on {
  background: rgba(14, 165, 233, 0.1);
}

.mp-row-t {
  font-size: 0.8rem;
  color: var(--mp-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-row-a {
  font-size: 0.7rem;
  color: var(--mp-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-karaoke {
  position: relative;
  display: block;
  width: 100%;
}

.mp-w-base {
  color: #9ca3af;
}

.mp-line--on .mp-w-base {
  color: #a1a1aa;
}

.mp-w-sweep {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  display: block;
  box-sizing: border-box;
  white-space: pre-wrap;
  color: var(--mp-sweep);
  pointer-events: none;
  transform: translateZ(0);
  font: inherit;
  letter-spacing: inherit;
  line-height: inherit;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-size: 100% 100%;
  mask-size: 100% 100%;
}

.mp-w {
  margin-right: 0.12em;
}
</style>
