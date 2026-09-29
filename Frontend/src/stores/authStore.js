// @ts-check
/**
 * 认证态：token 与当前用户。token 持久化走 api/http.js（localStorage myyoutube_token）。
 */
import { defineStore } from "pinia";
import { getToken, setToken as persistToken, clearToken } from "../api/http";
import { DEFAULT_PROFILE_AVATAR_URL as defaultAvatar, sanitizeImageUrl } from "../utils/userProfile";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    token: getToken() || "",
    /** 当前登录用户 id（来自 /user/info） */
    userId: null,
    nickName: "",
    avatarUrl: "",
    /** 当前登录用户资料快照（顶栏头像等），不因进入他人主页而被替换 */
    selfProfile: null,
    /** 登录场景（AuthScene）与登出/过期提示共用的消息条 */
    authMessage: "",
    /** 登录/注册请求进行中 */
    authLoading: false,
  }),
  getters: {
    isAuthed: (s) => Boolean(s.token),
    /** 顶栏与评论框的「我」头像：始终取当前登录用户 */
    selfAvatarUrl: (s) => {
      const url = s.selfProfile?.avatarUrl ? sanitizeImageUrl(s.selfProfile.avatarUrl) : "";
      return url || defaultAvatar;
    },
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
