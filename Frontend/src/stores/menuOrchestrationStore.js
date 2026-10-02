// @ts-check
/**
 * 菜单编排域：loadModuleCards（各模块首屏数据装载）与 setActiveMenu（菜单切换编排）。
 * 状态分属 home/history/liked/immersive/profile/studio/detail/navigation 各域 store，
 * 这里只做跨域动作编排，自身不持有菜单数据；App 本地能力（搜索预览观察器、上传弹窗开关）
 * 经 registerMenuOrchestrationHooks 注入。独立成 store 而非并入 navigationStore：
 * homeStore/notificationStore 反向依赖 navigationStore，编排层必须在其下游而非其内部。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { fetchContentList, fetchContentFeed } from "../api/contentApi";
import { VIDEO_CATEGORY_CHIPS } from "../constants/videoCategories";
import { menuPathFor, router } from "../router";
import { useAuthStore } from "./authStore";
import { useDetailStore } from "./detailStore";
import { useHistoryStore } from "./historyStore";
import { homeSortOptions, useHomeStore } from "./homeStore";
import { useLikedStore } from "./likedStore";
import { useNavigationStore } from "./navigationStore";
import { useProfileStore } from "./profileStore";
import { useSearchStore } from "./searchStore";
import { useImmersiveStore } from "./immersiveStore";
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
    const immersive = useImmersiveStore();
    const profile = useProfileStore();
    home.loading = true;
    try {
      if (menu.key === "home") {
        const chip = VIDEO_CATEGORY_CHIPS.find((c) => c.key === home.homeCategoryKey);
        const [longRes, shortRes] = await Promise.all([
          // 网格走统一内容池混排接口(视频/动态同池; 分类过滤时仅视频)
          fetchContentList({
            pageNo: 1,
            pageSize: 24,
            category: chip?.code || undefined,
            sort: home.homeSortKey,
          }),
          // 连播流走统一消费流(全量混排)
          fetchContentFeed({ pageNo: 1, pageSize: 18 }),
        ]);
        home.cards = longRes.list.length ? longRes.list : [];
        home.homeLongPageNo = 1;
        home.homeLongPages = longRes.pages ?? 0;
        home.immersiveRows = shortRes.list.length ? shortRes.list : [];
        home.immersivePageNo = 1;
        home.immersivePages = shortRes.pages ?? 0;
        const sortLabel = homeSortOptions.find((x) => x.value === home.homeSortKey)?.label || "排序";
        navigation.pageHint = `首页 · 内容池混排(/contents/list) + 连播(/contents/feed) · ${chip?.label ?? "全部"} · ${sortLabel}`;
        nextTick(() => {
          home.maybeLoadMoreLongByWindow();
        });
        home.saveSnapshot();
        return;
      }
      if (menu.key === "history") {
        history.historyLongPageNo = 1;
        await history.loadLongPage({ reset: true });
        home.cards = [];
        navigation.pageHint = "历史播放记录 · 长视频与短视频（分页）";
        return;
      }
      if (menu.key === "liked") {
        home.immersiveRows = [];
        home.immersivePageNo = 1;
        home.immersivePages = 0;
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
        home.immersiveRows = [];
        home.immersivePageNo = 1;
        home.immersivePages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        immersive.followSubscribedFilterAuthorId = null;
        immersive.followAuthorFeedQueue = [];
        immersive.followAuthorVideoPageNo = 1;
        immersive.followAuthorVideoHasMore = false;
        immersive.followVideoPageNo = 1;
        immersive.followVideoHasMore = true;
        home.cards = [];
        if (!auth.isAuthed) {
          immersive.followFeedQueue = [];
          navigation.pageHint = "请先登录后查看「关注」视频流。";
          return;
        }
        immersive.feedScope = "follow";
        immersive.immersiveFeedIndex = 0;
        immersive.immersiveFeedAutoplayPending = true;
        await profile.loadRelationPage("following", { reset: true });
        await immersive.loadSubscribedFollowVideos({ reset: true });
        navigation.pageHint = "关注 · 左侧为关注列表，右侧为关注作者的短视频推荐（GET /videos/follow/list）";
        return;
      }
      if (menu.key === "music") {
        home.loading = false;
        home.cards = [];
        home.immersiveRows = [];
        home.immersivePageNo = 1;
        home.immersivePages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        navigation.pageHint = "音乐 · 本地播放与全屏歌词（前端演示）";
        return;
      }
      if (menu.key === "chat") {
        home.loading = false;
        home.cards = [];
        home.immersiveRows = [];
        home.immersivePageNo = 1;
        home.immersivePages = 0;
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        navigation.pageHint = "聊天 · 会话与消息（对接后端 /chat/*）";
        return;
      }
      if (menu.key === "immersive") {
        // 首页沉浸横条已经装过同一份 /contents/feed 就直接复用，避免同一队列请求两次
        if (!home.immersiveRows.length) {
          const res = await fetchContentFeed({ pageNo: 1, pageSize: 18 });
          home.immersiveRows = Array.isArray(res.list) ? res.list : [];
          home.immersivePageNo = 1;
          home.immersivePages = res.pages ?? 0;
        }
        navigation.pageHint = "沉浸 · 视频与动态同一队列（GET /contents/feed）";
        return;
      }
    } catch (error) {
      if (!fallback) {
        throw error;
      }
      if (menu.key === "home") {
        home.cards = [];
        home.homeLongPageNo = 1;
        home.homeLongPages = 0;
        home.immersiveRows = [];
        home.immersivePageNo = 1;
        home.immersivePages = 0;
        home.saveSnapshot();
      } else {
        if (menu.key === "history") {
          history.historyLongRows = [];
          history.historyLongPageNo = 1;
          history.historyLongPages = 0;
          home.cards = [];
        } else if (menu.key === "liked") {
          liked.likedRows = [];
          liked.likedPageNo = 1;
          liked.likedPages = 0;
          liked.likedTotal = 0;
          home.cards = [];
        } else if (menu.key === "subscribed") {
          immersive.followFeedQueue = [];
          immersive.followAuthorFeedQueue = [];
          home.cards = [];
        } else if (menu.key === "immersive") {
          home.immersiveRows = [];
          home.immersivePageNo = 1;
          home.immersivePages = 0;
        }
      }
      navigation.pageHint = `接口暂不可用，当前显示为空数据。错误信息：${error.message}`;
    } finally {
      home.loading = false;
    }
  }

  async function setActiveMenu(menu) {
    const home = useHomeStore();
    const navigation = useNavigationStore();
    const immersive = useImmersiveStore();
    const profile = useProfileStore();
    const studio = useStudioStore();
    const detail = useDetailStore();
    const search = useSearchStore();
    const seq = ++activeMenuSwitchSeq;
    search.searchActive = false;
    hooks.teardownSearchVideoObserver();
    const prevMenuKey = navigation.activeMenuKey;
    const prevShowProfile = profile.open;
    const switchingImmersiveTabs =
      prevMenuKey !== menu.key &&
      (prevMenuKey === "immersive" || prevMenuKey === "subscribed") &&
      (menu.key === "immersive" || menu.key === "subscribed");
    if (switchingImmersiveTabs) {
      immersive.bumpPlayToken();
      immersive.pauseImmersiveFeedVideos({ muteForeground: true });
      immersive.immersiveFeedPaused = true;
      immersive.resetImmersiveFeedProgressUiForSwitch();
      immersive.resetFeedPlayersToStart();
    }
    if (immersive.profileFeedOpen) {
      immersive.closeProfileFeed();
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
    if (menu.key === "immersive") {
      immersive.bumpPlayToken();
      immersive.feedScope = "immersive";
      immersive.immersiveFeedIndex = 0;
      immersive.immersiveFeedAutoplayPending = true;
    }
    if (menu.key === "subscribed") {
      immersive.bumpPlayToken();
      immersive.feedScope = "follow";
      immersive.followSubscribedFilterAuthorId = null;
      immersive.followAuthorFeedQueue = [];
      immersive.followAuthorVideoPageNo = 1;
      immersive.followAuthorVideoHasMore = false;
      immersive.followVideoPageNo = 1;
      immersive.followVideoHasMore = true;
      immersive.immersiveFeedIndex = 0;
      immersive.immersiveFeedAutoplayPending = true;
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
    if (menu.key === "immersive" || menu.key === "subscribed") {
      nextTick(() => {
        if (seq !== activeMenuSwitchSeq) return;
        if (navigation.activeMenuKey !== menu.key) return;
        immersive.reloadFeedPlayers();
      });
    }
  }

  return { loadModuleCards, setActiveMenu };
});
