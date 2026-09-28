// @ts-check
/**
 * 导航域状态：当前主菜单 key / 标题、页面提示文案，以及「按标签跳搜索」这类跨域导航编排。
 * 原先散在 App.vue 且被 SearchView / ShortsView / HomeView 共用，故独立成 store。
 * 依赖方向单向：navigationStore → search / shorts / detail；三者反过来只经 App 注册的 hook 取用。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import { getApiBaseUrl } from "../api/http";
import { useDetailStore } from "./detailStore";
import { useSearchStore } from "./searchStore";
import { useShortsStore } from "./shortsStore";

const hooks = {
  /** 收起 App 级遮罩（上传弹窗 / 创作中心 / 竖屏短播放器），它们的开关仍在 App */
  closeAppOverlays: () => {},
};

export function registerNavigationHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

export const useNavigationStore = defineStore("navigation", () => {
  const activeMenuKey = ref("home");
  const activeMenuLabel = ref("首页");
  const pageHint = ref(`欢迎来到 THYME。请选择左侧模块查看内容。API 基础地址：${getApiBaseUrl()}`);

  /** 解析为可点击标签（不含 # 前缀，便于搜索） */
  function navigateToTag(tag) {
    const t = String(tag ?? "")
      .replace(/^#/, "")
      .trim();
    if (!t) return;
    const search = useSearchStore();
    const shorts = useShortsStore();
    const detail = useDetailStore();
    if (search.searchExpandOpen) {
      search.closeSearchExpand();
    }
    shorts.closeShortsCommentPanel();
    hooks.closeAppOverlays();
    if (shorts.profileFeedOpen) {
      shorts.closeProfileFeed();
    }
    if (detail.open) {
      detail.closeDetail();
    }
    search.searchKeyword = t;
    search.searchSubmittedKeyword = t;
    search.searchActive = true;
    search.searchTab = "tag";
    search.searchResultError = "";
    activeMenuLabel.value = `标签：${t}`;
    pageHint.value = `按标签搜索：「${t}」`;
    void search.loadTabResults({ reset: true });
  }

  return { activeMenuKey, activeMenuLabel, pageHint, navigateToTag };
});
