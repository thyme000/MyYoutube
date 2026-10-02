import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

vi.mock("../../utils/toast", () => ({ toast: vi.fn() }));
vi.mock("../../utils/downloadFile", () => ({
  downloadRemoteFile: vi.fn(),
  videoDownloadName: vi.fn(() => "video-1.mp4"),
}));

import { toast } from "../../utils/toast";
import { downloadRemoteFile } from "../../utils/downloadFile";
import { useDynamicStore } from "../dynamicStore";
import { useShortsStore } from "../shortsStore";

/** 当前条是 getter（读 unifiedFeedList[shortsFeedIndex]），只能从队列侧摆状态 */
function seedCurrent(store, row) {
  store.feedScope = "profile";
  store.profileFeedQueue = [row];
  store.shortsFeedIndex = 0;
}

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("shortsDownloadCurrent 的下载分发", () => {
  it("动态条目交给动态 store 的懒转码任务，不走直链", async () => {
    const store = useShortsStore();
    const dynamic = useDynamicStore();
    const spy = vi.spyOn(dynamic, "requestDownload").mockResolvedValue(undefined);
    const row = { id: "77", type: "dynamic" };
    seedCurrent(store, row);

    await store.shortsDownloadCurrent();

    expect(spy).toHaveBeenCalledWith(row);
    expect(downloadRemoteFile).not.toHaveBeenCalled();
  });

  it("视频条目走前端直链", async () => {
    const store = useShortsStore();
    seedCurrent(store, { id: "5", type: "video", videoUrl: "https://cdn.example/a.mp4" });

    await store.shortsDownloadCurrent();

    expect(downloadRemoteFile).toHaveBeenCalledWith("https://cdn.example/a.mp4", "video-1.mp4");
    expect(toast).toHaveBeenCalledWith("视频已开始下载", "success");
  });

  it("没有播放地址时不下载，只提示", async () => {
    const store = useShortsStore();
    seedCurrent(store, { id: "6", type: "video" });

    await store.shortsDownloadCurrent();

    expect(downloadRemoteFile).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith("当前内容没有可下载的地址", "info");
  });

  it("队列为空时静默返回", async () => {
    const store = useShortsStore();

    await store.shortsDownloadCurrent();

    expect(downloadRemoteFile).not.toHaveBeenCalled();
    expect(toast).not.toHaveBeenCalled();
  });

  it("直链失败时把错误抛成 toast", async () => {
    const store = useShortsStore();
    downloadRemoteFile.mockRejectedValueOnce(new Error("下载失败: HTTP 403"));
    seedCurrent(store, { id: "7", type: "video", videoUrl: "https://cdn.example/b.mp4" });

    await store.shortsDownloadCurrent();

    expect(toast).toHaveBeenCalledWith("下载失败: HTTP 403", "error");
  });
});
