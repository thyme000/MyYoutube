<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from "vue";
import * as chatApi from "../../api/chatApi.js";

/** 高于短视频评论侧栏等浮层（约 4500） */
const POP_Z = 8000;
const VIEW_MARGIN = 10;
const GAP = 8;

const props = defineProps({
  disabled: { type: Boolean, default: false },
});

const emit = defineEmits(["pick"]);

/** 与库 category_code、后端 EmojiItem 一致 */
const CATEGORIES = [
  { code: "expression", label: "表情" },
  { code: "people", label: "人物" },
  { code: "hands", label: "手部" },
  { code: "daily", label: "日常" },
  { code: "animals", label: "动物" },
  { code: "plants", label: "植物" },
  { code: "food_drink", label: "饮食" },
  { code: "sports_culture", label: "文体" },
  { code: "travel", label: "旅游" },
  { code: "objects", label: "物品" },
  { code: "clocks", label: "时钟" },
  { code: "hearts", label: "心形" },
  { code: "moon", label: "月亮" },
  { code: "office", label: "办公" },
  { code: "arrows", label: "箭头" },
  { code: "signs", label: "标志" },
  { code: "transport", label: "交通" },
  { code: "buildings", label: "建筑" },
  { code: "currency", label: "货币" },
  { code: "shapes", label: "图形" },
  { code: "zodiac", label: "星座" },
  { code: "nature", label: "自然" },
  { code: "flags", label: "国旗" },
];

const open = ref(false);
const wrapRef = ref(null);
const popRef = ref(null);
/** fixed 定位（Teleport 到 body，避免侧栏 / 详情 overflow 裁切与贴右溢出） */
const popStyle = ref({});
const activeCode = ref(CATEGORIES[0].code);
const items = ref([]);
const loading = ref(false);
const loadError = ref("");

let docBound = false;

function sortItems(list) {
  return [...(list || [])].sort((a, b) => (Number(a?.sortOrder) || 0) - (Number(b?.sortOrder) || 0));
}

async function fetchCategory(code) {
  loading.value = true;
  loadError.value = "";
  items.value = [];
  try {
    const raw = await chatApi.listEmojisByCategory(code);
    const list = Array.isArray(raw) ? raw : [];
    items.value = sortItems(list);
  } catch (e) {
    loadError.value = e?.message || "加载失败";
    items.value = [];
  } finally {
    loading.value = false;
  }
}

function togglePanel() {
  if (props.disabled) return;
  open.value = !open.value;
}

function onDocPointerDown(ev) {
  const wrap = wrapRef.value;
  const pop = popRef.value;
  const t = ev.target;
  if (!(t instanceof Node)) return;
  if (wrap?.contains(t) || pop?.contains(t)) return;
  open.value = false;
}

function bindDocClose() {
  if (docBound) return;
  document.addEventListener("pointerdown", onDocPointerDown, true);
  docBound = true;
}

function unbindDocClose() {
  if (!docBound) return;
  document.removeEventListener("pointerdown", onDocPointerDown, true);
  docBound = false;
}

function updatePopPosition() {
  if (!open.value) return;
  const wrap = wrapRef.value;
  const pop = popRef.value;
  if (!wrap || !pop) return;

  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const maxW = Math.min(360, vw - 2 * VIEW_MARGIN);
  const rect = wrap.getBoundingClientRect();
  const ph = Math.max(pop.getBoundingClientRect().height, 120);

  let top = rect.top - GAP - ph;
  if (top < VIEW_MARGIN) {
    const below = rect.bottom + GAP;
    if (below + ph <= vh - VIEW_MARGIN) {
      top = below;
    } else {
      top = Math.max(VIEW_MARGIN, Math.min(rect.top - GAP - ph, vh - VIEW_MARGIN - ph));
    }
  } else {
    top = Math.min(top, vh - VIEW_MARGIN - ph);
  }

  let left = rect.right - maxW;
  left = Math.max(VIEW_MARGIN, Math.min(left, vw - VIEW_MARGIN - maxW));

  popStyle.value = {
    position: "fixed",
    top: `${Math.round(top)}px`,
    left: `${Math.round(left)}px`,
    width: `${Math.round(maxW)}px`,
    zIndex: String(POP_Z),
  };
}

function schedulePopPosition() {
  requestAnimationFrame(() => {
    updatePopPosition();
    requestAnimationFrame(() => updatePopPosition());
  });
}

function onViewportChange() {
  if (open.value) updatePopPosition();
}

watch(open, (v) => {
  if (v) {
    void fetchCategory(activeCode.value);
    nextTick(() => {
      bindDocClose();
      window.addEventListener("resize", onViewportChange);
      window.addEventListener("scroll", onViewportChange, true);
      schedulePopPosition();
    });
  } else {
    unbindDocClose();
    window.removeEventListener("resize", onViewportChange);
    window.removeEventListener("scroll", onViewportChange, true);
    popStyle.value = {};
  }
});

