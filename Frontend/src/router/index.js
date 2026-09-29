import { createRouter, createWebHistory } from "vue-router";
import { MENU_ROUTES } from "./menuRoutes.js";

export { MENU_ROUTES };

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
  /** 创作中心：App 以叠层渲染 StudioView，路由只承载 URL 与历史栈 */
  {
    path: "/studio",
    name: "studio",
    meta: { title: "创作中心" },
    component: { render: () => null },
  },
  /** 上传弹窗：同上，叠层渲染，URL 可深链可后退 */
  {
    path: "/upload",
    name: "upload",
    meta: { title: "上传视频" },
    component: { render: () => null },
  },
  /** 个人中心：/profile 自己，/profile/:userId 他人；ProfileCenterView 由 App 叠层渲染 */
  {
    path: "/profile",
    name: "profile-self",
    meta: { title: "个人中心" },
    component: { render: () => null },
  },
  {
    path: "/profile/:userId",
    name: "profile-user",
    meta: { title: "个人中心" },
    component: { render: () => null },
  },
  /** 全屏连播：scope ∈ liked|history|profile|follow；队列在 shortsStore，URL 只承载历史栈 */
  {
    path: "/feed/:scope/:id?",
    name: "feed",
    meta: { title: "连播" },
    component: { render: () => null },
  },
];

routes.push({ path: "/:pathMatch(.*)*", redirect: "/" });

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const hit = MENU_ROUTES.find((r) => r.key === to.meta.menuKey);
  document.title = hit ? `${hit.label} · THYME` : to.meta.title ? `${to.meta.title} · THYME` : "THYME";
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
