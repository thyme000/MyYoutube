<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { musicSearch, musicResolveSong } from "../api/musicApi.js";
import { parseLyricsText } from "../utils/lyricsParser.js";
import { trimUrlToWavFile } from "../utils/audioTrimWav.js";
import BaseModal from "./ui/BaseModal.vue";

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 与幻灯片成片时长一致（秒）；>0 时锁定片段长度，仅调整起点 */
  targetSegmentSec: { type: Number, default: 0 },
});

const emit = defineEmits(["close", "picked"]);

const query = ref("");
const loading = ref(false);
const err = ref("");
const results = ref([]);
let debounceTimer = null;

const streamUrl = ref("");
const trackLabel = ref("");
const durationSec = ref(0);
const startSec = ref(0);
const endSec = ref(0);
const resolving = ref(false);
const resolvingId = ref("");
const exporting = ref(false);
const previewAudioRef = ref(null);
let previewTimeHandler = null;

/** @type {import('vue').Ref<Array<{ timeMs: number, text: string, translation?: string }>>} */
const lyricLines = ref([]);

const isFixedSegment = computed(() => Number(props.targetSegmentSec) > 0);
const fixedLen = computed(() => Math.max(0, Number(props.targetSegmentSec) || 0));
const maxStartSec = computed(() => {
  if (!isFixedSegment.value) return Math.max(0, durationSec.value - 1);
  const L = fixedLen.value;
  const d = durationSec.value;
  return Math.max(0, d - L);
});

function resetInner() {
  query.value = "";
  results.value = [];
  err.value = "";
  streamUrl.value = "";
  trackLabel.value = "";
  durationSec.value = 0;
  startSec.value = 0;
  endSec.value = 0;
  lyricLines.value = [];
  stopPreview();
}

watch(
  () => props.open,
  (v) => {
    if (!v) {
      clearTimeout(debounceTimer);
      resetInner();
    }
  }
);

watch(
  () => [props.targetSegmentSec, props.open],
  () => {
    if (props.open && streamUrl.value && durationSec.value > 0) {
      clampRange();
    }
  }
);

watch(query, (q) => {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    void runSearch(String(q || "").trim());
  }, 420);
});

async function runSearch(t) {
  if (!t) {
    results.value = [];
    return;
  }
  loading.value = true;
  err.value = "";
  try {
    const data = await musicSearch(t, 30);
    results.value = Array.isArray(data) ? data : [];
  } catch (e) {
    err.value = String(e?.message || e || "搜索失败");
    results.value = [];
  } finally {
    loading.value = false;
  }
}

function hintDurationSec(row) {
  if (!row || typeof row !== "object") return 0;
  const dt = row.dt != null ? Number(row.dt) : NaN;
  if (Number.isFinite(dt) && dt > 0) {
    return Math.max(1, Math.round(dt / 1000));
  }
  const d = row.duration != null ? Number(row.duration) : NaN;
  if (Number.isFinite(d) && d > 0) {
    return d > 1000 ? Math.max(1, Math.round(d / 1000)) : Math.max(1, Math.round(d));
  }
  return 0;
}

function loadDurationFromUrl(url, hintSec) {
  return new Promise((resolve) => {
    const a = new Audio();
    a.preload = "metadata";
    a.crossOrigin = "anonymous";
    const finish = (fallback) => {
      let d = Number(a.duration);
      if (!Number.isFinite(d) || d <= 0) {
        d = fallback > 0 ? fallback : 180;
      }
      durationSec.value = Math.max(1, Math.floor(d));
      a.removeAttribute("src");
      a.load();
      resolve();
    };
    a.onloadedmetadata = () => finish(hintSec);
    a.onerror = () => finish(hintSec);
    a.src = url;
  });
}

