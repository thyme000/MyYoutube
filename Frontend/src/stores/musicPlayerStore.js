// @ts-check
/**
 * 全局音乐播放状态：切换侧栏「音乐」以外的界面时队列与播放不丢失。
 * 队列最多 20 首，超出时按 FIFO 移除最早一首。
 */
import { shallowRef, computed, watch, nextTick } from "vue";
import { defineStore } from "pinia";
import { parseLyricsText } from "../utils/lyricsParser.js";
import { musicResolveSong } from "../api/musicApi.js";

export const MAX_MUSIC_QUEUE = 20;

export const audioRef = shallowRef(null);


/**
 * pinia 状态容器：音乐播放器的可序列化状态。
 * 导出的同名 ref 是可写 computed 门面（内部 .value 读写直通 store），消费方零改动。
 */
const useMusicPlayerState = defineStore("musicPlayer", {
  state: () => ({
    playQueue: [],
    currentQueueKey: "",
    remoteAudioUrl: "",
    remoteCoverUrl: "",
    titleText: "音乐",
    artistText: "在「云端音乐」中搜索并播放，歌曲将加入队列",
    lyricsLines: [],
    currentSec: 0,
    durationSec: 0,
    playing: false,
    lyricsClockMs: 0,
    activeLineIndex: -1,
    karaokeLineIndex: -1,
    activeWordIndex: -1,
    volumePct: 100,
    volumeBeforeMute: 100,
    gradientSeed: 0.42,
    musicLyricsFullscreen: false,
  }),
});

let _state = null;
let _watched = false;
function st() {
  if (!_state) {
    _state = useMusicPlayerState();
    if (!_watched) {
      _watched = true;
      installStoreWatches();
    }
  }
  return _state;
}

export function setAudioElement(el) {
  audioRef.value = el || null;
}

export const playQueue = computed({
  get: () => st().playQueue,
  set: (v) => { st().playQueue = v; },
});
export const currentQueueKey = computed({
  get: () => st().currentQueueKey,
  set: (v) => { st().currentQueueKey = v; },
});
export const remoteAudioUrl = computed({
  get: () => st().remoteAudioUrl,
  set: (v) => { st().remoteAudioUrl = v; },
});
export const remoteCoverUrl = computed({
  get: () => st().remoteCoverUrl,
  set: (v) => { st().remoteCoverUrl = v; },
});
export const titleText = computed({
  get: () => st().titleText,
  set: (v) => { st().titleText = v; },
});
export const artistText = computed({
  get: () => st().artistText,
  set: (v) => { st().artistText = v; },
});
export const lyricsLines = computed({
  get: () => st().lyricsLines,
  set: (v) => { st().lyricsLines = v; },
});
export const currentSec = computed({
  get: () => st().currentSec,
  set: (v) => { st().currentSec = v; },
});
export const durationSec = computed({
  get: () => st().durationSec,
  set: (v) => { st().durationSec = v; },
});
export const playing = computed({
  get: () => st().playing,
  set: (v) => { st().playing = v; },
});
export const lyricsClockMs = computed({
  get: () => st().lyricsClockMs,
  set: (v) => { st().lyricsClockMs = v; },
});
export const activeLineIndex = computed({
  get: () => st().activeLineIndex,
  set: (v) => { st().activeLineIndex = v; },
});
export const karaokeLineIndex = computed({
  get: () => st().karaokeLineIndex,
  set: (v) => { st().karaokeLineIndex = v; },
});
export const activeWordIndex = computed({
  get: () => st().activeWordIndex,
  set: (v) => { st().activeWordIndex = v; },
});
export const volumePct = computed({
  get: () => st().volumePct,
  set: (v) => { st().volumePct = v; },
});
export const volumeBeforeMute = computed({
  get: () => st().volumeBeforeMute,
  set: (v) => { st().volumeBeforeMute = v; },
});
export const gradientSeed = computed({
  get: () => st().gradientSeed,
  set: (v) => { st().gradientSeed = v; },
});

