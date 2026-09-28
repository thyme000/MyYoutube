import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useCommentPanelStore } from "../commentPanelStore";

beforeEach(() => {
  setActivePinia(createPinia());
});

describe("commentPanelStore 面板开关", () => {
  it("四个入口默认全关", () => {
    const store = useCommentPanelStore();
    expect(store.shortsCommentPanelOpen).toBe(false);
    expect(store.searchExpandCommentOpen).toBe(false);
    expect(store.searchListCommentOpen).toBe(false);
    expect(store.searchListCommentCard).toBeNull();
  });
});

describe("commentPanelStore.patchVideoById", () => {
  it("持有卡片且 id 命中时浅合并，且不改写原对象", () => {
    const store = useCommentPanelStore();
    const card = { id: 5, likeCount: 1, isLike: false };
    store.searchListCommentCard = card;
    store.patchVideoById("5", { likeCount: 2 });
    expect(store.searchListCommentCard).toEqual({ id: 5, likeCount: 2, isLike: false });
    expect(card).toEqual({ id: 5, likeCount: 1, isLike: false });
  });

  it("未持有卡片时不动", () => {
    const store = useCommentPanelStore();
    store.patchVideoById("5", { likeCount: 2 });
    expect(store.searchListCommentCard).toBeNull();
  });

  it("id 不匹配时保持原引用，避免无谓的响应式更新", () => {
    const store = useCommentPanelStore();
    store.searchListCommentCard = { id: 5, likeCount: 1 };
    const before = store.searchListCommentCard;
    store.patchVideoById("6", { likeCount: 2 });
    expect(store.searchListCommentCard).toBe(before);
  });
});
