<script setup>
import { ref, computed, watch } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { toast } from "../../utils/toast.js";
import BaseModal from "../ui/BaseModal.vue";

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  conversationId: { type: String, default: "" },
  /** 会话展示名（私聊对方昵称 / 群名） */
  displayName: { type: String, default: "" },
});

const emit = defineEmits(["update:modelValue", "pick"]);

const keyword = ref("");
const list = ref(/** @type {any[]} */ ([]));
const total = ref(0);
const currentPage = ref(0);
const loading = ref(false);
const loadingMore = ref(false);
const inputRef = ref(null);

const titleText = computed(() => {
  const n = String(props.displayName || "").trim() || "会话";
  return `与「${n}」的聊天记录`;
});

const hasMore = computed(() => list.value.length > 0 && list.value.length < total.value);

function close() {
  emit("update:modelValue", false);
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function highlightHtml(text, kw) {
  const t = String(text ?? "");
  const k = String(kw ?? "").trim();
  if (!k) return escapeHtml(t);
  let pattern;
  try {
    pattern = new RegExp(k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
  } catch {
    return escapeHtml(t);
  }
  let out = "";
  let last = 0;
  let m;
  while ((m = pattern.exec(t)) !== null) {
    out += escapeHtml(t.slice(last, m.index));
    out += `<span class="chat-find-rec__hit">${escapeHtml(m[0])}</span>`;
    last = m.index + m[0].length;
    if (m[0].length === 0) {
      pattern.lastIndex++;
      if (pattern.lastIndex > t.length) break;
    }
  }
  out += escapeHtml(t.slice(last));
  return out;
}

function recordPreview(item) {
  const ct = String(item?.contentType || "").toUpperCase();
  const c = item?.content || {};
  if (ct === "TEXT") return String(c.text || "").trim() || "[文本]";
  if (ct === "SYSTEM") return String(c.systemText || c.text || "").trim() || "[系统]";
  if (ct === "IMAGE") return "[图片]";
  if (ct === "FORWARD_VIDEO") return "[转发视频]";
  if (ct === "FORWARD_DYNAMIC") return "[转发动态]";
  if (ct === "FILE" || ct === "VIDEO") return String(c.fileName || "").trim() || "[文件]";
  return String(c.text || "").trim() || "[消息]";
}

function formatHHmm(d) {
  const h = d.getHours();
  const m = d.getMinutes();
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

const WEEK_CN = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"];

function formatRecordTime(val) {
  let d;
  if (Array.isArray(val) && val.length >= 3) {
    const y = Number(val[0]);
    const mo = Number(val[1]) - 1;
    const day = Number(val[2]);
    const h = Number(val[3] ?? 0);
    const mi = Number(val[4] ?? 0);
    d = new Date(y, mo, day, h, mi, Number(val[5] ?? 0));
  } else {
    d = new Date(val);
  }
  if (Number.isNaN(d.getTime())) return "";
  const now = new Date();
  const startOf = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  if (startOf(d) === startOf(now)) return formatHHmm(d);
  const diff = now.getTime() - d.getTime();
  const weekMs = 7 * 24 * 60 * 60 * 1000;
  if (diff >= 0 && diff < weekMs && d <= now) {
    return `${WEEK_CN[d.getDay()]} ${formatHHmm(d)}`;
  }
  return `${d.getMonth() + 1}月${d.getDate()}日 ${formatHHmm(d)}`;
}

function senderNick(item) {
  return String(item?.senderNickname || item?.senderNickName || "").trim() || "用户";
}

function senderAv(item) {
  return sanitizeImageUrl(item?.senderAvatarUrl) || DEFAULT_PROFILE_AVATAR_URL;
}

function onAvError(ev) {
  const t = ev?.target;
  if (t instanceof HTMLImageElement) {
    t.onerror = null;
    t.src = DEFAULT_PROFILE_AVATAR_URL;
  }
}

let debounceTimer = null;

async function fetchPage(reset) {
  const id = String(props.conversationId || "").trim();
  const kw = keyword.value.trim();
  if (!id || !kw) {
    if (reset) {
      list.value = [];
      total.value = 0;
      currentPage.value = 0;
    }
    return;
  }
  const nextPage = reset ? 1 : currentPage.value + 1;
  if (reset) {
    loading.value = true;
  } else {
    loadingMore.value = true;
  }
  try {
    const data = await chatApi.findChatRecords(id, {
      keyword: kw,
      pageNum: nextPage,
      pageSize: 20,
    });
    const rows = Array.isArray(data?.list) ? data.list : [];
    total.value = Number(data?.total) || 0;
    if (reset) {
      list.value = rows;
    } else {
      list.value = [...list.value, ...rows];
    }
    currentPage.value = nextPage;
  } catch (e) {
    if (reset) {
      list.value = [];
      total.value = 0;
      currentPage.value = 0;
    }
    toast(e?.message || "搜索失败", "error");
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function scheduleSearch() {
  window.clearTimeout(debounceTimer);
  debounceTimer = window.setTimeout(() => {
    debounceTimer = null;
    void fetchPage(true);
  }, 360);
}

function onLoadMore() {
  if (!hasMore.value || loading.value || loadingMore.value) return;
  void fetchPage(false);
}

function onPickRow(item) {
  const seq = Number(item?.seq);
  if (!Number.isFinite(seq) || seq <= 0) {
    toast("无法定位：缺少消息序号", "info");
    return;
  }
  emit("pick", { seq });
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      keyword.value = "";
      list.value = [];
      total.value = 0;
      currentPage.value = 0;
      window.setTimeout(() => inputRef.value?.focus?.(), 80);
    } else {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
  }
);

watch(keyword, () => {
  if (!props.modelValue) return;
  if (!keyword.value.trim()) {
    window.clearTimeout(debounceTimer);
    list.value = [];
    total.value = 0;
    currentPage.value = 0;
    return;
  }
  scheduleSearch();
});
</script>

<template>
  <BaseModal
    :open="modelValue"
    variant="light"
    overlay-class="chat-find-rec-overlay"
    card-class="chat-find-rec"
    aria-labelledby="chat-find-rec-title"
    @close="close"
  >
        <header class="chat-find-rec__head">
          <h2 id="chat-find-rec-title" class="chat-find-rec__title">{{ titleText }}</h2>
          <div class="chat-find-rec__win-btns">
            <button type="button" class="chat-find-rec__win-btn" aria-label="关闭" @click="close">
              <i class="ri-close-line" aria-hidden="true"></i>
            </button>
          </div>
        </header>

        <div class="chat-find-rec__search">
          <i class="ri-search-line chat-find-rec__search-ico" aria-hidden="true"></i>
          <input
            ref="inputRef"
            v-model="keyword"
            type="search"
            class="chat-find-rec__input"
            placeholder="搜索"
            enterkeyhint="search"
            autocomplete="off"
          />
        </div>

        <div class="chat-find-rec__body">
          <p v-if="loading" class="chat-find-rec__hint">搜索中…</p>
          <p v-else-if="!keyword.trim()" class="chat-find-rec__hint chat-find-rec__hint--muted">输入关键词搜索聊天记录</p>
          <p v-else-if="!list.length" class="chat-find-rec__hint chat-find-rec__hint--muted">未找到相关记录</p>
          <ul v-else class="chat-find-rec__list">
            <li
              v-for="(item, idx) in list"
              :key="`${item.seq ?? idx}-${idx}`"
              class="chat-find-rec__row"
              role="button"
              tabindex="0"
              @click="onPickRow(item)"
              @keydown.enter.prevent="onPickRow(item)"
            >
              <img class="chat-find-rec__av" :src="senderAv(item)" alt="" @error="onAvError" />
              <div class="chat-find-rec__mid">
                <div class="chat-find-rec__meta">
                  <span class="chat-find-rec__nick">{{ senderNick(item) }}</span>
                  <span class="chat-find-rec__time">{{ formatRecordTime(item.createdAt) }}</span>
                </div>
                <!-- highlightHtml 逐段 escapeHtml，只注入固定的高亮 span，无注入面 -->
                <!-- eslint-disable vue/no-v-html -->
                <p
                  class="chat-find-rec__preview"
                  v-html="highlightHtml(recordPreview(item), keyword)"
                />
                <!-- eslint-enable vue/no-v-html -->
              </div>
            </li>
          </ul>
          <button
            v-if="hasMore && !loading"
            type="button"
            class="chat-find-rec__more"
            :disabled="loadingMore"
            @click="onLoadMore"
          >
            {{ loadingMore ? "加载中…" : "加载更多" }}
          </button>
        </div>
  </BaseModal>
</template>

<style scoped>
/* —— 查找聊天记录（会话详情入口）—— */
.chat-find-rec-overlay {
  z-index: 12060;
  align-items: flex-start;
  padding-top: 6vh;
}

.chat-find-rec {
  width: min(440px, 94vw);
  max-height: min(88vh, 720px);
  margin: 0 auto;
  background: var(--slate-100);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-modal);
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-find-rec__head {
  position: relative;
  padding: var(--space-16) var(--space-44) var(--space-12);
  background: var(--slate-100);
  border-bottom: 1px solid var(--slate-200);
}

.chat-find-rec__title {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 600;
  color: var(--color-text-strong);
  text-align: center;
  line-height: 1.35;
}

.chat-find-rec__win-btns {
  position: absolute;
  top: 10px;
  right: 8px;
  display: flex;
  gap: var(--space-2);
}

.chat-find-rec__win-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--radius-lg);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-22);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-find-rec__win-btn:hover {
  background: rgb(var(--slate-900-rgb) / 0.06);
  color: var(--color-text-strong);
}

.chat-find-rec__search {
  margin: var(--space-14) var(--space-16) 0;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--space-8);
  padding: 0 var(--space-12);
  height: 44px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--teal-300);
  background: var(--color-bg-surface);
  box-sizing: border-box;
}

