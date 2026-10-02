<script setup>
// @ts-check
/**
 * 图集配乐动态播放器: 客户端本地合成 —— 图片轮播 + CSS 叠化 + <audio>。
 * 与服务端 ffmpeg 成片共用同一套时间轴公式(第 k 次切换在 items[k].startMs),
 * 切换/焦点/画布口径一致, 保证预览即所得。
 * 自动播放遵循浏览器策略: 静音轮播常开, 用户与页面交互过才自动带声(utils/userGesture)。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { hasUserGesture } from "../utils/userGesture";

const props = defineProps({
  /** DynamicVO: items[{imageUrl,startMs,focusX,focusY}], transition, fadeMs, audioUrl, totalDurationMs, canvasType */
  dynamic: { type: Object, required: true },
});

const rootRef = ref(null);
const audioRef = ref(null);

const activeIndex = ref(0);
const playing = ref(false);
const muted = ref(true);
const currentSec = ref(0);

const items = computed(() => props.dynamic?.items || []);
const hasAudio = computed(() => Boolean(props.dynamic?.audioUrl));
const totalSec = computed(() => (props.dynamic?.totalDurationMs || 0) / 1000);
const transition = computed(() => props.dynamic?.transition || "fade");
const fadeMs = computed(() => Number(props.dynamic?.fadeMs ?? 800));
const progressPct = computed(() =>
  totalSec.value > 0 ? `${Math.min(100, (currentSec.value / totalSec.value) * 100)}%` : "0%"
);

let rafId = 0;
let clockStartMs = 0;
let observer = null;

function updateActive() {
  const t = currentSec.value * 1000;
  let idx = 0;
  for (let i = 0; i < items.value.length; i++) {
    if (Number(items.value[i].startMs || 0) <= t) idx = i;
  }
  activeIndex.value = idx;
}

function tick() {
  if (!playing.value) return;
  if (hasAudio.value && audioRef.value) {
    currentSec.value = audioRef.value.currentTime;
  } else {
    currentSec.value = (performance.now() - clockStartMs) / 1000;
  }
  if (totalSec.value > 0 && currentSec.value >= totalSec.value) {
    restart();
    return;
  }
  updateActive();
  rafId = requestAnimationFrame(tick);
}

function startRaf() {
  stopRaf();
  rafId = requestAnimationFrame(tick);
}
function stopRaf() {
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
}

function restart() {
  currentSec.value = 0;
  activeIndex.value = 0;
  if (hasAudio.value && audioRef.value) {
    audioRef.value.currentTime = 0;
    audioRef.value.play().catch(() => {});
  } else {
    clockStartMs = performance.now();
  }
  startRaf();
}

function play() {
  if (playing.value) return;
  playing.value = true;
  muted.value = !hasUserGesture();
  if (hasAudio.value && audioRef.value) {
    audioRef.value.muted = muted.value;
    audioRef.value.play().catch(() => {});
  } else {
    clockStartMs = performance.now() - currentSec.value * 1000;
  }
  startRaf();
}

function pause() {
  playing.value = false;
  stopRaf();
  if (audioRef.value) audioRef.value.pause();
}

function toggleSound() {
  if (!playing.value) {
    muted.value = false;
    play();
    if (hasAudio.value && audioRef.value) {
      muted.value = false;
      audioRef.value.muted = false;
    }
    return;
  }
  muted.value = !muted.value;
  if (audioRef.value) audioRef.value.muted = muted.value;
}

function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) play();
        else pause();
      }
    },
    { threshold: 0.5 }
  );
  if (rootRef.value) observer.observe(rootRef.value);
});

onBeforeUnmount(() => {
  pause();
  if (observer && rootRef.value) observer.unobserve(rootRef.value);
  observer = null;
});
</script>

<template>
  <div
    ref="rootRef"
    class="dyn-player"
    :class="dynamic.canvasType === 'horizontal' ? 'dyn-player--horizontal' : 'dyn-player--vertical'"
    @click="toggleSound"
  >
    <img
      v-for="(item, i) in items"
      :key="i"
      :src="item.imageUrl"
      class="dyn-img"
      :class="{
        'dyn-img--active': i === activeIndex,
        'dyn-img--fade': transition === 'fade' && items.length > 1,
      }"
      :style="{
        objectPosition: `${(item.focusX ?? 0.5) * 100}% ${(item.focusY ?? 0.5) * 100}%`,
        transitionDuration: transition === 'fade' ? `${fadeMs}ms` : undefined,
      }"
      alt=""
    />
    <div class="dyn-shade"></div>
    <button type="button" class="dyn-sound" :aria-label="muted ? '开启声音' : '静音'">
      <i :class="muted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
    </button>
    <div class="dyn-foot">
      <div class="dyn-progress"><div class="dyn-progress-bar" :style="{ width: progressPct }"></div></div>
      <span class="dyn-time">{{ formatTime(currentSec) }} / {{ formatTime(totalSec) }}</span>
    </div>
    <audio v-if="hasAudio" ref="audioRef" :src="dynamic.audioUrl" preload="metadata"></audio>
  </div>
</template>

<style scoped>
.dyn-player {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 12px;
  background: #000;
  cursor: pointer;
  user-select: none;
}
.dyn-player--vertical {
  aspect-ratio: 1080 / 1920;
  max-height: 640px;
}
.dyn-player--horizontal {
  aspect-ratio: 16 / 9;
}
.dyn-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
}
.dyn-img--active {
  opacity: 1;
}
.dyn-img--fade {
  transition-property: opacity;
  transition-timing-function: linear;
}
.dyn-shade {
  position: absolute;
  inset: auto 0 0 0;
  height: 72px;
  background: linear-gradient(transparent, rgb(0 0 0 / 0.55));
  pointer-events: none;
}
.dyn-sound {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  background: rgb(0 0 0 / 0.45);
  color: #fff;
  font-size: 18px;
  display: grid;
  place-items: center;
  cursor: pointer;
}
.dyn-foot {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.dyn-progress {
  flex: 1;
  height: 3px;
  border-radius: 2px;
  background: rgb(255 255 255 / 0.3);
  overflow: hidden;
}
.dyn-progress-bar {
  height: 100%;
  background: #fff;
  transition: width 0.2s linear;
}
.dyn-time {
  font-size: 11px;
  color: #fff;
  font-variant-numeric: tabular-nums;
}
</style>
