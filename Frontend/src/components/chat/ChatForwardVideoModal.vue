<script setup>
import { computed, ref, watch } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { toast } from "../../utils/toast.js";
import BaseModal from "../ui/BaseModal.vue";

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  selfUserId: { type: [String, Number], default: null },
  /** 转发卡片类型: video/dynamic(统一内容池) */
  forwardType: { type: String, default: "video" },
  videoId: { type: [String, Number], default: null },
  videoTitle: { type: String, default: "" },
  videoCoverUrl: { type: String, default: "" },
  dynamicId: { type: [String, Number], default: null },
  dynamicTitle: { type: String, default: "" },
  dynamicCoverUrl: { type: String, default: "" },
});

const emit = defineEmits(["update:modelValue", "sent"]);

const rows = ref(/** @type {any[]} */ ([]));
const pages = ref(1);
const page = ref(1);
const loading = ref(false);
const loadingMore = ref(false);
const error = ref("");
const keyword = ref("");
const selected = ref(/** @type {Record<string, boolean>} */ ({}));
const sending = ref(false);

const isDynamic = computed(() => String(props.forwardType) === "dynamic");
const displayTitle = computed(() => {
  const t = String(isDynamic.value ? props.dynamicTitle : props.videoTitle || "").trim();
  return t || (isDynamic.value ? "动态" : "视频");
});
const displayId = computed(() => (isDynamic.value ? props.dynamicId : props.videoId));
const displayCoverUrl = computed(() => (isDynamic.value ? props.dynamicCoverUrl : props.videoCoverUrl));

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows.value;
  return (rows.value || []).filter((r) => {
    const name = String(r?.conversationName || "").toLowerCase();
    const id = String(r?.conversationId || "").toLowerCase();
    return name.includes(kw) || id.includes(kw);
  });
});

const hasMore = computed(() => page.value < pages.value);

function close() {
  if (sending.value) return;
  emit("update:modelValue", false);
}

function convName(r) {
  return String(r?.conversationName || "").trim() || "会话";
}

function convAvatar(r) {
  return sanitizeImageUrl(r?.conversationAvatar) || DEFAULT_PROFILE_AVATAR_URL;
}

function onAvatarError(ev) {
  const t = ev?.target;
  if (t instanceof HTMLImageElement) {
    t.onerror = null;
    t.src = DEFAULT_PROFILE_AVATAR_URL;
  }
}

function toggleSelectRow(r) {
  const id = String(r?.conversationId || "").trim();
  if (!id) return;
  selected.value = { ...selected.value, [id]: !selected.value[id] };
}

const selectedIds = computed(() =>
  Object.keys(selected.value).filter((id) => selected.value[id])
);

