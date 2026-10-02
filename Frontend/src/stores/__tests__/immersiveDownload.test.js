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
import { useImmersiveStore } from "../immersiveStore";

/** 当前条是 getter（读 unifiedFeedList[immersiveFeedIndex]），只能从队列侧摆状态 */
function seedCurrent(store, row) {
  store.feedScope = "profile";
  store.profileFeedQueue = [row];
  store.immersiveFeedIndex = 0;
}

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("immersiveDownloadCurrent 的下载分发", () => {
  it("动态条目交给动态 store 的懒转码任务，不走直链", async () => {
    const store = useImmersiveStore();
    const dynamic = useDynamicStore();
    const spy = vi.spyOn(dynamic, "requestDownload").mockResolvedValue(undefined);
    const row = { id: "77", type: "dynamic" };
    seedCurrent(store, row);

    await store.immersiveDownloadCurrent();

    expect(spy).toHaveBeenCalledWith(row);
    expect(downloadRemoteFile).not.toHaveBeenCalled();
  });

  it("视频条目走前端直链", async () => {
    const store = useImmersiveStore();
    seedCurrent(store, { id: "5", type: "video", videoUrl: "https://cdn.example/a.mp4" });

    await store.immersiveDownloadCurrent();

    expect(downloadRemoteFile).toHaveBeenCalledWith("https://cdn.example/a.mp4", "video-1.mp4");
    expect(toast).toHaveBeenCalledWith("视频已开始下载", "success");
  });

  it("没有播放地址时不下载，只提示", async () => {
    const store = useImmersiveStore();
    seedCurrent(store, { id: "6", type: "video" });

    await store.immersiveDownloadCurrent();

    expect(downloadRemoteFile).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith("当前内容没有可下载的地址", "info");
  });

  it("队列为空时静默返回", async () => {
    const store = useImmersiveStore();

    await store.immersiveDownloadCurrent();

    expect(downloadRemoteFile).not.toHaveBeenCalled();
    expect(toast).not.toHaveBeenCalled();
  });

  it("直链失败时把错误抛成 toast", async () => {
    const store = useImmersiveStore();
    downloadRemoteFile.mockRejectedValueOnce(new Error("下载失败: HTTP 403"));
    seedCurrent(store, { id: "7", type: "video", videoUrl: "https://cdn.example/b.mp4" });

    await store.immersiveDownloadCurrent();

    expect(toast).toHaveBeenCalledWith("下载失败: HTTP 403", "error");
  });
});
