// @ts-check
/**
 * 我的收藏域状态：content 级混排收藏(视频/动态同池)的分页数据。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { cancelCollectContent, fetchCollectPage } from "../api/collectApi";
import { useAuthStore } from "./authStore";
import { useImmersiveStore } from "./immersiveStore";

export const useCollectStore = defineStore("collect", () => {
  const collectRows = ref([]);
  const collectLoading = ref(false);
  const collectPageNo = ref(1);
  const collectPages = ref(0);
  const collectTotal = ref(0);

  async function load({ reset = false } = {}) {
    const auth = useAuthStore();
    if (!auth.isAuthed) return;
    if (collectLoading.value) return;
    collectLoading.value = true;
    try {
      const targetPage = reset ? 1 : collectPageNo.value;
      const res = await fetchCollectPage({ pageNo: targetPage, pageSize: 9 });
      const nextList = Array.isArray(res.list) ? res.list : [];
      collectRows.value = nextList;
      // 空页即到底: 不信任 pages 提示, 防止分页按钮无限翻
      collectPages.value = nextList.length ? Number(res.pages || 0) : targetPage;
      collectTotal.value = Number(res.total || 0);
      if (reset) {
        collectPageNo.value = 1;
      }
    } catch {
      collectRows.value = [];
      collectPageNo.value = 1;
      collectPages.value = 0;
      collectTotal.value = 0;
    }
    collectLoading.value = false;
  }

  async function nextPage() {
    if (collectPageNo.value >= (collectPages.value || 1) || collectLoading.value) return;
    collectPageNo.value += 1;
    await load();
  }

  async function prevPage() {
    if (collectPageNo.value <= 1 || collectLoading.value) return;
    collectPageNo.value -= 1;
    await load();
  }

  /** 视频卡进全屏连播(动态卡由视图层路由到详情层) */
  function openCard(card, index) {
    useImmersiveStore().openProfileFeed(collectRows.value, index);
  }

  async function removeOne(id) {
    await cancelCollectContent(id);
    collectRows.value = collectRows.value.filter((row) => String(row.id) !== String(id));
    collectTotal.value = Math.max(0, collectTotal.value - 1);
    if (!collectRows.value.length && collectPageNo.value > 1) {
      collectPageNo.value -= 1;
      await load();
    }
  }

  return {
    collectRows,
    collectLoading,
    collectPageNo,
    collectPages,
    collectTotal,
    load,
    nextPage,
    prevPage,
    openCard,
    removeOne,
  };
});
