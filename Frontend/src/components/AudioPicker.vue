<script setup>
// @ts-check
/**
 * 音频选择器: 公开库搜索 + 我的音频 两个页签, 行内试听, 选中回传库条目。
 * 供动态发布与幻灯片合成选 BGM。
 */
import { computed, ref, watch } from "vue";
import BaseModal from "./ui/BaseModal.vue";
import { fetchAudioLibrary, fetchMyAudios } from "../api/audioApi.js";

const props = defineProps({
  open: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "picked"]);

const tab = ref("library");
const keyword = ref("");
const rows = ref([]);
const loading = ref(false);
const err = ref("");
const pageNo = ref(1);
const pages = ref(0);
/** 正在试听的音频 id, 同时只播一个 */
const playingId = ref("");
let playingEl = null;

const keywordDebounced = computed(() => keyword.value);

watch(
  () => [props.open, tab.value, keywordDebounced.value],
  ([open]) => {
    if (!open) return;
    stopPreview();
    load(true);
  },
  { immediate: true }
);

async function load(reset) {
  const page = reset ? 1 : pageNo.value + 1;
  if (!reset && pages.value && page > pages.value) return;
  loading.value = true;
  err.value = "";
  try {
    const fetcher = tab.value === "my" ? fetchMyAudios : fetchAudioLibrary;
    const { total, pages: ps, list } = await fetcher({
      keyword: keywordDebounced.value,
      pageNo: page,
      pageSize: 20,
    });
    rows.value = reset ? list : [...rows.value, ...list];
    pages.value = ps;
    pageNo.value = page;
    void total;
  } catch (e) {
    err.value = e?.message || "加载音频失败";
  } finally {
    loading.value = false;
  }
}

function togglePreview(row) {
  if (playingId.value === row.id) {
    stopPreview();
    return;
  }
  stopPreview();
  const el = new Audio(row.previewUrl);
  el.onended = () => {
    playingId.value = "";
    playingEl = null;
  };
  el.play().catch(() => {});
  playingId.value = row.id;
  playingEl = el;
}

function stopPreview() {
  if (playingEl) {
    try {
      playingEl.pause();
    } catch {
      /* ignore */
    }
  }
  playingEl = null;
  playingId.value = "";
}

function pick(row) {
  stopPreview();
  emit("picked", { id: row.id, title: row.title, durationMs: row.durationMs });
}

function fmt(ms) {
  const s = Math.max(0, Math.round((ms || 0) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
</script>

<template>
  <BaseModal :open="props.open" card-class="dynpub-card" width="min(520px, 92vw)" aria-label="选择音频">
    <div class="apick">
      <header class="apick-head">
        <h3>选择音频</h3>
        <button type="button" class="apick-x" @click="emit('close')">×</button>
      </header>
      <div class="apick-tabs">
        <button type="button" class="apick-tab" :class="{ 'apick-tab--on': tab === 'library' }" @click="tab = 'library'">公开音频库</button>
        <button type="button" class="apick-tab" :class="{ 'apick-tab--on': tab === 'my' }" @click="tab = 'my'">我的音频</button>
      </div>
      <div v-if="tab === 'library'" class="apick-search">
        <i class="ri-search-line"></i>
        <input v-model="keyword" type="text" placeholder="按标题搜索" />
      </div>
      <div class="apick-list">
        <p v-if="err" class="apick-msg">{{ err }}</p>
        <p v-if="!loading && !rows.length && !err" class="apick-msg">
          {{ tab === "my" ? "还没有上传过音频" : "库里还没有音频，发布时上传的音频会自动入库" }}
        </p>
        <div v-for="row in rows" :key="row.id" class="apick-row" :class="{ 'apick-row--playing': playingId === row.id }">
          <button type="button" class="apick-play" @click="togglePreview(row)">
            <i :class="playingId === row.id ? 'ri-pause-line' : 'ri-play-line'"></i>
          </button>
          <span class="apick-title">{{ row.title }}</span>
          <span v-if="tab === 'my' && row.visibility === 'private'" class="apick-badge">私密</span>
          <span class="apick-dur">{{ fmt(row.durationMs) }}</span>
          <button type="button" class="apick-use" @click="pick(row)">使用</button>
        </div>
        <p v-if="loading" class="apick-msg">加载中…</p>
        <button
          v-else-if="rows.length && pageNo < pages"
          type="button"
          class="apick-more"
          @click="load(false)"
        >加载更多</button>
      </div>
    </div>
  </BaseModal>
</template>

<style scoped>
.apick {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.apick-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.apick-head h3 {
  margin: 0;
  font-size: 16px;
}
.apick-x {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
}
.apick-tabs {
  display: flex;
  gap: 8px;
}
.apick-tab {
  height: 30px;
  padding: 0 14px;
  border-radius: 15px;
  border: 1px solid var(--line, #333);
  background: transparent;
  color: inherit;
  font-size: 13px;
  cursor: pointer;
}
.apick-tab--on {
  background: var(--ink-100, #f5f5f7);
  color: #111;
  border-color: transparent;
  font-weight: 600;
}
.apick-search {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line, #333);
  border-radius: 8px;
  padding: 0 10px;
  height: 34px;
}
.apick-search input {
  flex: 1;
  border: none;
  background: transparent;
  color: inherit;
  outline: none;
  font-size: 13px;
}
.apick-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 120px;
}
.apick-msg {
  margin: 0;
  font-size: 13px;
  color: var(--ink-500, #999);
  text-align: center;
  padding: 18px 0;
}
.apick-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
}
.apick-row--playing {
  background: rgb(255 255 255 / 0.06);
}
.apick-play {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.1);
  color: inherit;
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
}
.apick-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.apick-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgb(255 255 255 / 0.12);
  color: var(--ink-300, #ccc);
}
.apick-dur {
  font-size: 11px;
  color: var(--ink-500, #999);
  font-variant-numeric: tabular-nums;
}
.apick-use {
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: 13px;
  background: var(--ink-100, #f5f5f7);
  color: #111;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.apick-more {
  border: none;
  background: transparent;
  color: var(--ink-400, #aaa);
  font-size: 12px;
  cursor: pointer;
  align-self: center;
  padding: 6px;
}
</style>