async function selectTrack(row) {
  if (!row?.id) return;
  resolving.value = true;
  resolvingId.value = String(row.id);
  err.value = "";
  stopPreview();
  lyricLines.value = [];
  try {
    const data = await musicResolveSong({ url: String(row.id) });
    const url = data?.url || "";
    if (!url) {
      throw new Error("未获取到播放地址");
    }
    streamUrl.value = url;
    trackLabel.value = `${data.name || row.name || "曲目"} — ${data.ar_name || row.artists || "—"}`;
    const hint = hintDurationSec(row);
    durationSec.value = 0;
    await loadDurationFromUrl(url, hint);

    const rawLyric = data?.lyric != null ? String(data.lyric) : "";
    lyricLines.value = parseLyricsText(rawLyric).lines || [];

    const L = fixedLen.value;
    const d = durationSec.value;
    if (isFixedSegment.value && L > 0) {
      if (L > d) {
        err.value = `该歌曲长度（约 ${d}s）短于幻灯片所需 BGM ${L}s，请换一首或调整图片张数`;
        streamUrl.value = "";
        trackLabel.value = "";
        durationSec.value = 0;
        lyricLines.value = [];
        return;
      }
      startSec.value = 0;
      endSec.value = L;
      clampRange();
    } else {
      startSec.value = 0;
      endSec.value = d;
      clampRange();
    }
  } catch (e) {
    streamUrl.value = "";
    trackLabel.value = "";
    durationSec.value = 0;
    lyricLines.value = [];
    err.value = String(e?.message || e || "解析失败");
  } finally {
    resolving.value = false;
    resolvingId.value = "";
  }
}

function clampRange() {
  const d = Math.max(1, durationSec.value);
  const L = fixedLen.value;
  if (isFixedSegment.value && L > 0) {
    if (L > d) return;
    let s = Number(startSec.value) || 0;
    s = Math.max(0, Math.min(s, d - L));
    startSec.value = s;
    endSec.value = s + L;
    return;
  }
  if (endSec.value > d) endSec.value = d;
  if (startSec.value < 0) startSec.value = 0;
  if (startSec.value >= endSec.value) {
    startSec.value = Math.max(0, Math.min(endSec.value - 1, d - 1));
  }
  if (endSec.value - startSec.value < 1) {
    endSec.value = Math.min(d, startSec.value + 1);
  }
}

function pickLyricLine(ln) {
  if (!ln || streamUrl.value === "") return;
  const t = Math.max(0, ln.timeMs / 1000);
  const L = fixedLen.value;
  const d = durationSec.value;
  if (isFixedSegment.value && L > 0 && L <= d) {
    const maxS = d - L;
    startSec.value = Math.max(0, Math.min(maxS, t));
    endSec.value = startSec.value + L;
  } else {
    startSec.value = Math.min(Math.max(0, t), Math.max(0, d - 1));
    clampRange();
  }
}

function onStartSliderInput() {
  clampRange();
}

const durationLabel = computed(() => formatMmss(durationSec.value));
const startLabel = computed(() => formatMmss(startSec.value));
const endLabel = computed(() => formatMmss(endSec.value));
const segmentLen = computed(() => {
  if (isFixedSegment.value && fixedLen.value > 0) return fixedLen.value;
  return Math.max(0, endSec.value - startSec.value);
});

const pickerHint = computed(() => {
  if (isFixedSegment.value && fixedLen.value > 0) {
    return "搜索选歌后拖动起点或点歌词对齐，再试听/导出。";
  }
  return "与「音乐」模块相同搜索源；选定后可截取片段并试听，确认后导出为 WAV 用于幻灯片。";
});

