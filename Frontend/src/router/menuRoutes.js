/**
 * 菜单与路径的唯一事实来源。独立成文件是为了让 scripts/e2e 能在 Node 里直接 import ——
 * router/index.js 依赖 vue-router，脱离 DOM 的 Node 环境 import 会失败。
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
