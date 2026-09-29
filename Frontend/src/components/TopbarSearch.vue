<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import { useSearchStore, SEARCH_UI } from "../stores/searchStore";
import { fetchSearchPrompt } from "../api/searchApi";
import { useToastStore } from "../stores/toastStore";

const emit = defineEmits(["search"]);

const searchStore = useSearchStore();
const { pushToast } = useToastStore();
const {
  searchKeyword,
  searchInputFocused,
  searchDropdownOpen,
  searchPromptRows,
  searchPromptLoading,
  searchHotRows,
  searchHotLoading,
} = storeToRefs(searchStore);

const searchInputRef = ref(null);
let searchSuggestBlurTimer = null;
let searchPromptDebounceTimer = null;

function dedupeStringsMax10(arr) {
  const seen = new Set();
  const out = [];
  for (const s of arr) {
    const x = String(s).trim();
    if (!x || seen.has(x)) continue;
    seen.add(x);
    out.push(x);
    if (out.length >= 10) break;
  }
  return out;
}

function highlightSuggestParts(line) {
  const kw = searchKeyword.value.trim();
  const t = String(line);
  if (!kw) return [{ t, hl: false }];
  const tL = t.toLowerCase();
  const kL = kw.toLowerCase();
  if (tL.startsWith(kL)) {
    return [
      { t: t.slice(0, kw.length), hl: true },
      { t: t.slice(kw.length), hl: false },
    ];
  }
  return [{ t, hl: false }];
}

const searchDropdownVisible = computed(() => {
  if (!searchInputFocused.value || !searchDropdownOpen.value) return false;
  return (
    searchPromptLoading.value ||
    searchPromptRows.value.length > 0 ||
    searchHotLoading.value ||
    searchHotRows.value.length > 0
  );
});

function onInputFocus() {
  searchInputFocused.value = true;
  searchDropdownOpen.value = true;
  if (searchSuggestBlurTimer) {
    clearTimeout(searchSuggestBlurTimer);
    searchSuggestBlurTimer = null;
  }
  void searchStore.loadHotIfNeeded();
}

function onInputBlur() {
  searchSuggestBlurTimer = setTimeout(() => {
    searchInputFocused.value = false;
    searchDropdownOpen.value = false;
  }, 160);
}

function selectSuggestion(text) {
  const t = String(text).trim();
  if (!t) return;
  searchKeyword.value = t;
  searchDropdownOpen.value = false;
  emit("search");
}

function clearKeyword() {
  searchKeyword.value = "";
  nextTick(() => {
    searchInputRef.value?.focus?.();
  });
}

/** 顶栏「/」快捷键聚焦入口 */
function focusInput() {
  searchInputRef.value?.focus?.();
}
defineExpose({ focusInput });

watch(
  () => searchKeyword.value,
  (q) => {
    if (searchPromptDebounceTimer) {
      clearTimeout(searchPromptDebounceTimer);
      searchPromptDebounceTimer = null;
    }
    if (!searchInputFocused.value) return;
    const kw = String(q).trim();
    if (!kw) {
      searchPromptRows.value = [];
      return;
    }
    searchPromptDebounceTimer = setTimeout(async () => {
      searchPromptLoading.value = true;
      try {
        const raw = await fetchSearchPrompt(kw);
        searchPromptRows.value = dedupeStringsMax10(Array.isArray(raw) ? raw : []);
      } catch {
        pushToast(SEARCH_UI.promptError, "error");
        searchPromptRows.value = [];
      } finally {
        searchPromptLoading.value = false;
      }
    }, 220);
  }
);

onBeforeUnmount(() => {
  if (searchSuggestBlurTimer) clearTimeout(searchSuggestBlurTimer);
  if (searchPromptDebounceTimer) clearTimeout(searchPromptDebounceTimer);
});
</script>

<template>
  <div class="search-box search-box--with-dropdown">
    <div class="search-box__input-wrap">
      <input
        ref="searchInputRef"
        v-model="searchKeyword"
        type="text"
        :placeholder="SEARCH_UI.placeholder"
        aria-label="搜索"
        autocomplete="off"
        @keyup.enter="emit('search')"
        @focus="onInputFocus"
        @blur="onInputBlur"
      />
      <button
        v-show="searchKeyword.trim().length > 0"
        type="button"
        class="search-box__clear"
        aria-label="清空"
        title="清空"
        @mousedown.prevent
        @click="clearKeyword"
      >
        <i class="ri-close-line" aria-hidden="true"></i>
      </button>
    </div>
    <button class="icon-btn" type="button" @click="emit('search')">搜索</button>
    <div v-show="searchDropdownVisible" class="search-dropdown" role="listbox" @mousedown.prevent>
      <div v-if="searchPromptLoading || searchPromptRows.length" class="search-dropdown-block">
        <div class="search-dropdown-title">{{ SEARCH_UI.promptTitle }}</div>
        <div v-if="searchPromptLoading" class="search-dropdown-hint">加载中…</div>
        <button
          v-for="(line, idx) in searchPromptRows"
          :key="'p-' + idx + line"
          type="button"
          class="search-dropdown-item"
          role="option"
          @click="selectSuggestion(line)"
        >
          <template v-for="(seg, j) in highlightSuggestParts(line)" :key="j">
            <span v-if="seg.hl" class="search-dropdown-hi">{{ seg.t }}</span>
            <span v-else>{{ seg.t }}</span>
          </template>
        </button>
      </div>
      <div v-if="searchHotLoading || searchHotRows.length" class="search-dropdown-block">
        <div class="search-dropdown-title">{{ SEARCH_UI.hotTitle }}</div>
        <div v-if="searchHotLoading" class="search-dropdown-hint">加载中…</div>
        <button
          v-for="(line, idx) in searchHotRows"
          :key="'h-' + idx + line"
          type="button"
          class="search-dropdown-item"
          role="option"
          @click="selectSuggestion(line)"
        >
          {{ line }}
        </button>
      </div>
    </div>
  </div>
</template>
