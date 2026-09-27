import { createRouter, createWebHistory } from "vue-router";
import ChatView from "../views/ChatView.vue";
import MusicView from "../views/MusicView.vue";

/**
 * 阶段一：路由仅承载 URL <-> 菜单 key 的映射，页面仍由 App.vue 状态机托管。
 * 拆 views/ 时每个菜单会替换为真实组件路由。
 */
export const MENU_ROUTES = [
  { key: "home", path: "/", label: "首页" },
  { key: "shorts", path: "/shorts", label: "短视频" },
  { key: "subscribed", path: "/subscribed", label: "关注" },
  { key: "history", path: "/history", label: "历史播放记录" },
  { key: "liked", path: "/liked", label: "赞过的视频" },
  { key: "music", path: "/music", label: "音乐" },
  { key: "chat", path: "/chat", label: "聊天" },
];

const routes = [
  ...MENU_ROUTES.map((r) => ({
    path: r.path,
    name: `menu-${r.key}`,
    meta: { menuKey: r.key },
    // 已拆出 views/ 的菜单挂真实组件，其余仍由 App.vue 状态机托管；
    // 页面改为 <RouterView> 渲染后再切懒加载
    component: r.key === "chat" ? ChatView
      : r.key === "music" ? MusicView
      : { render: () => null },
  })),
];

routes.push({ path: "/:pathMatch(.*)*", redirect: "/" });

export const router = createRouter({
  history: createWebHistory(),
  routes,
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
