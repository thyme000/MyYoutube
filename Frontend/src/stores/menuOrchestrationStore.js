// @ts-check
/**
 * 菜单编排域：loadModuleCards（各模块首屏数据装载）与 setActiveMenu（菜单切换编排）。
 * 状态分属 home/history/liked/shorts/profile/studio/detail/navigation 各域 store，
 * 这里只做跨域动作编排，自身不持有菜单数据；App 本地能力（搜索预览观察器、上传弹窗开关）
 * 经 registerMenuOrchestrationHooks 注入。独立成 store 而非并入 navigationStore：
 * homeStore/notificationStore 反向依赖 navigationStore，编排层必须在其下游而非其内部。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { fetchShortsFeedPage, fetchVideoListPage, fetchVideosByModule } from "../api/videoApi";
import { VIDEO_CATEGORY_CHIPS } from "../constants/videoCategories";
import { menuPathFor, router } from "../router";
import { useAuthStore } from "./authStore";
import { useBehaviorStore } from "./behaviorStore";
import { useDetailStore } from "./detailStore";
import { useHistoryStore } from "./historyStore";
import { homeSortOptions, useHomeStore } from "./homeStore";
import { useLikedStore } from "./likedStore";
import { useNavigationStore } from "./navigationStore";
import { useProfileStore } from "./profileStore";
import { useSearchStore } from "./searchStore";
import { useShortsStore } from "./shortsStore";
import { useStudioStore } from "./studioStore";

const hooks = {
  /** 切菜单时拆掉搜索列表的视频自动播放观察器（DOM 归 App） */
  teardownSearchVideoObserver: () => {},
  /** 上传弹窗开关随 /upload 路由，状态仍在 App */
  closeUploadModal: () => {},
};

export function registerMenuOrchestrationHooks(next = {}) {
  for (const [key, fn] of Object.entries(next)) {
    if (typeof fn === "function") hooks[key] = fn;
  }
}

