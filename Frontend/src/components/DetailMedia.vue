<script setup>
// @ts-check
/**
 * 详情层媒体区: 视频行走原生 <video>(含缓冲层), 动态行走受控 DynamicPlayer + 自制控件条。
 * 宿主只渲染本组件; 播放状态、行为信号(PLAY/FINISH/历史)与下载都收敛在 detailStore。
 */
import { ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useDetailStore } from "../stores/detailStore";
import DynamicPlayer from "./DynamicPlayer.vue";

const store = useDetailStore();
const {
  video: videoDetail,
  buffering: detailVideoBuffering,
  playerRef: detailPlayerRef,
  dynPlayerRef,
  dynPaused,
  dynMuted,
  dynVolume,
  dynDuration,
  dynCurrentTime,
} = storeToRefs(store);

/** 播放器实例: ref 对象留在本组件, 挂载后回写给 store 供命令式控制(快捷键/续播/自动播放) */
const localVideoRef = ref(null);
watch(localVideoRef, (v) => { detailPlayerRef.value = v; }, { immediate: true });
const localDynRef = ref(null);
watch(localDynRef, (v) => { dynPlayerRef.value = v; }, { immediate: true });

function formatTime(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
</script>

<template>
  <div class="video-detail-player-wrap" :class="{ 'video-detail-player-wrap--dyn': store.isDynamicRow }">
    <template v-if="store.isDynamicRow">
      <div class="dyn-detail-stage">
        <DynamicPlayer
          :key="String(videoDetail.id)"
          ref="localDynRef"
          class="dyn-detail-player"
          :dynamic="videoDetail"
          :loop="false"
          controlled
          :paused="dynPaused"
          :muted="dynMuted"
          :volume="dynVolume"
          @durationchange="store.onDynDurationChange"
          @timeupdate="store.onDynamicTimeUpdate"
          @ended="store.onDynamicEnded"
          @play="store.onDynPlay"
          @pause="store.onDynPause"
          @request-toggle-play="store.toggleDetailPlay"
          @request-toggle-mute="store.toggleDetailMute"
        />
      </div>
      <div class="dyn-detail-controls">
        <input
          type="range"
          class="dyn-detail-scrub"
          min="0"
          :max="dynDuration || 1"
          step="0.1"
          :disabled="!dynDuration"
          :value="dynCurrentTime"
          aria-label="播放进度"
          @input="store.onDetailDynScrub"
        />
        <div class="dyn-detail-controls-row">
          <button
            type="button"
            class="dyn-detail-ctl-btn"
            :title="dynPaused ? '播放' : '暂停'"
            :aria-label="dynPaused ? '播放' : '暂停'"
            @click="store.toggleDetailPlay"
          >
            <i :class="dynPaused ? 'ri-play-fill' : 'ri-pause-line'"></i>
          </button>
          <span class="dyn-detail-time">{{ formatTime(dynCurrentTime) }} / {{ formatTime(dynDuration) }}</span>
          <span class="dyn-detail-spacer"></span>
          <button
            type="button"
            class="dyn-detail-ctl-btn"
            :title="dynMuted ? '开启声音' : '静音'"
            :aria-label="dynMuted ? '开启声音' : '静音'"
            @click="store.toggleDetailMute"
          >
            <i :class="dynMuted ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
          </button>
          <input
            type="range"
            class="dyn-detail-vol"
            min="0"
            max="1"
            step="0.05"
            :value="dynVolume"
            aria-label="音量"
            @input="store.onDetailDynVolume"
          />
          <button
            type="button"
            class="dyn-detail-ctl-btn"
            title="全屏"
            aria-label="全屏"
            @click="store.toggleDetailFullscreen"
          >
            <i class="ri-fullscreen-line"></i>
          </button>
        </div>
      </div>
    </template>
    <template v-else>
      <video
        v-if="videoDetail.videoUrl"
        :key="String(videoDetail.id)"
        ref="localVideoRef"
        class="video-detail-player"
        controls
        playsinline
        preload="auto"
        autoplay
        :src="videoDetail.videoUrl"
        @loadstart="store.onVideoLoadStart"
        @canplay="store.onVideoCanPlay"
        @error="store.onVideoMediaError"
        @loadeddata="() => void store.tryAutoplay()"
        @loadedmetadata="() => { store.applyPlaybackResumeIfNeeded(); void store.tryAutoplay(); }"
        @canplaythrough="() => void store.tryAutoplay()"
        @pause="store.onVideoPause"
        @play="store.onVideoPlay"
        @timeupdate="store.onVideoTimeUpdate"
        @seeked="store.onVideoSeeked"
        @ended="store.onVideoEnded"
      />
      <div
        v-if="videoDetail.videoUrl && detailVideoBuffering"
        class="video-player-loading video-player-loading--detail"
        aria-live="polite"
        aria-busy="true"
      >
        <div class="video-player-loading__shimmer" aria-hidden="true" />
        <div class="video-player-loading__inner">
          <span class="video-player-loading__ring video-player-loading__ring--detail" />
          <span class="video-player-loading__text video-player-loading__text--detail">正在加载视频</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.video-detail-player-wrap {
  position: relative;
  /* 视频未铺满区域用浅灰填充，避免大块纯黑 */
  background: var(--gray-200);
  border-radius: var(--radius-2xl);
  overflow: hidden;
  margin-bottom: var(--space-12);
  border: 1px solid var(--line);
}

/* 元数据未到时 <video> 无固有高度，先固定 16:9 占位，避免加载层期间被压扁再撑开 */
.video-detail-player-wrap:has(> .video-detail-player) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 100%;
  max-height: min(70vh, 720px);
  aspect-ratio: 16 / 9;
  min-height: min(200px, 42vw);
}