async function fetchPage(reset) {
  const uid = props.selfUserId != null ? String(props.selfUserId) : "";
  const vid = displayId.value != null ? String(displayId.value) : "";
  if (!uid) {
    error.value = "未登录";
    rows.value = [];
    pages.value = 1;
    page.value = 1;
    return;
  }
  if (!vid) {
    error.value = isDynamic.value ? "缺少动态信息" : "缺少视频信息";
    rows.value = [];
    pages.value = 1;
    page.value = 1;
    return;
  }
  if (reset) {
    loading.value = true;
    error.value = "";
    page.value = 1;
  } else {
    loadingMore.value = true;
    error.value = "";
  }
  try {
    const next = reset ? 1 : page.value + 1;
    const data = await chatApi.listUserConversations(next, 30);
    const list = Array.isArray(data?.list) ? data.list : [];
    const p = Number(data?.pages) || 1;
    pages.value = p;
    page.value = next;
    if (reset) {
      rows.value = list;
    } else {
      const seen = new Set(rows.value.map((x) => String(x?.conversationId)));
      const merged = [...rows.value];
      for (const r of list) {
        const id = String(r?.conversationId || "");
        if (!id || seen.has(id)) continue;
        seen.add(id);
        merged.push(r);
      }
      rows.value = merged;
    }
  } catch (e) {
    if (reset) rows.value = [];
    error.value = e?.message || "加载会话失败";
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function onLoadMore() {
  if (!hasMore.value || loading.value || loadingMore.value) return;
  void fetchPage(false);
}

async function onSend() {
  const uid = props.selfUserId != null ? String(props.selfUserId) : "";
  const vid = displayId.value != null ? String(displayId.value) : "";
  if (!uid) {
    toast("请先登录", "info");
    return;
  }
  if (!vid) {
    toast(isDynamic.value ? "缺少动态信息" : "缺少视频信息", "error");
    return;
  }
  const ids = selectedIds.value;
  if (!ids.length) {
    toast("请选择会话", "info");
    return;
  }
  if (sending.value) return;
  sending.value = true;
  try {
    let ok = 0;
    let fail = 0;
    let lastErr = "";
    for (const cid of ids) {
      try {
        const send = isDynamic.value
          ? chatApi.forwardDynamicMessage({
              conversationId: String(cid),
              senderId: String(uid),
              contentType: "FORWARD_DYNAMIC",
              content: { forwardDynamicId: String(vid) },
              clientMsgId: `fwd-${Date.now()}-${Math.random().toString(16).slice(2)}`,
            })
          : chatApi.forwardVideoMessage({
              conversationId: String(cid),
              senderId: String(uid),
              contentType: "FORWARD_VIDEO",
              content: { forwardVideoId: String(vid) },
              clientMsgId: `fwd-${Date.now()}-${Math.random().toString(16).slice(2)}`,
            });
        await send;
        ok += 1;
      } catch (e) {
        fail += 1;
        lastErr = String(e?.message || "发送失败");
      }
    }
    if (ok > 0) {
      emit("sent", { count: ok, fail });
      emit("update:modelValue", false);
      if (fail > 0) {
        toast(lastErr ? `部分会话发送失败：${lastErr}` : "部分会话发送失败", "info");
      }
    } else {
      toast(lastErr || "转发失败", "error");
    }
  } catch (e) {
    toast(e?.message || "转发失败", "error");
  } finally {
    sending.value = false;
  }
}

watch(() => props.modelValue,
  (open) => {
    if (open) {
      keyword.value = "";
      selected.value = {};
      rows.value = [];
      pages.value = 1;
      page.value = 1;
      error.value = "";
      void fetchPage(true);
    }
  }
);
</script>

<template>
  <BaseModal :open="modelValue" variant="light" overlay-class="chat-fwd-overlay" card-class="chat-fwd" @close="close">
        <header class="chat-fwd__head">
          <h2 class="chat-fwd__title">转发「{{ displayTitle }}」</h2>
          <div class="chat-fwd__win-btns">
            <button type="button" class="chat-fwd__win-btn" aria-label="关闭" :disabled="sending" @click="close">
              <i class="ri-close-line" aria-hidden="true"></i>
            </button>
          </div>
        </header>

        <div class="chat-fwd__preview">
          <img class="chat-fwd__cover" :src="sanitizeImageUrl(displayCoverUrl)" alt="" @error="onAvatarError" />
          <div class="chat-fwd__preview-mid">
            <p class="chat-fwd__preview-title">{{ displayTitle }}</p>
            <p class="chat-fwd__preview-sub">选择会话发送</p>
          </div>
        </div>

        <div class="chat-fwd__search">
          <i class="ri-search-line chat-fwd__search-ico" aria-hidden="true"></i>
          <input v-model="keyword" type="search" class="chat-fwd__input" placeholder="搜索会话" autocomplete="off" />
        </div>

        <div class="chat-fwd__body">
          <p v-if="loading" class="chat-fwd__hint">加载中…</p>
          <p v-else-if="error" class="chat-fwd__err">{{ error }}</p>
          <ul v-else class="chat-fwd__list">
            <li
              v-for="r in filteredRows"
              :key="String(r.conversationId)"
              class="chat-fwd__row"
              role="button"
              tabindex="0"
              @click="toggleSelectRow(r)"
              @keydown.enter.prevent="toggleSelectRow(r)"
              @keydown.space.prevent="toggleSelectRow(r)"
            >
              <input
                class="chat-fwd__chk"
                type="checkbox"
                :checked="!!selected[String(r.conversationId)]"
                @click.stop
                @change="toggleSelectRow(r)"
              />
              <img class="chat-fwd__av" :src="convAvatar(r)" alt="" @error="onAvatarError" />
              <span class="chat-fwd__name" :title="convName(r)">{{ convName(r) }}</span>
            </li>
          </ul>
          <button v-if="hasMore && !loading" type="button" class="chat-fwd__more" :disabled="loadingMore" @click="onLoadMore">
            {{ loadingMore ? "加载中…" : "加载更多" }}
          </button>
        </div>

        <footer class="chat-fwd__foot">
          <button type="button" class="chat-fwd__btn chat-fwd__btn--ghost" :disabled="sending" @click="close">取消</button>
          <button type="button" class="chat-fwd__btn chat-fwd__btn--primary" :disabled="sending || selectedIds.length === 0" @click="onSend">
            {{ sending ? "发送中…" : `发送（${selectedIds.length}）` }}
          </button>
        </footer>
  </BaseModal>
</template>


<style scoped>
/* 卡片与遮罩(.chat-fwd/.chat-fwd-overlay)是 cardClass/overlayClass 传给 BaseModal 渲染的,
   带不上本组件 scope id, 样式放全局 main.css */

.chat-fwd__head {
  position: relative;
  padding: var(--space-16) var(--space-44) var(--space-12);
  background: var(--slate-100);
  border-bottom: 1px solid var(--slate-200);
}

.chat-fwd__title {
  margin: 0;
  font-size: var(--font-size-16);
  font-weight: 600;
  color: var(--color-text-strong);
  text-align: center;
  line-height: 1.35;
}

.chat-fwd__win-btns {
  position: absolute;
  top: 10px;
  right: 8px;
  display: flex;
  gap: var(--space-2);
}

.chat-fwd__win-btn {
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

.chat-fwd__win-btn:hover:not(:disabled) {
  background: rgb(var(--slate-900-rgb) / 0.06);
  color: var(--color-text-strong);
}

.chat-fwd__win-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-fwd__preview {
  margin: var(--space-14) var(--space-16) 0;
  display: flex;
  gap: var(--space-12);
  align-items: center;
  padding: var(--space-10) var(--space-12);
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
}

.chat-fwd__cover {
  width: 54px;
  height: 54px;
  border-radius: var(--radius-xl);
  object-fit: cover;
  background: var(--slate-200);
  flex-shrink: 0;
}

.chat-fwd__preview-mid {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.chat-fwd__preview-title {
  margin: 0;
  font-size: var(--font-size-14);
  font-weight: 600;
  color: var(--color-text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-fwd__preview-sub {
  margin: 0;
  font-size: var(--font-size-12);
  color: var(--color-text-muted);
}

.chat-fwd__search {
  margin: var(--space-12) var(--space-16) 0;
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

.chat-fwd__search-ico {
  color: var(--green-600);
  font-size: var(--font-size-18);
}

.chat-fwd__input {
  flex: 1;
  border: none;
  outline: none;
  font-size: var(--font-size-14);
  background: transparent;
  color: var(--color-text-strong);
}

.chat-fwd__input::placeholder {
  color: var(--color-text-faint);
}

.chat-fwd__body {
  padding: var(--space-12) var(--space-12) var(--space-10);
  overflow: auto;
  flex: 1;
}

.chat-fwd__hint,
.chat-fwd__err {
  margin: var(--space-12) 0;
  text-align: center;
  color: var(--color-text-muted);
  font-size: var(--font-size-13);
}

.chat-fwd__err {
  color: var(--red-500);
  font-weight: 600;
}

.chat-fwd__hint--muted {
  color: var(--color-text-faint);
}

.chat-fwd__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
}

.chat-fwd__row {
  display: grid;
  grid-template-columns: 18px 36px 1fr;
  align-items: center;
  gap: var(--space-10);
  padding: var(--space-10) var(--space-10);
  border-radius: var(--radius-xl);
  cursor: pointer;
  outline: none;
}

.chat-fwd__row:hover {
  background: rgb(var(--slate-900-rgb) / 0.06);
}

.chat-fwd__row:focus-visible {
  outline: 2px solid var(--sky-500);
  outline-offset: 2px;
}

.chat-fwd__chk {
  width: 16px;
  height: 16px;
}

.chat-fwd__av {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-xl);
  object-fit: cover;
  background: var(--slate-200);
}

.chat-fwd__name {
  font-size: var(--font-size-14);
  color: var(--color-text-strong);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-fwd__more {
  margin: var(--space-12) auto 0;
  display: block;
  border: 1px solid var(--color-border);
  background: var(--color-bg-surface);
  color: var(--color-text-strong);
  border-radius: var(--radius-xl);
  padding: var(--space-10) var(--space-14);
  cursor: pointer;
}

.chat-fwd__more:hover:not(:disabled) {
  background: var(--color-bg-hover);
}

.chat-fwd__more:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-fwd__foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-10);
  padding: var(--space-12) var(--space-12) var(--space-14);
  border-top: 1px solid var(--slate-200);
  background: var(--slate-100);
}

.chat-fwd__btn {
  height: 38px;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  cursor: pointer;
  font-size: var(--font-size-14);
}

.chat-fwd__btn--ghost {
  background: var(--color-bg-surface);
  color: var(--color-text-strong);
}

.chat-fwd__btn--primary {
  background: var(--green-600);
  color: var(--color-text-inverse);
  border-color: var(--green-600);
}

.chat-fwd__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
