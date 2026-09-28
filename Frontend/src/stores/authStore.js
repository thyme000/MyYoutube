/**
 * 认证态：token 与当前用户。token 持久化走 api/http.js（localStorage myyoutube_token）。
 */
import { defineStore } from "pinia";
import { getToken, setToken as persistToken, clearToken } from "../api/http";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    token: getToken() || "",
    /** 当前登录用户 id（来自 /user/info） */
    userId: null,
    nickName: "",
    avatarUrl: "",
    /** 当前登录用户资料快照（顶栏头像等），不因进入他人主页而被替换 */
    selfProfile: null,
  }),
  getters: {
    isAuthed: (s) => Boolean(s.token),
  },
  actions: {
    signIn(newToken) {
      this.token = newToken || "";
      persistToken(this.token);
    },
    setCurrentUser(user = {}) {
      if (user.userId != null) this.userId = user.userId;
      if (user.nickName != null) this.nickName = user.nickName;
      if (user.avatarUrl != null) this.avatarUrl = user.avatarUrl;
    },
    setSelfProfile(profile) {
      this.selfProfile = profile || null;
    },
    signOut() {
      this.token = "";
      clearToken();
      this.userId = null;
      this.nickName = "";
      this.avatarUrl = "";
      this.selfProfile = null;
    },
    /** 页面重新可见等场景下与 localStorage 对齐 */
    refreshFromStorage() {
      this.token = getToken() || "";
    },
  },
});
