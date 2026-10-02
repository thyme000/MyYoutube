import { createRouter, createWebHistory } from "vue-router";
import { MENU_ROUTES } from "./menuRoutes.js";
import {
  loadMenuView,
  loadStudioView,
  loadProfileCenterView,
  loadVideoDetailView,
  loadDynamicDetailView,
} from "./views.js";

export { MENU_ROUTES };

/**
 * 菜单路由 /studio /profile 由 <RouterView>（经 App 主区解析器）渲染真实视图。
 * shorts / subscribed 的 ShortsView 在 App 里常驻（搜索结果页要复用同一个 feed 实例），
 * /upload 叠在来路视图之上（App 解析器归底），/feed 的连播 UI 在常驻 ShortsView 内 —— 三者给空组件占位。
 */
const NULL_VIEW = { render: () => null };

const routes = [
  ...MENU_ROUTES.map((r) => ({
    path: r.path,
    name: `menu-${r.key}`,
    meta: { menuKey: r.key },
    components: { default: loadMenuView[r.key] ?? NULL_VIEW },
  })),
  /** 创作中心（YouTube Studio 风格）：路由承载 URL 与历史栈，/upload 可叠其上 */
  {
    path: "/studio",
    name: "studio",
    meta: { title: "创作中心" },
    components: { default: loadStudioView },
  },
  /** 上传弹窗：叠层渲染（归底到来路视图），URL 可深链可后退 */
  {
    path: "/upload",
    name: "upload",
    meta: { title: "上传视频" },
    components: { default: NULL_VIEW },
  },
  /** 个人中心：/profile 自己，/profile/:userId 他人 */
  {
    path: "/profile",
    name: "profile-self",
    meta: { title: "个人中心" },
    components: { default: loadProfileCenterView },
  },
  {
    path: "/profile/:userId",
    name: "profile-user",
    meta: { title: "个人中心" },
    components: { default: loadProfileCenterView },
  },
  /** 全屏连播：scope ∈ liked|history|profile|follow；队列在 shortsStore，URL 只承载历史栈 */
  {
    path: "/feed/:scope/:id?",
    name: "feed",
    meta: { title: "连播" },
    components: { default: NULL_VIEW },
  },
  /** 视频详情：命名视图渲染在 main 之外（main 被 v-show 隐藏）；详情内切视频用 replace 不叠历史 */
  {
    path: "/video/:id",
    name: "video",
    meta: { title: "视频详情" },
    components: { detail: loadVideoDetailView },
  },
  /** 动态详情：与视频详情同一命名视图层 */
  {
    path: "/dynamic/:id",
    name: "dynamic",
    meta: { title: "动态详情" },
    components: { detail: loadDynamicDetailView },
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