/** 音乐歌词页全屏（Teleport 到 body）时为 true；供 App 隐藏会话悬浮条等，避免 z-index 叠在全屏层之上 */
export const musicLyricsFullscreen = computed({
  get: () => st().musicLyricsFullscreen,
  set: (v) => { st().musicLyricsFullscreen = v; },
});

export const playSrc = computed(() => remoteAudioUrl.value || undefined);
export const currentQueueIndex = computed(() =>
  playQueue.value.findIndex((q) => q.key === currentQueueKey.value),
);
export const displayCover = computed(() => remoteCoverUrl.value || "");

export const seekPct = computed(() => {
  if (!durationSec.value) return 0;
  return Math.min(1000, Math.round((currentSec.value / durationSec.value) * 1000));
});

export const lyricsRenderItems = computed(() =>
  lyricsLines.value.map((line, idx) => ({ line, idx })),
);

let lyricsRafId = 0;
let lastSyncMs = -1;
let bypassGateUntil = 0;

function calcLineEndMs(lines, lineIdx) {
  const line = lines[lineIdx];
  if (!line) return 0;
  const baseStart = Number(line.timeMs) || 0;
  const nextStart =
    lineIdx + 1 < lines.length ? Number(lines[lineIdx + 1]?.timeMs || 0) : Number.POSITIVE_INFINITY;

  // 同时间戳/超近时间戳歌词很常见（尤其长句拆行），不能直接用 nextStart 作为本行结束。
  const words = Array.isArray(line.words) ? line.words : [];
  if (words.length) {
    const lastWordStart = Number(words[words.length - 1]?.timeMs || baseStart);
    const textLen = Math.max(1, String(lineKaraokeDisplayText(line) || "").length);
    const tailDur = Math.min(1800, Math.max(520, textLen * 70));
    const byWords = lastWordStart + tailDur;
    if (Number.isFinite(nextStart)) {
      // nextStart 明显在后面时，优先用 nextStart；否则用 byWords，避免瞬间切行。
      return nextStart - baseStart >= 220 ? Math.min(nextStart, byWords) : byWords;
    }
    return byWords;
  }

  const textLen = Math.max(1, String(line.text || "").length);
  const fallbackDur = Math.min(2600, Math.max(900, textLen * 90));
  const byText = baseStart + fallbackDur;
  if (Number.isFinite(nextStart)) {
    return nextStart - baseStart >= 220 ? Math.min(nextStart, byText) : byText;
  }
  return byText;
}

function pumpLyricsClock() {
  const a = audioRef.value;
  if (a) lyricsClockMs.value = a.currentTime * 1000;
  if (playing.value && a && !a.paused) {
    lyricsRafId = requestAnimationFrame(pumpLyricsClock);
  }
}

function installStoreWatches() {
watch(playing, (p) => {
  if (lyricsRafId) {
    cancelAnimationFrame(lyricsRafId);
    lyricsRafId = 0;
  }
  const a = audioRef.value;
  if (a) lyricsClockMs.value = a.currentTime * 1000;
  if (p) lyricsRafId = requestAnimationFrame(pumpLyricsClock);
});

watch(volumePct, (v) => {
  const a = audioRef.value;
  if (a) a.volume = Math.min(1, Math.max(0, Number(v) / 100));
});

watch(playSrc, () => {
  nextTick(() => {
    const a = audioRef.value;
    if (a) a.volume = Math.min(1, Math.max(0, volumePct.value / 100));
  });
});
}

