/**
 * 搜索域状态：关键词、分页结果、下拉联想、列表内预览与全屏放大层。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 SearchView.vue 时随之整体搬出。
 */
import { defineStore } from "pinia";
import { nextTick } from "vue";
import { fetchSearchHot, fetchSearchVideo, fetchSearchUser, fetchSearchTag } from "../api/searchApi.js";
import { toast as pushToast } from "../utils/toast.js";

export const SEARCH_PAGE_SIZE = 10;

/** 搜索页文案（与产品约定一致） */
export const SEARCH_UI = {
  placeholder: "搜索视频标题、用户昵称、标签或ID",
  promptTitle: "猜你想搜",
  hotTitle: "热搜榜",
  emptyKeyword: "输入关键词开始搜索",
  noResult: "没有找到相关内容，换个关键词试试",
  promptError: "提示加载失败，请稍后重试",
  searchError: "搜索失败，请检查网络后重试",
};

export const useSearchStore = defineStore("search", {
  state: () => ({
    searchKeyword: "",
    searchActive: false,
    searchTab: "all",
    searchSubmittedKeyword: "",
    searchInputFocused: false,
    searchDropdownOpen: false,
    searchPromptRows: [],
    searchHotRows: [],
    searchPromptLoading: false,
    searchHotLoading: false,

    searchVideoRows: [],
    searchVideoPageNo: 1,
    searchVideoPages: 0,
    searchVideoLoading: false,
    searchVideoLoadingMore: false,
    searchVideoSort: "relevance",
    searchUserRows: [],
    searchUserPageNo: 1,
    searchUserPages: 0,
    searchUserLoading: false,
    searchUserLoadingMore: false,
    searchTagRows: [],
    searchTagPageNo: 1,
    searchTagPages: 0,
    searchTagLoading: false,
    searchTagLoadingMore: false,
    searchAllUserStrip: [],
    searchResultError: "",

    /** 搜索流：放大层播放（不跳转详情） */
    searchExpandOpen: false,
    searchExpandCard: null,
    searchExpandIndex: 0,
    searchExpandCommentOpen: false,
    /** 搜索列表内打开评论（未进全屏放大层） */
    searchListCommentOpen: false,
    searchListCommentCard: null,
    /** 搜索列表每条视频的播放进度（仅列表内预览） */
    searchFeedProgressMap: {},
    searchFeedLikeBusyId: null,
    searchFeedPausedMap: {},
    searchFeedListPrefs: {},
    searchFeedMainVideoById: {},
    searchFeedMediaWrapById: {},

    searchExpandCurrentTime: 0,
    searchExpandDuration: 0,
    searchExpandPaused: true,
    /** 切换全屏搜索视频过程中忽略 pause / timeupdate，避免中央播放钮闪动 */
    searchExpandAutoplayPending: false,
    searchExpandMuted: false,
    searchExpandVolume: 0.85,
    searchExpandLikeBusy: false,
    searchExpandIsPortrait: true,
  }),

  actions: {
    /** 列表加载完成后的视图层钩子（观察器依赖 App.vue 的 DOM 引用），由宿主注册 */
    registerLoadedHook(fn) {
      this._loadedHook = typeof fn === "function" ? fn : null;
    },
    _notifyLoaded() {
      if (this._loadedHook) {
        nextTick(() => this._loadedHook());
      }
    },

    async loadHotIfNeeded() {
      if (this.searchHotRows.length || this.searchHotLoading) return;
      this.searchHotLoading = true;
      try {
        const raw = await fetchSearchHot();
        this.searchHotRows = Array.isArray(raw) ? raw.slice(0, 7) : [];
      } catch {
        this.searchHotRows = [];
      } finally {
        this.searchHotLoading = false;
      }
    },

    async loadVideoPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchVideoPageNo = 1;
        this.searchVideoRows = [];
      }
      const nextPage = reset ? 1 : this.searchVideoPageNo + 1;
      const maxP = this.searchVideoPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (!reset && this.searchVideoLoadingMore) return;
      if (this.searchVideoLoading || this.searchVideoLoadingMore) return;
      if (reset) this.searchVideoLoading = true;
      else this.searchVideoLoadingMore = true;
      try {
        const res = await fetchSearchVideo({
          keyword: kw,
          sort: this.searchVideoSort,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchVideoRows = reset ? list : [...this.searchVideoRows, ...list];
        this.searchVideoPageNo = nextPage;
        this.searchVideoPages = res.pages ?? 0;
        this.searchResultError = "";
        this._notifyLoaded();
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchVideoRows = [];
      } finally {
        this.searchVideoLoading = false;
        this.searchVideoLoadingMore = false;
      }
    },

    async loadUserStrip({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw || !reset) return;
      try {
        const res = await fetchSearchUser({ keyword: kw, pageNo: 1, pageSize: 8 });
        this.searchAllUserStrip = res.list || [];
      } catch {
        this.searchAllUserStrip = [];
      }
    },

    async loadUserPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchUserPageNo = 1;
        this.searchUserRows = [];
      }
      const nextPage = reset ? 1 : this.searchUserPageNo + 1;
      const maxP = this.searchUserPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (this.searchUserLoading || this.searchUserLoadingMore) return;
      if (reset) this.searchUserLoading = true;
      else this.searchUserLoadingMore = true;
      try {
        const res = await fetchSearchUser({
          keyword: kw,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchUserRows = reset ? list : [...this.searchUserRows, ...list];
        this.searchUserPageNo = nextPage;
        this.searchUserPages = res.pages ?? 0;
        this.searchResultError = "";
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchUserRows = [];
      } finally {
        this.searchUserLoading = false;
        this.searchUserLoadingMore = false;
      }
    },

    async loadTagPage({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      if (reset) {
        this.searchTagPageNo = 1;
        this.searchTagRows = [];
      }
      const nextPage = reset ? 1 : this.searchTagPageNo + 1;
      const maxP = this.searchTagPages || 0;
      if (!reset && maxP > 0 && nextPage > maxP) return;
      if (this.searchTagLoading || this.searchTagLoadingMore) return;
      if (reset) this.searchTagLoading = true;
      else this.searchTagLoadingMore = true;
      try {
        const res = await fetchSearchTag({
          keyword: kw,
          pageNo: nextPage,
          pageSize: SEARCH_PAGE_SIZE,
        });
        const list = res.list || [];
        this.searchTagRows = reset ? list : [...this.searchTagRows, ...list];
        this.searchTagPageNo = nextPage;
        this.searchTagPages = res.pages ?? 0;
        this.searchResultError = "";
        this._notifyLoaded();
      } catch {
        this.searchResultError = SEARCH_UI.searchError;
        pushToast(SEARCH_UI.searchError, "error");
        if (reset) this.searchTagRows = [];
      } finally {
        this.searchTagLoading = false;
        this.searchTagLoadingMore = false;
      }
    },

    async loadTabResults({ reset }) {
      const kw = this.searchSubmittedKeyword.trim();
      if (!kw) return;
      this.searchResultError = "";
      if (this.searchTab === "all") {
        await this.loadUserStrip({ reset: true });
        await this.loadVideoPage({ reset: true });
        return;
      }
      if (this.searchTab === "video") {
        await this.loadVideoPage({ reset: true });
        return;
      }
      if (this.searchTab === "user") {
        await this.loadUserPage({ reset: true });
        return;
      }
      if (this.searchTab === "tag") {
        await this.loadTagPage({ reset: true });
      }
    },

    selectTab(key) {
      if (this.searchTab === key) return;
      this.searchTab = key;
      void this.loadTabResults({ reset: true });
    },
  },
});
