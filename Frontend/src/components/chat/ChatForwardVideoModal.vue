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

