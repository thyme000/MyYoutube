<script setup>
// @ts-check
/**
 * 图集配乐动态播放器: 客户端本地合成 —— 图片轮播 + CSS 叠化 + <audio>。
 * 与服务端 ffmpeg 成片共用同一套时间轴公式(第 k 次切换在 items[k].startMs),
 * 切换/焦点/画布口径一致, 保证预览即所得。
 * 自动播放遵循浏览器策略: 静音轮播常开, 用户与页面交互过才自动带声(utils/userGesture)。
 * controlled 时交出播放/静音/进度控制权(沉浸 feed 用): 不自建 IntersectionObserver、
 * 点击改为上报 request-toggle-play、隐藏自带的进度与音量控件, 播放态由 paused/muted 驱动,
 * 进度经 seek() 注入并回报 timeupdate/durationchange —— 与 <video> 的宿主契约对齐。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { hasUserGesture } from "../utils/userGesture";

const props = defineProps({
  /** DynamicVO: items[{imageUrl,startMs,focusX,focusY}], transition, fadeMs, audioUrl, totalDurationMs, canvasType */
  dynamic: { type: Object, required: true },
  /** false 时播完一轮只 emit ended 并暂停(连播切下一条用), 默认循环轮播 */
  loop: { type: Boolean, default: true },
  /** 受控模式: 播放/静音由 paused/muted 驱动, 进度经 seek() 注入 */
  controlled: { type: Boolean, default: false },
  paused: { type: Boolean, default: true },
  muted: { type: Boolean, default: true },
  volume: { type: Number, default: 0.85 },
});
const emit = defineEmits([
  "ended",
  "timeupdate",
  "durationchange",
  "request-toggle-play",
  "request-toggle-mute",
]);

const rootRef = ref(null);
const audioRef = ref(null);

const activeIndex = ref(0);
const playing = ref(false);
const audioMuted = ref(true);
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
/** 受控模式进度上报的节流档位: 0.1s 一档, 与 <video> timeupdate 同量级 */
let lastReportedTenth = -1;

function updateActive() {
  const t = currentSec.value * 1000;
  let idx = 0;
  for (let i = 0; i < items.value.length; i++) {
    if (Number(items.value[i].startMs || 0) <= t) idx = i;
  }
  activeIndex.value = idx;
}

/** 只在跨过 0.1s 档位时上报，避免 60fps 灌爆宿主的进度状态 */
function reportProgress() {
  const tenth = Math.floor(currentSec.value * 10);
  if (tenth === lastReportedTenth) return;
  lastReportedTenth = tenth;
  emit("timeupdate", currentSec.value);
}

function tick() {
  if (!playing.value) return;
  if (hasAudio.value && audioRef.value) {
    currentSec.value = audioRef.value.currentTime;
  } else {
    currentSec.value = (performance.now() - clockStartMs) / 1000;
  }
  if (totalSec.value > 0 && currentSec.value >= totalSec.value) {
    // 完播先于循环重启上报: 宿主(详情层行为信号)依赖 ended, 受控连播同样依赖
    emit("ended");
    if (!props.loop) {
      pause();
      return;
    }
    restart();
    return;
  }
  updateActive();
  reportProgress();
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
  lastReportedTenth = -1;
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
  audioMuted.value = props.controlled ? props.muted : !hasUserGesture();
  if (hasAudio.value && audioRef.value) {
    audioRef.value.muted = audioMuted.value;
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
    audioMuted.value = false;
    play();
    if (hasAudio.value && audioRef.value) {
      audioMuted.value = false;
      audioRef.value.muted = false;
    }
    return;
  }
  audioMuted.value = !audioMuted.value;
  if (audioRef.value) audioRef.value.muted = audioMuted.value;
}

/** 受控宿主的跳转注入: 拖到哪一秒就从哪一秒继续（音频未就绪时只挪进度） */
function seek(sec) {
  const total = totalSec.value;
  let t = Number(sec);
  if (!Number.isFinite(t)) return;
  t = Math.max(0, total > 0 ? Math.min(t, total) : t);
  currentSec.value = t;
  lastReportedTenth = -1;
  if (hasAudio.value && audioRef.value) {
    try {
      audioRef.value.currentTime = t;
    } catch {
      /* 元数据未就绪，等待 canplay 后由 currentTime 接管 */
    }
  } else if (playing.value) {
    clockStartMs = performance.now() - t * 1000;
  }
  updateActive();
  reportProgress();
}

function togglePlay() {
  if (playing.value) pause();
  else play();
}

function setMuted(m) {
  audioMuted.value = Boolean(m);
  if (audioRef.value) audioRef.value.muted = audioMuted.value;
}

function setVolume(v) {
  const vol = Math.min(1, Math.max(0, Number(v) || 0));
  if (audioRef.value) audioRef.value.volume = vol;
}

function onRootClick() {
  if (props.controlled) {
    emit("request-toggle-play");
    return;
  }
  toggleSound();
}

function onSoundClick(ev) {
  ev.stopPropagation();
  if (props.controlled) {
    emit("request-toggle-mute");
    return;
  }
  toggleSound();
}

function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

watch(
  () => props.paused,
  (p) => {
    if (!props.controlled) return;
    if (p) pause();
    else play();
  }
);

watch(
  () => props.muted,
  (m) => {
    if (!props.controlled) return;
    setMuted(m);
  }
);

watch(
  () => props.volume,
  (v) => {
    if (!props.controlled) return;
    setVolume(v);
  }
);

onMounted(() => {
  if (props.controlled) {
    audioMuted.value = props.muted;
    if (audioRef.value) {
      audioRef.value.muted = props.muted;
      audioRef.value.volume = props.volume;
    }
    emit("durationchange", { durationSec: totalSec.value, canvasType: props.dynamic?.canvasType });
    if (!props.paused) play();
    return;
  }
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

defineExpose({ seek, togglePlay, play, pause, setMuted, setVolume });
</script>

<template>
  <div
    ref="rootRef"
    class="dyn-player"
    :class="dynamic.canvasType === 'horizontal' ? 'dyn-player--horizontal' : 'dyn-player--vertical'"
    @click="onRootClick"
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
    <button
      v-if="!controlled"
      type="button"
      class="dyn-sound"
      :aria-label="audioMuted ? '开启声音' : '静音'"
      @click="onSoundClick"
    >
      <i :class="audioMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
    </button>
    <div v-if="!controlled" class="dyn-foot">
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
