import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

vi.mock("../../api/behaviorApi", () => ({ reportVideoBehavior: vi.fn() }));

import { reportVideoBehavior } from "../../api/behaviorApi";
import { useAuthStore } from "../authStore";
import { useBehaviorStore } from "../behaviorStore";

beforeEach(async () => {
  localStorage.clear();
  setActivePinia(createPinia());
  vi.clearAllMocks();
  reportVideoBehavior.mockResolvedValue(undefined);
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
    await useBehaviorStore().report("immersive-1", "PLAY", 3000);
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