watch(activeCode, (code) => {
  if (open.value && code) void fetchCategory(code);
});

watch([() => loading.value, () => items.value], () => {
  if (open.value) nextTick(() => schedulePopPosition());
});

function selectCategory(code) {
  activeCode.value = code;
}

function onPickItem(row) {
  const g = row?.glyph != null ? String(row.glyph) : "";
  if (!g) return;
  emit("pick", g);
  open.value = false;
}

onBeforeUnmount(() => {
  unbindDocClose();
  window.removeEventListener("resize", onViewportChange);
  window.removeEventListener("scroll", onViewportChange, true);
});
</script>

<template>
  <div ref="wrapRef" class="chat-emoji-wrap">
    <button
      type="button"
      class="chat-msg-attach chat-msg-attach--emoji"
      :disabled="disabled"
      :class="{ 'chat-msg-attach--emoji-on': open }"
      title="表情"
      aria-label="打开表情面板"
      :aria-expanded="open ? 'true' : 'false'"
      @click.stop="togglePanel"
    >
      <i class="ri-emotion-happy-line" aria-hidden="true"></i>
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        ref="popRef"
        class="chat-msg-emoji-pop chat-msg-emoji-pop--portal"
        role="dialog"
        aria-label="表情选择"
        :style="popStyle"
        @click.stop
      >
        <div class="chat-msg-emoji-tabs" role="tablist">
          <button
            v-for="c in CATEGORIES"
            :key="c.code"
            type="button"
            role="tab"
            class="chat-msg-emoji-tab"
            :class="{ 'chat-msg-emoji-tab--active': activeCode === c.code }"
            @click="selectCategory(c.code)"
          >
            {{ c.label }}
          </button>
        </div>
        <div
          class="chat-msg-emoji-body"
          :class="{ 'chat-msg-emoji-body--centered': loading || loadError || !items.length }"
        >
          <p v-if="loading" class="chat-msg-emoji-hint">加载中…</p>
          <p v-else-if="loadError" class="chat-msg-emoji-err">{{ loadError }}</p>
          <p v-else-if="!items.length" class="chat-msg-emoji-hint">该分类暂无数据</p>
          <div v-else class="chat-msg-emoji-grid">
            <button
              v-for="row in items"
              :key="String(row.id ?? row.glyph) + '-' + (row.nameZh || '')"
              type="button"
              class="chat-msg-emoji-cell"
              :title="String(row.nameZh || '').trim() || row.glyph"
              @click="onPickItem(row)"
            >
              <span class="chat-msg-emoji-glyph" aria-hidden="true">{{ row.glyph }}</span>
              <span class="chat-msg-emoji-name">{{ String(row.nameZh || "").trim() || " " }}</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.chat-emoji-wrap {
  position: relative;
  flex-shrink: 0;
}

.chat-msg-attach--emoji-on {
  border-color: #07c160;
  color: #059669;
  background: #ecfdf5;
}

.chat-msg-emoji-pop {
  width: min(360px, calc(100vw - 24px));
  max-height: min(340px, 48vh);
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #fff;
  box-shadow: 0 10px 40px rgba(15, 23, 42, 0.15);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Teleport 到 body：top/left/width 由 ChatEmojiPicker 内联样式写入 */
.chat-msg-emoji-pop--portal {
  position: fixed;
  margin: 0;
}

.chat-msg-emoji-tabs {
  flex-shrink: 0;
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  padding: 8px 8px 6px;
  overflow-x: auto;
  border-bottom: 1px solid #e2e8f0;
  background: #f8fafc;
}

.chat-msg-emoji-tab {
  flex: 0 0 auto;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: transparent;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
  white-space: nowrap;
}

.chat-msg-emoji-tab:hover {
  color: #0f172a;
}

.chat-msg-emoji-tab--active {
  background: #fff;
  border-color: #e2e8f0;
  color: #0f172a;
  font-weight: 600;
}

.chat-msg-emoji-body {
  flex-shrink: 0;
  box-sizing: border-box;
  /* 固定高度，切换分类 / 加载时面板整体不上下跳动 */
  height: 260px;
  min-height: 260px;
  max-height: 260px;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px;
}

.chat-msg-emoji-body--centered {
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-msg-emoji-hint,
.chat-msg-emoji-err {
  margin: 0;
  padding: 12px 8px;
  font-size: 13px;
  text-align: center;
  color: #94a3b8;
}

.chat-msg-emoji-err {
  color: #b91c1c;
}

.chat-msg-emoji-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.chat-msg-emoji-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 4px;
  padding: 8px 4px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #fff;
  cursor: pointer;
  min-height: 64px;
  text-align: center;
  box-sizing: border-box;
}

.chat-msg-emoji-cell:hover {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.chat-msg-emoji-glyph {
  font-size: 22px;
  line-height: 1.2;
}

.chat-msg-emoji-name {
  font-size: 10px;
  line-height: 1.25;
  color: #64748b;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
</style>
