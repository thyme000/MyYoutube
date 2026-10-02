<script setup>
// @ts-check
/**
 * 我的音频管理: 试听、公开/私密切换、删除。
 * 在先使用权: 删除/转私密不影响已发布动态(持快照)。
 */
import { ref, watch } from "vue";
import BaseModal from "./ui/BaseModal.vue";
import { fetchMyAudios, setAudioVisibility, deleteAudio } from "../api/audioApi.js";
import { useToastStore } from "../stores/toastStore";

const props = defineProps({
  open: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "changed"]);

const toastStore = useToastStore();
const rows = ref([]);
const loading = ref(false);
const pageNo = ref(1);
const pages = ref(0);
const playingId = ref("");
let playingEl = null;

watch(
  () => props.open,
  (open) => {
    if (open) load(true);
    else stopPreview();
  }
);

async function load(reset) {
  const page = reset ? 1 : pageNo.value + 1;
  if (!reset && pages.value && page > pages.value) return;
  loading.value = true;
  try {
    const { pages: ps, list } = await fetchMyAudios({ pageNo: page, pageSize: 20 });
    rows.value = reset ? list : [...rows.value, ...list];
    pages.value = ps;
    pageNo.value = page;
  } catch (e) {
    toastStore.pushToast(e?.message || "加载音频失败", "error");
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

async function toggleVisibility(row) {
  try {
    await setAudioVisibility(row.id, row.visibility === "public" ? "private" : "public");
    row.visibility = row.visibility === "public" ? "private" : "public";
    emit("changed");
  } catch (e) {
    toastStore.pushToast(e?.message || "切换失败", "error");
  }
}

async function remove(row) {
  if (!window.confirm(`删除音频「${row.title}」？已发布动态不受影响。`)) return;
  try {
    await deleteAudio(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    stopPreview();
    emit("changed");
    toastStore.pushToast("已删除", "success");
  } catch (e) {
    toastStore.pushToast(e?.message || "删除失败", "error");
  }
}

function fmt(ms) {
  const s = Math.max(0, Math.round((ms || 0) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
</script>

<template>
  <BaseModal :open="props.open" card-class="dynpub-card" width="min(560px, 92vw)" aria-label="我的音频">
    <div class="myaud">
      <header class="myaud-head">
        <h3>我的音频</h3>
        <button type="button" class="myaud-x" @click="emit('close')">×</button>
      </header>
      <p class="myaud-note">
        私密音频别人搜不到也选不了；已发布动态不受转私密/删除影响。
      </p>
      <div class="myaud-list">
        <p v-if="!loading && !rows.length" class="myaud-empty">还没有上传过音频</p>
        <div v-for="row in rows" :key="row.id" class="myaud-row">
          <button type="button" class="myaud-play" @click="togglePreview(row)">
            <i :class="playingId === row.id ? 'ri-pause-line' : 'ri-play-line'"></i>
          </button>
          <span class="myaud-title">{{ row.title }}</span>
          <span class="myaud-badge" :class="{ 'myaud-badge--private': row.visibility === 'private' }">
            {{ row.visibility === "public" ? "公开" : "私密" }}
          </span>
          <span class="myaud-dur">{{ fmt(row.durationMs) }}</span>
          <button type="button" class="myaud-op" @click="toggleVisibility(row)">
            {{ row.visibility === "public" ? "转私密" : "转公开" }}
          </button>
          <button type="button" class="myaud-op myaud-op--danger" @click="remove(row)">删除</button>
        </div>
        <p v-if="loading" class="myaud-empty">加载中…</p>
        <button
          v-else-if="rows.length && pageNo < pages"
          type="button"
          class="myaud-more"
          @click="load(false)"
        >加载更多</button>
      </div>
    </div>
  </BaseModal>
</template>

<style scoped>
.myaud {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.myaud-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.myaud-head h3 {
  margin: 0;
  font-size: 16px;
}
.myaud-x {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
}
.myaud-note {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500, #999);
}
.myaud-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 100px;
}
.myaud-empty {
  margin: 0;
  font-size: 13px;
  color: var(--ink-500, #999);
  text-align: center;
  padding: 18px 0;
}
.myaud-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
}
.myaud-play {
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
.myaud-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.myaud-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgb(255 255 255 / 0.12);
}
.myaud-badge--private {
  color: var(--ink-400, #aaa);
}
.myaud-dur {
  font-size: 11px;
  color: var(--ink-500, #999);
  font-variant-numeric: tabular-nums;
}
.myaud-op {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--line, #333);
  border-radius: 13px;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
}
.myaud-op--danger {
  color: #ff6b6b;
  border-color: rgb(255 107 107 / 0.4);
}
.myaud-more {
  border: none;
  background: transparent;
  color: var(--ink-400, #aaa);
  font-size: 12px;
  cursor: pointer;
  align-self: center;
  padding: 6px;
}
</style>
