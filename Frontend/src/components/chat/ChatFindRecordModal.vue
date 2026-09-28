<script setup>
import { ref, computed, watch } from "vue";
import * as chatApi from "../../api/chatApi.js";
import { sanitizeImageUrl, DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile.js";
import { toast } from "../../utils/toast.js";

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
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="modal-overlay modal-overlay--light chat-find-rec-overlay"
      role="presentation"
      @click.self="close"
    >
      <div class="chat-find-rec" role="dialog" aria-modal="true" aria-labelledby="chat-find-rec-title" @click.stop>
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
      </div>
    </div>
  </Teleport>
</template>