export function makeQueueKey() {
  return `q-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function handleDroppedCurrent() {
  if (!playQueue.value.length) {
    currentQueueKey.value = "";
    clearRemotePlayback();
    titleText.value = "音乐";
    artistText.value = "在「云端音乐」中搜索并播放，歌曲将加入队列";
    lyricsLines.value = [];
    playing.value = false;
    return;
  }
  playQueueItem(playQueue.value[0]);
}

/** 入队前维持最多 MAX_MUSIC_QUEUE：满则移除队首（FIFO） */
function enforceQueueCapBeforePush() {
  while (playQueue.value.length >= MAX_MUSIC_QUEUE) {
    const dropped = playQueue.value.shift();
    if (dropped?.key === currentQueueKey.value) {
      handleDroppedCurrent();
    }
  }
}

export function applyPlaybackFromPayload(payload) {
  remoteAudioUrl.value = payload.streamUrl || "";
  remoteCoverUrl.value = payload.picUrl || "";
  titleText.value = payload.title;
  artistText.value = payload.artist;
  if (payload.lyricRaw) {
    lyricsLines.value = parseLyricsText(String(payload.lyricRaw)).lines;
  } else {
    lyricsLines.value = [];
  }
  gradientSeed.value = Math.random();
}

export function applyPlaybackFromItem(item) {
  applyPlaybackFromPayload({
    streamUrl: item.streamUrl,
    picUrl: item.picUrl,
    title: item.title,
    artist: item.artist,
    lyricRaw: item.lyricRaw,
  });
}

export function clearRemotePlayback() {
  remoteAudioUrl.value = "";
  remoteCoverUrl.value = "";
}

export function playQueueItem(item) {
  if (!item?.streamUrl) return;
  currentQueueKey.value = item.key;
  applyPlaybackFromItem(item);
  nextTick(() => audioRef.value?.play?.().catch(() => {}));
}

export function removeFromQueue(key, ev) {
  if (ev) ev.stopPropagation();
  const idx = playQueue.value.findIndex((q) => q.key === key);
  if (idx < 0) return;
  const wasCurrent = currentQueueKey.value === key;
  playQueue.value = playQueue.value.filter((q) => q.key !== key);
  if (!wasCurrent) return;
  if (!playQueue.value.length) {
    currentQueueKey.value = "";
    clearRemotePlayback();
    titleText.value = "音乐";
    artistText.value = "在「云端音乐」中搜索并播放，歌曲将加入队列";
    lyricsLines.value = [];
    playing.value = false;
    return;
  }
  const i = Math.min(idx, playQueue.value.length - 1);
  const nextItem = playQueue.value[i];
  if (nextItem) playQueueItem(nextItem);
}

export function playPrevInQueue() {
  const list = playQueue.value;
  if (!list.length) return;
  const i = currentQueueIndex.value;
  if (i < 0) {
    playQueueItem(list[list.length - 1]);
    return;
  }
  const prev = i === 0 ? list.length - 1 : i - 1;
  playQueueItem(list[prev]);
}

export function playNextInQueue() {
  const list = playQueue.value;
  if (!list.length) return;
  const i = currentQueueIndex.value;
  if (i < 0) {
    playQueueItem(list[0]);
    return;
  }
  const next = i >= list.length - 1 ? 0 : i + 1;
  playQueueItem(list[next]);
}

export function onTrackEnded() {
  playing.value = false;
  const list = playQueue.value;
  if (!list.length) return;
  const i = currentQueueIndex.value;
  if (i < 0) return;
  const next = i >= list.length - 1 ? 0 : i + 1;
  playQueueItem(list[next]);
}

export function syncLyrics(t) {
  const lines = lyricsLines.value;
  if (!lines.length) {
    activeLineIndex.value = -1;
    karaokeLineIndex.value = -1;
    activeWordIndex.value = -1;
    return;
  }
  const ms = t * 1000;
  const now = typeof performance !== "undefined" ? performance.now() : Date.now();
  const isLargeJump = lastSyncMs >= 0 && Math.abs(ms - lastSyncMs) > 1200;
  /**
   * 当前行：取满足 timeMs <= ms 的最大下标（与 lineSweepWidthPct 的「本行 → 下一行时间」一致）。
   * 勿用 timeMs - 80ms 提前切行，否则上行扫光未满、下行已开始扫（拆行歌词尤其明显）。
   */
  let idx = -1;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (ms >= lines[i].timeMs) {
      idx = i;
      break;
    }
  }
  activeLineIndex.value = idx;
  let kIdx = idx;
  const prevK = karaokeLineIndex.value;
  /**
   * 硬闸门（时间边界）：
   * 1) 上一行未到「本行结束时间」前，禁止切到下一行；
   * 2) 正常连续播放时每次最多前进一行，确保严格顺序扫光；
   * 3) 用户手动跳播/大幅时间跳变时，允许直接定位，避免点歌词后卡在旧行。
   */
  const shouldBypassGate = now < bypassGateUntil || isLargeJump;
  if (!shouldBypassGate && prevK >= 0 && prevK < lines.length) {
    const prevEndMs = calcLineEndMs(lines, prevK);
    if (ms < prevEndMs) {
      kIdx = prevK;
    } else if (idx > prevK + 1) {
      kIdx = prevK + 1;
    }
  }
  karaokeLineIndex.value = kIdx;
  /**
   * 统一使用闸门后的行索引，避免任何依赖 activeLineIndex 的视图提前切到下一行。
   */
  activeLineIndex.value = kIdx;
  const line = kIdx >= 0 ? lines[kIdx] : null;
  if (line?.words?.length) {
    let wi = -1;
    for (let i = line.words.length - 1; i >= 0; i--) {
      if (ms >= line.words[i].timeMs) {
        wi = i;
        break;
      }
    }
    activeWordIndex.value = wi;
  } else {
    activeWordIndex.value = -1;
  }
  lastSyncMs = ms;
}

export function onTimeUpdate() {
  const a = audioRef.value;
  if (!a) return;
  currentSec.value = a.currentTime || 0;
  durationSec.value = a.duration && Number.isFinite(a.duration) ? a.duration : 0;
  lyricsClockMs.value = a.currentTime * 1000;
  syncLyrics(a.currentTime);
}

export function onAudioMetadata() {
  const a = audioRef.value;
  if (a?.duration && Number.isFinite(a.duration)) durationSec.value = a.duration;
}

export function onAudioPlay() {
  playing.value = true;
}

export function onAudioPause() {
  playing.value = false;
}

export function togglePlay() {
  const a = audioRef.value;
  if (!a) return;
  if (playing.value) a.pause();
  else void a.play().catch(() => {});
}

export function toggleMute() {
  if (volumePct.value > 0) {
    volumeBeforeMute.value = volumePct.value;
    volumePct.value = 0;
  } else {
    volumePct.value = volumeBeforeMute.value > 0 ? volumeBeforeMute.value : 80;
  }
}

export function onSeekInput(e) {
  const a = audioRef.value;
  if (!a || !durationSec.value) return;
  a.currentTime = (Number(e.target.value) / 1000) * durationSec.value;
  lyricsClockMs.value = a.currentTime * 1000;
}

export function seekToLyricLine(line) {
  const a = audioRef.value;
  if (!a || !line || !Number.isFinite(line.timeMs)) return;
  let t = line.timeMs / 1000;
  if (durationSec.value > 0) {
    t = Math.min(Math.max(0, t), Math.max(0, durationSec.value - 0.02));
  } else {
    t = Math.max(0, t);
  }
  a.currentTime = t;
  currentSec.value = t;
  lyricsClockMs.value = t * 1000;
  bypassGateUntil = (typeof performance !== "undefined" ? performance.now() : Date.now()) + 800;
  const targetIdx = lyricsLines.value.indexOf(line);
  if (targetIdx >= 0) {
    karaokeLineIndex.value = targetIdx;
    activeLineIndex.value = targetIdx;
  }
  syncLyrics(t);
  void a.play().catch(() => {});
}

export function formatTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) return "0:00";
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

function sanitizeMusicError(err) {
  const raw = err?.message != null ? String(err.message) : String(err ?? "");
  if (/<\s*html[\s>]|<!DOCTYPE|<\s*head[\s>]|<title>\s*405/i.test(raw) || (raw.includes("nginx") && raw.includes("<"))) {
    return "无法连接解析服务：请确认本机 5000 端口已启动，且 Nginx 已配置 /music-svc/ 反代后执行 reload。";
  }
  return raw.replace(/<[^>]*>/g, "").trim().slice(0, 240) || "请求失败";
}

/**
 * 解析服务返回后入队并播放（队列满时先 FIFO 丢队首）
 * @param {any} row
 * @param {{ onError?: (msg: string) => void }} [opts]
 */
export async function playCloudTrackFromSearch(row, { onError } = {}) {
  if (!row?.id) return;
  const sid = String(row.id);
  try {
    const data = await musicResolveSong({ url: sid });
    const title = data.name || row.name || "未知曲目";
    const artist = data.ar_name || row.artists || "—";
    const picUrl = data.pic || row.picUrl || "";
    const streamUrl = data.url || "";
    const lyricRaw = data.lyric != null ? String(data.lyric) : "";
    if (!streamUrl) {
      onError?.("未获取到播放地址");
      return;
    }
    playQueue.value = playQueue.value.filter((q) => q.songId !== sid);
    enforceQueueCapBeforePush();
    const item = {
      key: makeQueueKey(),
      songId: sid,
      title,
      artist,
      picUrl,
      streamUrl,
      lyricRaw,
    };
    playQueue.value.push(item);
    currentQueueKey.value = item.key;
    applyPlaybackFromItem(item);
    nextTick(() => audioRef.value?.play?.().catch(() => {}));
  } catch (e) {
    onError?.(sanitizeMusicError(e));
  }
}

/** 歌词扫光（与 MusicLyricsAppleView 模板共用） */
export function lineKaraokeDisplayText(line) {
  if (!line?.words?.length) return line?.text ?? "";
  const raw = String(line.text ?? "");
  if (raw.trim() !== "") return raw;
  return line.words.map((w) => w.text).join("");
}

export function lineSweepMaskStyle(pct) {
  const p = Math.min(100, Math.max(0, pct));
  const solid = {
    WebkitMaskImage: "linear-gradient(to right, #000 0%, #000 100%)",
    maskImage: "linear-gradient(to right, #000 0%, #000 100%)",
  };
  if (p >= 99.999) return solid;
  const fadeIn = 1.4;
  const fadeOut = 1.8;
  const t0 = Math.max(0, p - fadeIn);
  const t1 = Math.min(100, p + fadeOut);
  const img = `linear-gradient(to right, #000 0%, #000 ${t0}%, transparent ${t1}%)`;
  return {
    WebkitMaskImage: img,
    maskImage: img,
  };
}

/**
 * 扫光宽度 0–100。
 * 有逐字 words 时按「词时间戳 + 字符权重」推进，避免整行线性扫过把未唱词先点亮（英文常见）。
 * 无 words 时仍用「本行 → 下一行」线性。
 */
export function lineSweepWidthPct(line, lineIdx) {
  const ms = lyricsClockMs.value;
  const lines = lyricsLines.value;
  const lineEndMs = calcLineEndMs(lines, lineIdx);

  if (ms <= line.timeMs) return 0;
  if (ms >= lineEndMs) return 100;

  const words = line.words;
  if (words?.length >= 1) {
    if (ms < (words[0]?.timeMs ?? line.timeMs)) return 0;

    const lens = words.map((w) => Math.max(1, String(w?.text ?? "").length));
    const totalLen = lens.reduce((a, b) => a + b, 0);
    const denom = Math.max(1, totalLen);

    let acc = 0;
    for (let i = 0; i < words.length; i++) {
      const wStart = words[i].timeMs;
      const rawWEnd = i + 1 < words.length ? words[i + 1].timeMs : lineEndMs;
      const wEnd = Math.min(rawWEnd, lineEndMs);
      const len = lens[i];
      const dur = Math.max(50, wEnd - wStart);

      if (ms < wStart) {
        return Math.min(100, (acc / denom) * 100);
      }
      if (ms < wEnd) {
        const frac = (ms - wStart) / dur;
        return Math.min(100, ((acc + frac * len) / denom) * 100);
      }
      acc += len;
    }
    return 100;
  }

  const start = line.timeMs;
  const end = lineEndMs;
  const raw = (ms - start) / Math.max(80, end - start);
  return Math.min(100, Math.max(0, raw * 100));
}