.chat-find-rec__search-ico {
  flex-shrink: 0;
  font-size: var(--font-size-18);
  color: var(--color-text-faint);
}

.chat-find-rec__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: var(--font-size-15);
  color: var(--color-text-strong);
}

.chat-find-rec__input::placeholder {
  color: var(--color-text-faint);
}

.chat-find-rec__body {
  flex: 1;
  min-height: 200px;
  max-height: min(58vh, 520px);
  overflow-y: auto;
  margin-top: var(--space-12);
  padding: 0 0 var(--space-14);
}

.chat-find-rec__hint {
  margin: 0;
  padding: var(--space-28) var(--space-20);
  text-align: center;
  font-size: var(--font-size-14);
  color: var(--color-text-muted);
}

.chat-find-rec__hint--muted {
  color: var(--color-text-faint);
}

.chat-find-rec__list {
  list-style: none;
  margin: 0;
  padding: 0;
  background: var(--color-bg-surface);
  border-top: 1px solid var(--slate-200);
}

.chat-find-rec__row {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: var(--space-10);
  padding: var(--space-12) var(--space-14);
  border-bottom: 1px solid var(--color-border-subtle);
  cursor: pointer;
  outline: none;
}

.chat-find-rec__row:hover {
  background: var(--color-bg-sunken);
}

.chat-find-rec__row:focus-visible {
  background: var(--color-bg-hover);
}

.chat-find-rec__av {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  object-fit: cover;
  flex-shrink: 0;
  background: var(--slate-200);
}

.chat-find-rec__mid {
  flex: 1;
  min-width: 0;
}

.chat-find-rec__meta {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-8);
  margin-bottom: var(--space-4);
}

.chat-find-rec__nick {
  font-size: var(--font-size-13);
  color: var(--color-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-find-rec__time {
  flex-shrink: 0;
  font-size: var(--font-size-12);
  color: var(--color-text-faint);
}

.chat-find-rec__preview {
  margin: 0;
  font-size: var(--font-size-15);
  line-height: 1.45;
  color: var(--color-text-secondary);
  word-break: break-word;
}

.chat-find-rec__hit {
  color: var(--teal-600);
  font-weight: 600;
}

.chat-find-rec__more {
  display: block;
  width: calc(100% - 32px);
  margin: var(--space-12) auto 0;
  padding: var(--space-10);
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--font-size-14);
  font-weight: 600;
  cursor: pointer;
}

.chat-find-rec__more:hover:not(:disabled) {
  background: var(--color-bg-sunken);
}

.chat-find-rec__more:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