.video-detail-player-wrap:has(> .video-detail-player) .video-detail-player {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  vertical-align: top;
}

.video-detail-player {
  width: 100%;
  max-height: min(70vh, 720px);
  display: block;
  background: transparent;
  vertical-align: top;
}

/* 动态行: 深色舞台居中, 下方自制控件条(对齐 <video> 原生控件的操作面) */
.video-detail-player-wrap--dyn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-10);
  background: var(--ink-990, #000);
  padding: var(--space-12) var(--space-12) var(--space-10);
}

.dyn-detail-stage {
  display: flex;
  justify-content: center;
  min-height: 0;
}

.dyn-detail-stage :deep(.dyn-player--vertical) {
  height: min(62vh, 660px);
  width: auto;
  max-width: 100%;
}

.dyn-detail-stage :deep(.dyn-player--horizontal) {
  width: 100%;
}

.dyn-detail-controls {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.dyn-detail-scrub {
  width: 100%;
  height: 4px;
  accent-color: var(--accent, #f43f5e);
  cursor: pointer;
}

.dyn-detail-controls-row {
  display: flex;
  align-items: center;
  gap: var(--space-10);
}

.dyn-detail-ctl-btn {
  border: none;
  background: transparent;
  color: rgb(255 255 255 / 0.92);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  display: grid;
  place-items: center;
  padding: 2px 4px;
}

.dyn-detail-ctl-btn:hover {
  color: #fff;
}

.dyn-detail-time {
  color: rgb(255 255 255 / 0.85);
  font-size: var(--font-size-12);
  font-variant-numeric: tabular-nums;
}

.dyn-detail-spacer {
  flex: 1;
}

.dyn-detail-vol {
  width: 90px;
  accent-color: #fff;
  cursor: pointer;
}

.video-player-loading--detail {
  position: absolute;
  inset: 0;
  border-radius: var(--radius-2xl);
  background: linear-gradient(155deg, var(--slate-200) 0%, var(--gray-200) 42%, var(--slate-300) 100%);
}

.video-player-loading__ring--detail {
  border: 3px solid rgb(var(--blue-600-rgb) / 0.18);
  border-top-color: var(--blue-600);
}

.video-player-loading__text--detail {
  color: var(--color-text-muted);
}
</style>
