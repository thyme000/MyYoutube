import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

vi.mock("../../api/likeApi", () => ({
  likeComment: vi.fn(),
  cancelLikeComment: vi.fn(),
}));
vi.mock("../../utils/toast", () => ({ toast: vi.fn() }));

import { likeComment, cancelLikeComment } from "../../api/likeApi";
import { toast } from "../../utils/toast";
import { useAuthStore } from "../authStore";
import { useCommentStore } from "../commentStore";

function signIn() {
  useAuthStore().signIn("test-token");
}

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  vi.clearAllMocks();
  likeComment.mockResolvedValue(undefined);
  cancelLikeComment.mockResolvedValue(undefined);
});

describe("commentStore.toggleLike", () => {
  it("缺 id 直接返回 null，不提示也不发请求", async () => {
    const store = useCommentStore();
    signIn();
    expect(await store.toggleLike(null)).toBeNull();
    expect(await store.toggleLike({})).toBeNull();
    expect(likeComment).not.toHaveBeenCalled();
    expect(toast).not.toHaveBeenCalled();
  });

  it("未登录时提示并返回 null", async () => {
    const store = useCommentStore();
    expect(await store.toggleLike({ id: 7, isLike: false, likeCount: 1 })).toBeNull();
    expect(likeComment).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith("请先登录后再点赞", "info");
  });

  it("点赞只返回 +1 补丁，不改动入参行", async () => {
    const store = useCommentStore();
    signIn();
    const row = { id: 7, isLike: false, likeCount: 3 };
    expect(await store.toggleLike(row)).toEqual({ isLike: true, likeCount: 4 });
    expect(likeComment).toHaveBeenCalledWith(7);
    expect(row).toEqual({ id: 7, isLike: false, likeCount: 3 });
  });

  it("取消点赞只返回 -1 补丁", async () => {
    const store = useCommentStore();
    signIn();
    expect(await store.toggleLike({ id: "7", isLike: true, likeCount: 3 })).toEqual({
      isLike: false,
      likeCount: 2,
    });
    expect(cancelLikeComment).toHaveBeenCalledWith("7");
  });

  it("计数缺失或为 0 时不会减成负数", async () => {
    const store = useCommentStore();
    signIn();
    expect(await store.toggleLike({ id: 7, isLike: true })).toEqual({ isLike: false, likeCount: 0 });
    expect(await store.toggleLike({ id: 7, isLike: true, likeCount: 0 })).toEqual({
      isLike: false,
      likeCount: 0,
    });
  });

  it("请求在途时连点被吞掉，且请求结束后解除占用", async () => {
    const store = useCommentStore();
    signIn();
    let resolveLike;
    likeComment.mockReturnValue(new Promise((r) => (resolveLike = r)));

    const first = store.toggleLike({ id: 7, isLike: false, likeCount: 0 });
    expect(store.isLikeBusy(7)).toBe(true);
    expect(await store.toggleLike({ id: 7, isLike: false, likeCount: 0 })).toBeNull();
    expect(likeComment).toHaveBeenCalledTimes(1);

    resolveLike();
    await first;
    expect(store.isLikeBusy(7)).toBe(false);
  });

  it("接口失败时提示错误并返回 null，占用同样被解除", async () => {
    const store = useCommentStore();
    signIn();
    likeComment.mockRejectedValue(new Error("网络开小差"));

    expect(await store.toggleLike({ id: 7, isLike: false, likeCount: 0 })).toBeNull();
    expect(toast).toHaveBeenCalledWith("网络开小差", "error");
    expect(store.isLikeBusy(7)).toBe(false);
  });

  it("失败且无 message 时按当前方向给兜底文案", async () => {
    const store = useCommentStore();
    signIn();
    likeComment.mockRejectedValue(new Error());
    await store.toggleLike({ id: 7, isLike: false, likeCount: 0 });
    expect(toast).toHaveBeenCalledWith("点赞失败", "error");

    cancelLikeComment.mockRejectedValue(new Error());
    await store.toggleLike({ id: 7, isLike: true, likeCount: 1 });
    expect(toast).toHaveBeenCalledWith("取消点赞失败", "error");
  });

  it("忙碌表按字符串 id 归一，数字与字符串视为同一条", async () => {
    const store = useCommentStore();
    signIn();
    let resolveLike;
    likeComment.mockReturnValue(new Promise((r) => (resolveLike = r)));

    const first = store.toggleLike({ id: 7, isLike: false, likeCount: 0 });
    expect(await store.toggleLike({ id: "7", isLike: false, likeCount: 0 })).toBeNull();
    resolveLike();
    await first;
  });
});
