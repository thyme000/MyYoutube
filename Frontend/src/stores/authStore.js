/**
 * 认证态：token 与当前用户快照。token 持久化仍走 api/http.js（localStorage myyoutube_token）。
 * 拆 views/ 后各页面从这里取登录态，不再由 App.vue 逐层传递。
 */
import { defineStore } from "pinia";
import { getToken, setToken as persistToken, clearToken } from "../api/http";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    token: getToken() || "",
    /** 当前登录用户快照（登录/资料更新时填充） */
    userId: null,
    nickName: "",
    avatarUrl: "",
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
    signOut() {
      this.token = "";
      clearToken();
      this.userId = null;
      this.nickName = "";
      this.avatarUrl = "";
    },
    /** 页面重新可见等场景下与 localStorage 对齐 */
    refreshFromStorage() {
      this.token = getToken() || "";
    },
  },
});
