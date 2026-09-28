import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

vi.mock("../../utils/toast", () => ({ toast: vi.fn() }));

import { toast } from "../../utils/toast";
import { useAuthStore } from "../authStore";
import { useShareStore } from "../shareStore";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

describe("shareStore.openForwardVideo", () => {
  it("未登录时提示且不打开", () => {
    const store = useShareStore();
    store.openForwardVideo({ id: 1 });
    expect(store.forwardVideoOpen).toBe(false);
    expect(store.forwardVideoCard).toBeNull();
    expect(toast).toHaveBeenCalledWith("请先登录后使用转发", "info");
  });

  it("缺卡片信息时提示且不打开", () => {
    useAuthStore().signIn("t");
    const store = useShareStore();
    store.openForwardVideo(null);
    store.openForwardVideo({});
    expect(store.forwardVideoOpen).toBe(false);
    expect(toast).toHaveBeenCalledWith("缺少视频信息", "error");
  });

  it("登录且卡片合法时打开并记录目标", () => {
    useAuthStore().signIn("t");
    const store = useShareStore();
    store.openForwardVideo({ id: "1001", title: "标题" });
    expect(store.forwardVideoOpen).toBe(true);
    // state 经 Pinia reactive 包装，读回来是代理，故比内容而非引用
    expect(store.forwardVideoCard).toEqual({ id: "1001", title: "标题" });
    expect(toast).not.toHaveBeenCalled();
  });
});

describe("shareStore.openCommentReport", () => {
  const comment = { id: 9, userId: "2002", content: "普通评论" };

  it("未登录时提示且不打开", () => {
    const store = useShareStore();
    store.openCommentReport(comment);
    expect(store.commentReportOpen).toBe(false);
    expect(toast).toHaveBeenCalledWith("请先登录后再举报", "info");
  });

  it("缺评论信息时提示且不打开", () => {
    useAuthStore().signIn("t");
    const store = useShareStore();
    store.openCommentReport({});
    expect(store.commentReportOpen).toBe(false);
    expect(toast).toHaveBeenCalledWith("缺少评论信息", "error");
  });

  it("不能举报自己的评论（id 按字符串比对）", () => {
    const auth = useAuthStore();
    auth.signIn("t");
    auth.userId = 2002;
    const store = useShareStore();
    store.openCommentReport({ id: 9, userId: 2002, content: "我的评论" });
    expect(store.commentReportOpen).toBe(false);
    expect(toast).toHaveBeenCalledWith("不能举报自己的评论", "info");
  });

  it("已被处理的评论不可举报", () => {
    const auth = useAuthStore();
    auth.signIn("t");
    auth.userId = 1;
    const store = useShareStore();
    store.openCommentReport({ id: 9, userId: "2002", status: "BLOCKED" });
    expect(store.commentReportOpen).toBe(false);
    expect(toast).toHaveBeenCalledWith("该评论已被处理，无法举报", "info");
  });

  it("登录、非本人、未处理时打开并记录目标", () => {
    const auth = useAuthStore();
    auth.signIn("t");
    auth.userId = 1;
    const store = useShareStore();
    store.openCommentReport({ ...comment });
    expect(store.commentReportOpen).toBe(true);
    expect(store.commentReportTarget).toEqual(comment);
    expect(toast).not.toHaveBeenCalled();
  });
});
