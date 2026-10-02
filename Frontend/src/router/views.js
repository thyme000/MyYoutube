import { defineAsyncComponent } from "vue";

/**
 * 视图组件注册表：路由表（挂载真实组件）与 App 主区解析器共用，保证代码分割与单一来源。
 * SearchView / ShortsView 不在此列 —— 前者是未路由的搜索态，后者常驻 App（搜索页复用同一 feed 实例防双开）。
 */
export const loadMenuView = {
  home: () => import("../views/HomeView.vue"),
  history: () => import("../views/HistoryView.vue"),
  liked: () => import("../views/LikedView.vue"),
  music: () => import("../views/MusicView.vue"),
  chat: () => import("../views/ChatView.vue"),
};

export const loadStudioView = () => import("../views/StudioView.vue");
export const loadProfileCenterView = () => import("../views/ProfileCenterView.vue");
export const loadVideoDetailView = () => import("../views/VideoDetailView.vue");
export const loadDynamicDetailView = () => import("../views/DynamicDetailView.vue");

export const MenuView = Object.fromEntries(
  Object.entries(loadMenuView).map(([key, loader]) => [key, defineAsyncComponent(loader)])
);

export const StudioView = defineAsyncComponent(loadStudioView);
export const ProfileCenterView = defineAsyncComponent(loadProfileCenterView);