function formatMmss(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, "0")}`;
}

function stopPreview() {
  const a = previewAudioRef.value;
  if (previewTimeHandler && a) {
    a.removeEventListener("timeupdate", previewTimeHandler);
    previewTimeHandler = null;
  }
  if (a) {
    a.pause();
    a.removeAttribute("src");
    try {
      a.load();
    } catch {
      /* ignore */
    }
  }
}

function onPreviewSegment() {
  if (!streamUrl.value) return;
  stopPreview();
  const a = previewAudioRef.value;
  if (!a) return;
  err.value = "";
  a.crossOrigin = "anonymous";
  a.src = streamUrl.value;
  const s = Math.max(0, Math.min(startSec.value, durationSec.value - 0.05));
  const e = Math.max(s + 0.05, Math.min(endSec.value, durationSec.value));
  a.currentTime = s;
  previewTimeHandler = () => {
    if (a.currentTime >= e) {
      a.pause();
      stopPreview();
    }
  };
  a.addEventListener("timeupdate", previewTimeHandler);
  a.play().catch(() => {
    err.value = "无法试听（可能被浏览器拦截，或音频地址不允许跨域）";
    stopPreview();
  });
}

async function confirmPick() {
  if (!streamUrl.value) {
    err.value = "请先选择一首歌曲";
    return;
  }
  clampRange();
  const s = startSec.value;
  const e = endSec.value;
  const L = fixedLen.value;
  if (isFixedSegment.value && L > 0) {
    if (Math.abs(e - s - L) > 0.08) {
      clampRange();
    }
  } else if (e - s < 1) {
    err.value = "片段至少 1 秒";
    return;
  }
  exporting.value = true;
  err.value = "";
  try {
    const base = `slideshow-bgm-${Date.now()}.wav`;
    const file = await trimUrlToWavFile(streamUrl.value, s, e, base);
    emit("picked", file);
    emit("close");
  } catch (e) {
    err.value = String(e?.message || e || "导出片段失败，可尝试换歌或缩短区间");
  } finally {
    exporting.value = false;
  }
}

function onClose() {
  stopPreview();
  emit("close");
}

onBeforeUnmount(() => {
  stopPreview();
  clearTimeout(debounceTimer);
});
</script>

<template>
  <BaseModal
    :open="open"
    variant="none"
    overlay-class="slideshow-music-picker-overlay"
    card-class="slideshow-music-picker"
    aria-label="选择背景音乐"
    @close="onClose"
  >
        <div class="smp-head">
          <h3>云端音乐</h3>
          <button type="button" class="smp-close" aria-label="关闭" @click="onClose">
            <i class="ri-close-line" />
          </button>
        </div>
        <p class="smp-hint">{{ pickerHint }}</p>

        <div class="smp-search">
          <i class="ri-search-line" aria-hidden="true" />
          <input v-model="query" type="search" placeholder="歌手、歌曲…" autocomplete="off" />
        </div>
        <p v-if="err" class="smp-err">{{ err }}</p>
        <div v-if="loading" class="smp-loading">搜索中…</div>
        <template v-else>
          <p v-if="!query.trim()" class="smp-placeholder">输入关键词，搜索与「音乐」页相同的云端曲库</p>
          <p v-else-if="!results.length" class="smp-empty">无结果，换个关键词试试</p>
          <ul v-else class="smp-results">
            <li
              v-for="(it, idx) in results"
              :key="it.id || idx"
              role="button"
              tabindex="0"
              class="smp-result"
              :class="{ 'smp-result--busy': resolvingId === String(it.id) }"
              @click="selectTrack(it)"
              @keydown.enter.prevent="selectTrack(it)"
            >
              <img v-if="it.picUrl" class="smp-pic" :src="it.picUrl" alt="" @error="(e) => (e.target.style.display = 'none')" />
              <div class="smp-meta">
                <span class="smp-name">{{ it.name }}</span>
                <span class="smp-sub">{{ it.artists }} · {{ it.album }}</span>
              </div>
              <i v-if="resolvingId === String(it.id)" class="ri-loader-4-line smp-spin" aria-hidden="true" />
            </li>
          </ul>
        </template>

        <div v-if="streamUrl && durationSec > 0" class="smp-trim">
          <p class="smp-track-title">{{ trackLabel }}</p>
          <p class="smp-dur">整首约 <strong>{{ durationLabel }}</strong>（{{ durationSec }} 秒）</p>

          <template v-if="isFixedSegment && fixedLen > 0">
            <p class="smp-fixed-banner">
              已锁定 BGM 长度 <strong>{{ fixedLen }}</strong> 秒（与当前幻灯片一致）
            </p>
            <label class="smp-slider-label">
              <span>片段起点 {{ startLabel }} — 结束 {{ endLabel }}</span>
              <input
                v-model.number="startSec"
                type="range"
                min="0"
                :max="maxStartSec"
                step="0.05"
                :disabled="resolving || exporting"
                @input="onStartSliderInput"
              />
            </label>

            <div v-if="lyricLines.length" class="smp-lyrics">
              <p class="smp-lyrics-title">点歌词行，将片段起点对齐到该行时间</p>
              <ul class="smp-lyrics-list">
                <li
                  v-for="(ln, i) in lyricLines"
                  :key="`${ln.timeMs}-${i}`"
                  type="button"
                  class="smp-lyric-line"
                  :disabled="resolving || exporting"
                  @click="pickLyricLine(ln)"
                >
                  <span class="smp-lyric-t">{{ formatMmss(ln.timeMs / 1000) }}</span>
                  <span class="smp-lyric-txt">{{ ln.text || "—" }}</span>
                </li>
              </ul>
            </div>
          </template>

          <template v-else>
            <div class="smp-range-grid">
              <label class="smp-field">
                <span>起始（秒）</span>
                <input
                  v-model.number="startSec"
                  type="number"
                  min="0"
                  :max="Math.max(0, durationSec - 1)"
                  step="0.5"
                  @change="clampRange"
                />
                <span class="smp-mmss">{{ startLabel }}</span>
              </label>
              <label class="smp-field">
                <span>结束（秒）</span>
                <input
                  v-model.number="endSec"
                  type="number"
                  min="1"
                  :max="durationSec"
                  step="0.5"
                  @change="clampRange"
                />
                <span class="smp-mmss">{{ endLabel }}</span>
              </label>
            </div>
          </template>

          <p class="smp-seglen">
            所选片段长度：<strong>{{ segmentLen.toFixed(1) }}</strong> 秒
            <template v-if="isFixedSegment && fixedLen > 0">（固定）</template>
          </p>

          <div class="smp-actions">
            <button type="button" class="smp-btn smp-btn--ghost" :disabled="resolving || exporting" @click="onPreviewSegment">
              试听所选区间
            </button>
            <button type="button" class="smp-btn smp-btn--primary" :disabled="resolving || exporting" @click="confirmPick">
              {{ exporting ? "导出中…" : "使用此片段" }}
            </button>
          </div>
        </div>

        <audio ref="previewAudioRef" class="smp-audio-el" preload="none" />
  </BaseModal>
</template>

<style scoped>
/* 幻灯片：云端选曲弹层（高于上传弹窗 1002） */
.slideshow-music-picker-overlay {
  position: fixed;
  inset: 0;
  z-index: 1100;
  background: rgb(var(--black-rgb) / 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-16);
}

.slideshow-music-picker {
  position: relative;
  width: min(440px, 100%);
  max-height: min(86vh, 720px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-4xl);
  background: var(--ink-980);
  border: 1px solid rgb(var(--white-rgb) / 0.1);
  color: var(--gray-200);
  box-shadow: 0 20px 60px rgb(var(--black-rgb) / 0.5);
}

.smp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-14) var(--space-16);
  border-bottom: 1px solid rgb(var(--white-rgb) / 0.08);
}

.smp-head h3 {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 600;
}

.smp-close {
  border: none;
  background: transparent;
  color: var(--ink-500);
  cursor: pointer;
  padding: var(--space-4);
  border-radius: var(--radius-lg);
  line-height: 1;
}

.smp-close:hover {
  color: var(--color-text-inverse);
  background: rgb(var(--white-rgb) / 0.08);
}

.smp-close i {
  font-size: var(--font-size-22);
}

.smp-hint {
  margin: 0;
  padding: var(--space-10) var(--space-16);
  font-size: var(--font-size-12);
  line-height: 1.45;
  color: var(--ink-500);
  border-bottom: 1px solid rgb(var(--white-rgb) / 0.06);
}

.smp-search {
  display: flex;
  align-items: center;
  gap: var(--space-8);
  margin: var(--space-12) var(--space-16) 0;
  padding: var(--space-8) var(--space-12);
  border-radius: var(--radius-xl);
  background: var(--ink-900);
  border: 1px solid rgb(var(--white-rgb) / 0.1);
}

.smp-search i {
  color: var(--ink-600);
  font-size: var(--font-size-18);
}

.smp-search input {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--color-text-inverse);
  font-size: var(--font-size-14);
  outline: none;
}

.smp-err {
  margin: var(--space-8) var(--space-16) 0;
  font-size: var(--font-size-12);
  color: var(--red-400);
}

.smp-loading,
.smp-placeholder,
.smp-empty {
  margin: var(--space-12) var(--space-16);
  font-size: var(--font-size-13);
  color: var(--ink-500);
}

.smp-results {
  list-style: none;
  margin: var(--space-8) 0 0;
  padding: 0 var(--space-8) var(--space-8);
  max-height: 200px;
  overflow-y: auto;
}

.smp-result {
  display: flex;
  align-items: center;
  gap: var(--space-10);
  padding: var(--space-8) var(--space-8);
  border-radius: var(--radius-xl);
  cursor: pointer;
}

.smp-result:hover {
  background: rgb(var(--white-rgb) / 0.06);
}

.smp-result--busy {
  opacity: 0.7;
  pointer-events: none;
}

.smp-pic {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  object-fit: cover;
  flex-shrink: 0;
}

.smp-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.smp-name {
  font-size: var(--font-size-14);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.smp-sub {
  font-size: var(--font-size-12);
  color: var(--ink-600);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.smp-spin {
  font-size: var(--font-size-18);
  color: var(--ink-300);
  animation: smp-spin 0.9s linear infinite;
}

.smp-trim {
  padding: var(--space-12) var(--space-16) var(--space-16);
  border-top: 1px solid rgb(var(--white-rgb) / 0.08);
  background: rgb(var(--black-rgb) / 0.2);
}

.smp-track-title {
  margin: 0 0 var(--space-6);
  font-size: var(--font-size-13);
  color: var(--ink-300);
  line-height: 1.4;
}

.smp-dur {
  margin: 0 0 var(--space-12);
  font-size: var(--font-size-12);
  color: var(--ink-500);
}

.smp-range-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-12);
}

.smp-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  font-size: var(--font-size-11);
  color: var(--ink-400);
}

.smp-field input {
  border-radius: var(--radius-lg);
  border: 1px solid rgb(var(--white-rgb) / 0.15);
  background: var(--ink-980);
  color: var(--color-text-inverse);
  padding: var(--space-8) var(--space-10);
  font-size: var(--font-size-14);
}

.smp-mmss {
  font-size: var(--font-size-12);
  color: var(--ink-700);
}

.smp-seglen {
  margin: var(--space-10) 0 0;
  font-size: var(--font-size-12);
  color: var(--ink-400);
}

.smp-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-10);
  margin-top: var(--space-14);
}

.smp-btn {
  border-radius: var(--radius-full);
  padding: var(--space-9) var(--space-18);
  font-size: var(--font-size-13);
  font-weight: 600;
  cursor: pointer;
  border: none;
}

.smp-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.smp-btn--ghost {
  background: transparent;
  color: var(--color-text-inverse);
  border: 1px solid rgb(var(--white-rgb) / 0.3);
}

.smp-btn--primary {
  background: var(--color-bg-surface);
  color: var(--ink-980);
}

.smp-audio-el {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.smp-fixed-banner {
  margin: 0 0 var(--space-12);
  padding: var(--space-10) var(--space-12);
  border-radius: var(--radius-xl);
  background: rgba(80, 120, 255, 0.15);
  border: 1px solid rgba(120, 160, 255, 0.35);
  font-size: var(--font-size-13);
  color: #c8d4ff;
  text-align: left;
}

.smp-slider-label {
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
  margin-bottom: var(--space-14);
  font-size: var(--font-size-12);
  color: var(--ink-400);
  text-align: left;
}

.smp-slider-label input[type="range"] {
  width: 100%;
  accent-color: #8ab4ff;
}

.smp-lyrics {
  margin-bottom: var(--space-12);
  text-align: left;
}

.smp-lyrics-title {
  margin: 0 0 var(--space-8);
  font-size: var(--font-size-12);
  color: var(--ink-500);
}

.smp-lyrics-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 180px;
  overflow-y: auto;
  border-radius: var(--radius-xl);
  border: 1px solid rgb(var(--white-rgb) / 0.1);
  background: rgb(var(--black-rgb) / 0.25);
}

.smp-lyric-line {
  display: flex;
  gap: var(--space-10);
  align-items: baseline;
  width: 100%;
  padding: var(--space-8) var(--space-10);
  border: none;
  border-bottom: 1px solid rgb(var(--white-rgb) / 0.06);
  background: transparent;
  color: var(--gray-300);
  font-size: var(--font-size-13);
  text-align: left;
  cursor: pointer;
}

.smp-lyric-line:last-child {
  border-bottom: none;
}

.smp-lyric-line:hover:not(:disabled) {
  background: rgb(var(--white-rgb) / 0.06);
}

.smp-lyric-line:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.smp-lyric-t {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  font-size: var(--font-size-12);
  color: #7a9fff;
  min-width: 3.2rem;
}

.smp-lyric-txt {
  flex: 1;
  min-width: 0;
  line-height: 1.35;
}

</style>
