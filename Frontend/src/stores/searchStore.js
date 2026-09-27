/**
 * 搜索域状态：关键词、分页结果、下拉联想、列表内预览与全屏放大层。
 * App.vue 过渡期用 storeToRefs 同名解构引用；拆 SearchView.vue 时随之整体搬出。
 */
import { defineStore } from "pinia";

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
});
