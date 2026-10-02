import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { registerNavigationHooks, useNavigationStore } from "../navigationStore";
import { useSearchStore } from "../searchStore";
import { useImmersiveStore } from "../immersiveStore";
import { useDetailStore } from "../detailStore";

let closeAppOverlays;
let search;
let immersive;
let detail;

beforeEach(() => {
  setActivePinia(createPinia());
  closeAppOverlays = vi.fn();
  registerNavigationHooks({ closeAppOverlays });

  search = useSearchStore();
  immersive = useImmersiveStore();
  detail = useDetailStore();
  // 真实实现会碰 DOM / 发请求，这里只验证编排关系
  vi.spyOn(search, "closeSearchExpand").mockImplementation(() => {});
  vi.spyOn(search, "loadTabResults").mockResolvedValue(undefined);
  vi.spyOn(immersive, "closeImmersiveCommentPanel").mockImplementation(() => {});
  vi.spyOn(immersive, "closeProfileFeed").mockImplementation(() => {});
  vi.spyOn(detail, "closeDetail").mockImplementation(() => {});
});

describe("navigationStore 初始状态", () => {
  it("默认停在首页", () => {
    const nav = useNavigationStore();
    expect(nav.activeMenuKey).toBe("home");
    expect(nav.activeMenuLabel).toBe("首页");
    expect(nav.pageHint).toContain("API 基础地址");
  });
});

describe("navigationStore.navigateToTag", () => {
  it("空标签（含只有 # 的情况）不做任何事", () => {
    const nav = useNavigationStore();
    nav.navigateToTag("");
    nav.navigateToTag("   ");
    nav.navigateToTag("#");
    nav.navigateToTag(null);
    expect(search.searchActive).toBe(false);
    expect(search.loadTabResults).not.toHaveBeenCalled();
    expect(nav.activeMenuLabel).toBe("首页");
  });

  it("剥掉可能的前导 # 后落到搜索态", async () => {
    const nav = useNavigationStore();
    nav.navigateToTag("#旅行");
    await Promise.resolve();
    expect(search.searchKeyword).toBe("旅行");
    expect(search.searchSubmittedKeyword).toBe("旅行");
    expect(search.searchActive).toBe(true);
    expect(search.searchTab).toBe("tag");
    expect(search.searchResultError).toBe("");
    expect(nav.activeMenuLabel).toBe("标签：旅行");
    expect(nav.pageHint).toBe("按标签搜索：「旅行」");
    expect(search.loadTabResults).toHaveBeenCalledWith({ reset: true });
  });

  it("首尾空白会被去掉（标签来源 parseSearchTagsArray 已 trim）", async () => {
    useNavigationStore().navigateToTag("  摄影  ");
    await Promise.resolve();
    expect(search.searchKeyword).toBe("摄影");
  });

  it("跳搜索前先收起 App 级遮罩", () => {
    useNavigationStore().navigateToTag("旅行");
    expect(closeAppOverlays).toHaveBeenCalledTimes(1);
  });

  it("已打开的放大层 / 评论面板 / 主页全屏流 / 详情都会被收起", () => {
    search.searchExpandOpen = true;
    immersive.profileFeedOpen = true;
    detail.open = true;

    useNavigationStore().navigateToTag("旅行");

    expect(search.closeSearchExpand).toHaveBeenCalledTimes(1);
    expect(immersive.closeImmersiveCommentPanel).toHaveBeenCalledTimes(1);
    expect(immersive.closeProfileFeed).toHaveBeenCalledTimes(1);
    expect(detail.closeDetail).toHaveBeenCalledTimes(1);
  });

  it("评论侧栏无条件收起，其余本就关着的弹层不会被误关", () => {
    useNavigationStore().navigateToTag("旅行");
    expect(immersive.closeImmersiveCommentPanel).toHaveBeenCalledTimes(1);
    expect(search.closeSearchExpand).not.toHaveBeenCalled();
    expect(immersive.closeProfileFeed).not.toHaveBeenCalled();
    expect(detail.closeDetail).not.toHaveBeenCalled();
  });
});
