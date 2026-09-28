import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

vi.mock("../../api/behaviorApi", () => ({ reportVideoBehavior: vi.fn() }));
vi.mock("../../api/videoApi", () => ({ reportShortHistoryBatch: vi.fn() }));

import { reportVideoBehavior } from "../../api/behaviorApi";
import { reportShortHistoryBatch } from "../../api/videoApi";
import { useAuthStore } from "../authStore";
import { useBehaviorStore } from "../behaviorStore";

const tick = () => new Promise((r) => setTimeout(r, 0));
const SHORT_ITEM = { id: "1001", videoType: "SHORT_VIDEO" };

beforeEach(async () => {
  localStorage.clear();
  setActivePinia(createPinia());
  vi.clearAllMocks();
  reportVideoBehavior.mockResolvedValue(undefined);
  reportShortHistoryBatch.mockResolvedValue(undefined);
  // 观看历史队列是模块级的，跨用例复用，必须显式清空
  useBehaviorStore().resetShortsHistory();
  await tick();
});

describe("behaviorStore PLAY 去重", () => {
  it("未标记时未上报，标记后按字符串归一判定", () => {
    const store = useBehaviorStore();
    expect(store.hasPlayReported(1001)).toBe(false);
    store.markPlayReported(1001);
    expect(store.hasPlayReported("1001")).toBe(true);
    expect(store.hasPlayReported(1001)).toBe(true);
    expect(store.hasPlayReported(1002)).toBe(false);
  });

  it("空 id 不入表", () => {
    const store = useBehaviorStore();
    store.markPlayReported("");
    store.markPlayReported(null);
    expect(store.reportedPlayIds.size).toBe(0);
  });
});

describe("behaviorStore.report", () => {
  it("未登录不发请求", async () => {
    await useBehaviorStore().report(1001, "PLAY", 3000);
    expect(reportVideoBehavior).not.toHaveBeenCalled();
  });

  it("非纯数字 id 不发请求（过滤 mock 卡片 id）", async () => {
    useAuthStore().signIn("t");
    await useBehaviorStore().report("shorts-1", "PLAY", 3000);
    expect(reportVideoBehavior).not.toHaveBeenCalled();
  });

  it("登录后按约定 body 上报，时长取整且不为负", async () => {
    useAuthStore().signIn("t");
    await useBehaviorStore().report(1001, "PLAY", 3499.8);
    expect(reportVideoBehavior).toHaveBeenCalledTimes(1);
    const body = reportVideoBehavior.mock.calls[0][0];
    expect(body.videoId).toBe("1001");
    expect(body.event).toBe("PLAY");
    expect(body.watchDuration).toBe(3499);
    expect(body.watchTime).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);

    await useBehaviorStore().report(1001, "PLAY", -5);
    expect(reportVideoBehavior.mock.calls[1][0].watchDuration).toBe(0);
  });

  it("上报失败被静默吞掉，不抛给调用方", async () => {
    useAuthStore().signIn("t");
    reportVideoBehavior.mockRejectedValue(new Error("boom"));
    await expect(useBehaviorStore().report(1001, "PLAY", 1000)).resolves.toBeUndefined();
  });
});

describe("behaviorStore 短视频观看历史队列", () => {
  it("未登录不入队", async () => {
    const store = useBehaviorStore();
    store.enqueueShortsHistory("1001", SHORT_ITEM);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).not.toHaveBeenCalled();
  });

  it("长视频与无 videoType 的行不入队（只有短视频写 /videos/shorts/play）", async () => {
    useAuthStore().signIn("t");
    const store = useBehaviorStore();
    store.enqueueShortsHistory("1001", { id: "1001", videoType: "LONG_VIDEO" });
    store.enqueueShortsHistory("1002", { id: "1002" });
    store.enqueueShortsHistory("1003", null);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).not.toHaveBeenCalled();
  });

  it("同一视频重复入队只算一次", async () => {
    useAuthStore().signIn("t");
    const store = useBehaviorStore();
    store.enqueueShortsHistory("1001", SHORT_ITEM);
    store.enqueueShortsHistory(1001, SHORT_ITEM);
    store.enqueueShortsHistory("1001", SHORT_ITEM);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(1);
    expect(reportShortHistoryBatch.mock.calls[0][0].shortIds).toEqual(["1001"]);
  });

  it("满 10 条自动冲刷，并按后端约定带上 userId", async () => {
    useAuthStore().signIn("t");
    useAuthStore().userId = 9001;
    const store = useBehaviorStore();
    for (let i = 1; i <= 9; i += 1) {
      store.enqueueShortsHistory(String(1000 + i), { id: String(1000 + i), videoType: "SHORT" });
    }
    await tick();
    expect(reportShortHistoryBatch).not.toHaveBeenCalled();

    store.enqueueShortsHistory("1010", { id: "1010", videoType: "SHORT" });
    await tick();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(1);
    const payload = reportShortHistoryBatch.mock.calls[0][0];
    expect(payload.shortIds).toHaveLength(10);
    expect(payload.userId).toBe("9001");
  });

  it("已上报过的视频不再入队", async () => {
    useAuthStore().signIn("t");
    const store = useBehaviorStore();
    store.enqueueShortsHistory("1001", SHORT_ITEM);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(1);

    store.enqueueShortsHistory("1001", SHORT_ITEM);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(1);
  });

  it("发送失败时整批回队，下次冲刷重试", async () => {
    useAuthStore().signIn("t");
    const store = useBehaviorStore();
    reportShortHistoryBatch.mockRejectedValueOnce(new Error("offline"));

    store.enqueueShortsHistory("1001", SHORT_ITEM);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(1);

    reportShortHistoryBatch.mockResolvedValue(undefined);
    await store.flushShortsHistory();
    expect(reportShortHistoryBatch).toHaveBeenCalledTimes(2);
    expect(reportShortHistoryBatch.mock.calls[1][0].shortIds).toEqual(["1001"]);
  });
});
