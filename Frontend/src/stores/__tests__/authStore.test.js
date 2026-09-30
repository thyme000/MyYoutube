import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { DEFAULT_PROFILE_AVATAR_URL } from "../../utils/userProfile";
import { useAuthStore } from "../authStore";

const TOKEN_KEY = "myyoutube_token";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("authStore 初始态", () => {
  it("没有本地 token 时是未登录", () => {
    const auth = useAuthStore();
    expect(auth.token).toBe("");
    expect(auth.isAuthed).toBe(false);
  });

  it("从 localStorage 恢复 token，并剥掉误加的 Bearer 前缀", () => {
    localStorage.setItem(TOKEN_KEY, "Bearer abc123");
    const auth = useAuthStore();
    expect(auth.token).toBe("abc123");
    expect(auth.isAuthed).toBe(true);
  });
});

describe("authStore.signIn / signOut", () => {
  it("登录写入 state 与 localStorage", () => {
    const auth = useAuthStore();
    auth.signIn("abc123");
    expect(auth.token).toBe("abc123");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("abc123");
    expect(auth.isAuthed).toBe(true);
  });

  it("signIn 支持双 token 对象, access/refresh 分别持久化", () => {
    const auth = useAuthStore();
    auth.signIn({ accessToken: "acc.jwt", refreshToken: "ref-uuid" });
    expect(auth.token).toBe("acc.jwt");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("acc.jwt");
    expect(localStorage.getItem("myyoutube_refresh_token")).toBe("ref-uuid");
  });

  it("登出清空双 token、用户信息与 localStorage", () => {
    const auth = useAuthStore();
    auth.signIn({ accessToken: "acc.jwt", refreshToken: "ref-uuid" });
    auth.userId = 9001;
    auth.nickName = "某人";
    auth.avatarUrl = "/a.png";
    auth.setSelfProfile({ avatarUrl: "/a.png" });

    auth.signOut();

    expect(auth.token).toBe("");
    expect(auth.userId).toBeNull();
    expect(auth.nickName).toBe("");
    expect(auth.avatarUrl).toBe("");
    expect(auth.selfProfile).toBeNull();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem("myyoutube_refresh_token")).toBeNull();
    expect(auth.isAuthed).toBe(false);
  });

  it("refreshFromStorage 与 localStorage 对齐（页面重新可见等场景）", () => {
    const auth = useAuthStore();
    auth.signIn("abc123");
    localStorage.setItem(TOKEN_KEY, "new-token");
    auth.refreshFromStorage();
    expect(auth.token).toBe("new-token");

    localStorage.removeItem(TOKEN_KEY);
    auth.refreshFromStorage();
    expect(auth.token).toBe("");
  });
});

describe("authStore.setCurrentUser", () => {
  it("只覆盖传入的字段，缺省字段保持原值", () => {
    const auth = useAuthStore();
    auth.setCurrentUser({ userId: 9001, nickName: "某人", avatarUrl: "/a.png" });
    auth.setCurrentUser({ nickName: "改名了" });

    expect(auth.userId).toBe(9001);
    expect(auth.nickName).toBe("改名了");
    expect(auth.avatarUrl).toBe("/a.png");
  });

  it("空对象与无参调用都不清空已有信息", () => {
    const auth = useAuthStore();
    auth.setCurrentUser({ userId: 9001, nickName: "某人" });
    auth.setCurrentUser();
    auth.setCurrentUser({});
    expect(auth.userId).toBe(9001);
    expect(auth.nickName).toBe("某人");
  });
});

describe("authStore.selfAvatarUrl", () => {
  it("有资料快照时用它，没有时回落到默认头像", () => {
    const auth = useAuthStore();
    expect(auth.selfAvatarUrl).toBe(DEFAULT_PROFILE_AVATAR_URL);

    auth.setSelfProfile({ avatarUrl: "/uploads/a.png" });
    expect(auth.selfAvatarUrl).toBe("/uploads/a.png");
  });

  it("空值或后端把 null 序列化成字符串时回落，不返回空串", () => {
    const auth = useAuthStore();
    for (const bad of [null, "", "   ", "null", "undefined"]) {
      auth.setSelfProfile({ avatarUrl: bad });
      expect(auth.selfAvatarUrl, String(bad)).toBe(DEFAULT_PROFILE_AVATAR_URL);
    }
  });
});
