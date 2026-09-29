import { ref, watch, onUnmounted } from "vue";
import { musicSearch } from "../api/musicApi.js";
import { playCloudTrackFromSearch } from "../stores/musicPlayerStore.js";

/** 避免把 Nginx/HTML 整页塞进界面 */
function sanitizeMusicError(err) {
  const raw = err?.message != null ? String(err.message) : String(err ?? "");
  if (/<\s*html[\s>]|<!DOCTYPE|<\s*head[\s>]|<title>\s*405/i.test(raw) || (raw.includes("nginx") && raw.includes("<"))) {
    return "无法连接解析服务：请确认本机 5000 端口已启动，且 Nginx 已配置 /music-svc/ 反代后执行 reload。";
  }
  return raw.replace(/<[^>]*>/g, "").trim().slice(0, 240) || "请求失败";
}

/** 音乐库面板：云端搜索（防抖）、结果列表、点播解析 */
export function useMusicHub() {
  const hubOpen = ref(false);
  const hubTab = ref("cloud");
  const cloudSearchQuery = ref("");
  const cloudResults = ref([]);
  const searchLoading = ref(false);
  const cloudError = ref("");
  let searchDebounce = null;

  const resolvingSongId = ref("");

  function openHub() {
    hubOpen.value = true;
  }
  function closeHub() {
    hubOpen.value = false;
  }

  async function runCloudSearch() {
    const q = cloudSearchQuery.value.trim();
    cloudError.value = "";
    cloudResults.value = [];
    if (!q) return;
    searchLoading.value = true;
    try {
      const data = await musicSearch(q, 30);
      cloudResults.value = Array.isArray(data) ? data : [];
    } catch (e) {
      cloudError.value = sanitizeMusicError(e);
    } finally {
      searchLoading.value = false;
    }
  }

  watch(cloudSearchQuery, () => {
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(() => {
      if (hubTab.value === "cloud" && cloudSearchQuery.value.trim().length >= 1) {
        void runCloudSearch();
      }
    }, 420);
  });

  watch(hubTab, (t) => {
    if (t === "cloud" && cloudSearchQuery.value.trim()) void runCloudSearch();
  });

  async function playCloudTrack(row) {
    if (!row?.id) return;
    cloudError.value = "";
    const sid = String(row.id);
    resolvingSongId.value = sid;
    await playCloudTrackFromSearch(row, { onError: (msg) => { cloudError.value = msg; } });
    resolvingSongId.value = "";
  }

  onUnmounted(() => clearTimeout(searchDebounce));

  return {
    hubOpen,
    hubTab,
    cloudSearchQuery,
    cloudResults,
    searchLoading,
    cloudError,
    resolvingSongId,
    openHub,
    closeHub,
    playCloudTrack,
  };
}