export const useMenuOrchestrationStore = defineStore("menuOrchestration", () => {
  let activeMenuSwitchSeq = 0;

  async function loadModuleCards(menu, { fallback = true } = {}) {
    const home = useHomeStore();
    const navigation = useNavigationStore();
    const auth = useAuthStore();
    const history = useHistoryStore();
    const liked = useLikedStore();
    const shorts = useShortsStore();
    const profile = useProfileStore();
    home.loading = true;
    try {
      if (menu.key === "home") {
        const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === home.homeCategoryKey);
        const [longRes, shortRes] = await Promise.all([
          fetchVideoListPage({
            pageNo: 1,
            pageSize: 24,
            category: chip?.code || undefined,
            sort: home.homeSortKey,
            videoType: "LONG_VIDEO",
          }),
          fetchShortsFeedPage({
            pageNo: 1,
            pageSize: 18,
            category: chip?.code || undefined,
          }),
        ]);
        home.cards = longRes.list.length ? longRes.list : [];
        home.homeLongPageNo = 1;
        home.homeLongPages = longRes.pages ?? 0;
        home.homeShortsRows = shortRes.list.length ? shortRes.list : [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = shortRes.pages ?? 0;
        const sortLabel = homeSortOptions.find((x) => x.value === home.homeSortKey)?.label || "排序";
        navigation.pageHint = `首页 · 长视频(/videos/list) + 短视频(/videos/shorts/feed) · ${chip?.label ?? "全部"} · ${sortLabel}`;
        nextTick(() => {
          home.maybeLoadMoreLongByWindow();
        });
        home.saveSnapshot();
        return;
      }
      if (menu.key === "history") {
        history.historyLongPageNo = 1;
        history.historyShortPageNo = 1;
        await Promise.all([history.loadLongPage({ reset: true }), history.loadShortPage({ reset: true })]);
        home.cards = [];
        navigation.pageHint = "历史播放记录 · 长视频与短视频（分页）";
        return;
      }
      if (menu.key === "liked") {
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        home.cards = [];
        if (!auth.userId) {
          liked.likedRows = [];
          liked.likedPageNo = 1;
          liked.likedPages = 0;
          liked.likedTotal = 0;
          navigation.pageHint = "请先登录后查看「赞过的视频」。";
          return;
        }
        await liked.load({ reset: true });
        navigation.pageHint = `赞过的视频 · 共 ${liked.likedTotal} 条（GET /like/{userId}/list）`;
        return;
      }
      if (menu.key === "subscribed") {
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        shorts.followSubscribedFilterAuthorId = null;
        shorts.followAuthorFeedQueue = [];
        shorts.followAuthorVideoPageNo = 1;
        shorts.followAuthorVideoHasMore = false;
        shorts.followVideoPageNo = 1;
        shorts.followVideoHasMore = true;
        home.cards = [];
        if (!auth.isAuthed) {
          shorts.followFeedQueue = [];
          navigation.pageHint = "请先登录后查看「关注」视频流。";
          return;
        }
        shorts.feedScope = "follow";
        shorts.shortsFeedIndex = 0;
        shorts.shortsFeedAutoplayPending = true;
        await profile.loadRelationPage("following", { reset: true });
        await shorts.loadSubscribedFollowVideos({ reset: true });
        navigation.pageHint = "关注 · 左侧为关注列表，右侧为关注作者的短视频推荐（GET /videos/follow/list）";
        return;
      }
      if (menu.key === "music") {
        home.loading = false;
        home.cards = [];
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        navigation.pageHint = "音乐 · 本地播放与全屏歌词（前端演示）";
        return;
      }
      if (menu.key === "chat") {
        home.loading = false;
        home.cards = [];
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        navigation.pageHint = "聊天 · 会话与消息（对接后端 /chat/*）";
        return;
      }
      home.homeShortsRows = [];
      home.homeShortsPageNo = 1;
      home.homeShortsPages = 0;
      home.homeLongPageNo = 1;
      home.homeLongPages = 0;
      const list =
        menu.key === "shorts"
          ? (await fetchShortsFeedPage({ pageNo: 1, pageSize: 24 })).list
          : await fetchVideosByModule(menu.key);
      home.cards = list.length ? list : [];
      navigation.pageHint = `你正在查看「${menu.label}」模块。当前数据来源：Spring Boot 接口。`;
    } catch (error) {
      if (!fallback) {
        throw error;
      }
      if (menu.key === "home") {
        home.cards = [];
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
        home.saveSnapshot();
      } else {
        if (menu.key === "history") {
          history.historyLongRows = [];
          history.historyLongPageNo = 1;
          history.historyLongPages = 0;
          history.historyShortRows = [];
          history.historyShortPageNo = 1;
          history.historyShortPages = 0;
          home.cards = [];
        } else if (menu.key === "liked") {
          liked.likedRows = [];
          liked.likedPageNo = 1;
          liked.likedPages = 0;
          liked.likedTotal = 0;
          home.cards = [];
        } else if (menu.key === "subscribed") {
          shorts.followFeedQueue = [];
          shorts.followAuthorFeedQueue = [];
          home.cards = [];
        } else {
          home.cards = [];
        }
        home.homeShortsRows = [];
        home.homeShortsPageNo = 1;
        home.homeShortsPages = 0;
      }
      navigation.pageHint = `接口暂不可用，当前显示为空数据。错误信息：${error.message}`;
    } finally {
      home.loading = false;
    }
  }

  async function setActiveMenu(menu) {
    const home = useHomeStore();
    const navigation = useNavigationStore();
    const shorts = useShortsStore();
    const profile = useProfileStore();
    const studio = useStudioStore();
    const detail = useDetailStore();
    const search = useSearchStore();
    const seq = ++activeMenuSwitchSeq;
    search.searchActive = false;
    hooks.teardownSearchVideoObserver();
    const prevMenuKey = navigation.activeMenuKey;
    const prevShowProfile = profile.open;
    const switchingShortsTabs =
      prevMenuKey !== menu.key &&
      (prevMenuKey === "shorts" || prevMenuKey === "subscribed") &&
      (menu.key === "shorts" || menu.key === "subscribed");
    if (switchingShortsTabs) {
      shorts.bumpPlayToken();
      shorts.pauseShortsFeedVideos({ muteForeground: true });
      shorts.shortsFeedPaused = true;
      shorts.resetShortsFeedProgressUiForSwitch();
      shorts.resetFeedPlayersToStart();
    }
    if (prevMenuKey === "shorts" && menu.key !== "shorts") {
      void useBehaviorStore().flushShortsHistory();
    }
    if (shorts.profileFeedOpen) {
      shorts.closeProfileFeed();
    }
    studio.showStudio = false;
    hooks.closeUploadModal();
    detail.open = false;
    detail.video = null;
    profile.open = false;
    profile.clearReturnContext();
    profile.mediaPreviewOpen = false;
    navigation.activeMenuKey = menu.key;
    navigation.activeMenuLabel = menu.label;
    const targetPath = menuPathFor(menu.key);
    if (router.currentRoute.value.path !== targetPath) {
      void router.push(targetPath);
    }
    if (menu.key === "shorts") {
      shorts.bumpPlayToken();
      shorts.feedScope = "shorts";
      shorts.shortsFeedIndex = 0;
      shorts.shortsFeedAutoplayPending = true;
    }
    if (menu.key === "subscribed") {
      shorts.bumpPlayToken();
      shorts.feedScope = "follow";
      shorts.followSubscribedFilterAuthorId = null;
      shorts.followAuthorFeedQueue = [];
      shorts.followAuthorVideoPageNo = 1;
      shorts.followAuthorVideoHasMore = false;
      shorts.followVideoPageNo = 1;
      shorts.followVideoHasMore = true;
      shorts.shortsFeedIndex = 0;
      shorts.shortsFeedAutoplayPending = true;
    }

    if (menu.key === "home") {
      if (prevMenuKey === "home" && prevShowProfile) {
        if (home.restoreSnapshot()) {
          home.loading = false;
          nextTick(() => {
            home.maybeLoadMoreLongByWindow();
          });
          return;
        }
        await loadModuleCards(menu);
        return;
      }
      if (prevMenuKey === "home" && !prevShowProfile) {
        await loadModuleCards(menu);
        return;
      }
      if (home.restoreSnapshot()) {
        home.loading = false;
        nextTick(() => {
          home.maybeLoadMoreLongByWindow();
        });
        return;
      }
      await loadModuleCards(menu);
      return;
    }

    if (prevMenuKey === "home") {
      home.saveSnapshot();
    }
    await loadModuleCards(menu);
    if (seq !== activeMenuSwitchSeq) {
      return;
    }
    if (menu.key === "shorts" || menu.key === "subscribed") {
      nextTick(() => {
        if (seq !== activeMenuSwitchSeq) return;
        if (navigation.activeMenuKey !== menu.key) return;
        shorts.reloadFeedPlayers();
      });
    }
  }

  return { loadModuleCards, setActiveMenu };
});
