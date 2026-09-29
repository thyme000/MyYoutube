import { createRouter, createWebHistory } from "vue-router";

export const MENU_ROUTES = [
  { key: "home", path: "/", label: "首页" },
  { key: "shorts", path: "/shorts", label: "短视频" },
  { key: "subscribed", path: "/subscribed", label: "关注" },
  { key: "history", path: "/history", label: "历史播放记录" },
  { key: "liked", path: "/liked", label: "赞过的视频" },
  { key: "music", path: "/music", label: "音乐" },
  { key: "chat", path: "/chat", label: "聊天" },
];

/**
 * 交给 <RouterView> 渲染的菜单。shorts / subscribed 不在内 —— 它们的 ShortsView 在 App 里常驻
 * （搜索结果页要复用同一个 feed 实例），路由再渲染一份会双开，故给空组件占位。
 */
const MENU_VIEWS = {
  home: () => import("../views/HomeView.vue"),
  history: () => import("../views/HistoryView.vue"),
  liked: () => import("../views/LikedView.vue"),
  music: () => import("../views/MusicView.vue"),
  chat: () => import("../views/ChatView.vue"),
};

const routes = [
  ...MENU_ROUTES.map((r) => ({
    path: r.path,
    name: `menu-${r.key}`,
    meta: { menuKey: r.key },
    component: MENU_VIEWS[r.key] ?? { render: () => null },
  })),
];

routes.push({ path: "/:pathMatch(.*)*", redirect: "/" });

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const hit = MENU_ROUTES.find((r) => r.key === to.meta.menuKey);
  document.title = hit ? `${hit.label} · THYME` : "THYME";
  return true;
});

/** /shorts -> "shorts"；未识别路径返回 null（视为首页之外的未知态） */
export function menuKeyForPath(path) {
  const hit = MENU_ROUTES.find((r) => r.path === path);
  return hit ? hit.key : null;
}

/** "shorts" -> "/shorts"；home 归一为 "/" */
export function menuPathFor(key) {
  const hit = MENU_ROUTES.find((r) => r.key === key);
  return hit ? hit.path : "/";
}
