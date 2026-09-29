<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { toast } from "../../utils/toast.js";

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  selfUserId: { type: [String, Number], default: null },
  videoId: { type: [String, Number], default: null },
  videoTitle: { type: String, default: "" },
  videoCoverUrl: { type: String, default: "" },
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

const displayTitle = computed(() => String(props.videoTitle || "").trim() || "视频");

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
  const vid = props.videoId != null ? String(props.videoId) : "";
  if (!uid) {
    error.value = "未登录";
    rows.value = [];
    pages.value = 1;
    page.value = 1;
    return;
  }
  if (!vid) {
    error.value = "缺少视频信息";
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
  const vid = props.videoId != null ? String(props.videoId) : "";
  if (!uid) {
    toast("请先登录", "info");
    return;
  }
  if (!vid) {
    toast("缺少视频信息", "error");
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
        await chatApi.forwardVideoMessage({
          conversationId: String(cid),
          senderId: String(uid),
          contentType: "FORWARD_VIDEO",
          content: { forwardVideoId: String(vid) },
          clientMsgId: `fwd-${Date.now()}-${Math.random().toString(16).slice(2)}`,
        });
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

function onGlobalKeyDown(ev) {
  if (!props.modelValue) return;
  if (ev?.key === "Escape") close();
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      keyword.value = "";
      selected.value = {};
      rows.value = [];
      pages.value = 1;
      page.value = 1;
      error.value = "";
      void fetchPage(true);
      window.addEventListener("keydown", onGlobalKeyDown);
    } else {
      window.removeEventListener("keydown", onGlobalKeyDown);
    }
  }
);

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onGlobalKeyDown);
});
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="modal-overlay modal-overlay--light chat-fwd-overlay" @click.self="close">
      <div class="chat-fwd" role="dialog" aria-modal="true" @click.stop>
        <header class="chat-fwd__head">
          <h2 class="chat-fwd__title">转发「{{ displayTitle }}」</h2>
          <div class="chat-fwd__win-btns">
            <button type="button" class="chat-fwd__win-btn" aria-label="关闭" :disabled="sending" @click="close">
              <i class="ri-close-line" aria-hidden="true"></i>
            </button>
          </div>
        </header>

        <div class="chat-fwd__preview">
          <img class="chat-fwd__cover" :src="sanitizeImageUrl(videoCoverUrl)" alt="" @error="onAvatarError" />
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
          <p v-else-if="!filteredRows.length" class="chat-fwd__hint chat-fwd__hint--muted">暂无会话</p>
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
      </div>
    </div>
  </Teleport>
</template>


<style scoped>
/* —— 转发视频到会话 —— */
.chat-fwd-overlay {
  z-index: 12060;
  align-items: flex-start;
  padding-top: 6vh;
}

.chat-fwd {
  width: min(440px, 94vw);
  max-height: min(88vh, 720px);
  margin: 0 auto;
  background: #f4f6f8;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.18);
  border: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-fwd__head {
  position: relative;
  padding: 16px 44px 12px;
  background: #eef1f4;
  border-bottom: 1px solid #e5e9ef;
}

.chat-fwd__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  text-align: center;
  line-height: 1.35;
}

.chat-fwd__win-btns {
  position: absolute;
  top: 10px;
  right: 8px;
  display: flex;
  gap: 2px;
}

.chat-fwd__win-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  font-size: 22px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-fwd__win-btn:hover:not(:disabled) {
  background: rgba(15, 23, 42, 0.06);
  color: #0f172a;
}

.chat-fwd__win-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-fwd__preview {
  margin: 14px 16px 0;
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #fff;
}

.chat-fwd__cover {
  width: 54px;
  height: 54px;
  border-radius: 10px;
  object-fit: cover;
  background: #e2e8f0;
  flex-shrink: 0;
}

.chat-fwd__preview-mid {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chat-fwd__preview-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-fwd__preview-sub {
  margin: 0;
  font-size: 12px;
  color: #64748b;
}

.chat-fwd__search {
  margin: 12px 16px 0;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  height: 44px;
  border-radius: 8px;
  border: 1px solid #7dd3b8;
  background: #fff;
  box-sizing: border-box;
}

.chat-fwd__search-ico {
  color: #16a34a;
  font-size: 18px;
}

.chat-fwd__input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 14px;
  background: transparent;
  color: #0f172a;
}

.chat-fwd__input::placeholder {
  color: #94a3b8;
}

.chat-fwd__body {
  padding: 12px 12px 10px;
  overflow: auto;
  flex: 1;
}

.chat-fwd__hint,
.chat-fwd__err {
  margin: 12px 0;
  text-align: center;
  color: #64748b;
  font-size: 13px;
}

.chat-fwd__err {
  color: #ef4444;
  font-weight: 600;
}

.chat-fwd__hint--muted {
  color: #94a3b8;
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
  gap: 10px;
  padding: 10px 10px;
  border-radius: 10px;
  cursor: pointer;
  outline: none;
}

.chat-fwd__row:hover {
  background: rgba(15, 23, 42, 0.06);
}

.chat-fwd__row:focus-visible {
  outline: 2px solid #0ea5e9;
  outline-offset: 2px;
}

.chat-fwd__chk {
  width: 16px;
  height: 16px;
}

.chat-fwd__av {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  object-fit: cover;
  background: #e2e8f0;
}

.chat-fwd__name {
  font-size: 14px;
  color: #0f172a;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-fwd__more {
  margin: 12px auto 0;
  display: block;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #0f172a;
  border-radius: 10px;
  padding: 10px 14px;
  cursor: pointer;
}

.chat-fwd__more:hover:not(:disabled) {
  background: #f1f5f9;
}

.chat-fwd__more:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-fwd__foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 12px 12px 14px;
  border-top: 1px solid #e5e9ef;
  background: #eef1f4;
}

.chat-fwd__btn {
  height: 38px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  font-size: 14px;
}

.chat-fwd__btn--ghost {
  background: #fff;
  color: #0f172a;
}

.chat-fwd__btn--primary {
  background: #16a34a;
  color: #fff;
  border-color: #16a34a;
}

.chat-fwd__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
