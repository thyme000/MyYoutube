import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { cardPreviewKey, useHoverPreviewStore } from "../hoverPreviewStore";

const CARD = { id: "1001", videoUrl: "/media/1001.mp4" };
const HOVER_DELAY_MS = 500;

beforeEach(() => {
  setActivePinia(createPinia());
  vi.useFakeTimers();
});

afterEach(() => {
  useHoverPreviewStore().stop();
  vi.useRealTimers();
});

describe("cardPreviewKey", () => {
  it("同一视频在不同列表里是不同的 key（避免跨列表误判为同一条）", () => {
    expect(cardPreviewKey(CARD, "home")).toBe("home:1001");
    expect(cardPreviewKey(CARD, "liked")).toBe("liked:1001");
    expect(cardPreviewKey(CARD, "home")).not.toBe(cardPreviewKey(CARD, "liked"));
  });

  it("缺 id 时退化为空串后缀", () => {
    expect(cardPreviewKey({}, "home")).toBe("home:");
    expect(cardPreviewKey(null)).toBe(":");
  });
});

describe("hoverPreviewStore.enter", () => {
  it("取不到播放地址时完全不进入悬停态", () => {
    const store = useHoverPreviewStore();
    store.enter({ id: "1001" }, "home");
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    expect(store.hoveringId).toBe("");
    expect(store.activeId).toBe("");
  });

  it("立刻记录悬停目标，满 500ms 才真正开始预览", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    expect(store.hoveringId).toBe("home:1001");
    expect(store.activeId).toBe("");

    vi.advanceTimersByTime(HOVER_DELAY_MS - 1);
    expect(store.activeId).toBe("");

    vi.advanceTimersByTime(1);
    expect(store.activeId).toBe("home:1001");
    expect(store.videoUrl).toBe("/media/1001.mp4");
  });

  it("未满 500ms 就移出则取消预览", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    vi.advanceTimersByTime(HOVER_DELAY_MS - 1);
    store.leave(CARD, "home");

    vi.advanceTimersByTime(HOVER_DELAY_MS);
    expect(store.activeId).toBe("");
    expect(store.videoUrl).toBe("");
  });

  it("快速划过多个卡片时只有最后一个生效", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    vi.advanceTimersByTime(200);
    store.enter({ id: "1002", videoUrl: "/media/1002.mp4" }, "home");
    vi.advanceTimersByTime(400);

    expect(store.hoveringId).toBe("home:1002");
    expect(store.activeId).toBe("");
    vi.advanceTimersByTime(100);
    expect(store.activeId).toBe("home:1002");
    expect(store.videoUrl).toBe("/media/1002.mp4");
  });

  it("定时器回调前换了目标就不激活（防止旧卡片闪一下）", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    // 模拟定时器已排期但 hoveringId 被外部改写
    store.hoveringId = "liked:1001";
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    expect(store.activeId).toBe("");
  });
});

describe("hoverPreviewStore.leave", () => {
  it("预览中移出会连视频地址一起清掉", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    store.leave(CARD, "home");
    expect(store.hoveringId).toBe("");
    expect(store.activeId).toBe("");
    expect(store.videoUrl).toBe("");
  });

  it("移出的是别的卡片时不影响当前预览", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    store.leave({ id: "1002", videoUrl: "/media/1002.mp4" }, "home");
    expect(store.activeId).toBe("home:1001");
    expect(store.videoUrl).toBe("/media/1001.mp4");
  });

  it("同一 id 但列表不同视为不同卡片", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "liked");
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    store.leave(CARD, "home");
    expect(store.activeId).toBe("liked:1001");
  });
});

describe("hoverPreviewStore.stop", () => {
  it("清空全部预览态，并取消未到期的定时器", () => {
    const store = useHoverPreviewStore();
    store.enter(CARD, "home");
    store.stop();
    vi.advanceTimersByTime(HOVER_DELAY_MS);
    expect(store.hoveringId).toBe("");
    expect(store.activeId).toBe("");
    expect(store.videoUrl).toBe("");
  });
});
