# MyYoutube Vue 项目示例

这是一个基于 `Vue3 + Vite` 的 YouTube 风格前端示例，适合新手先搭结构、再逐步接入 Spring 单体后端。

## 启动项目

```bash
npm install
npm run dev
```

构建生产包：

```bash
npm run build
npm run preview
```

## 已实现功能

- 左侧栏（主导航 + 探索可折叠分组）
- 顶部栏（搜索、创建、通知、个人中心）
- 主内容区（支持接口请求 + 失败回退占位）
- 配置驱动菜单（后续扩展简单）
- 已预留 Spring Boot 接口层（`src/api/http.js`、`src/api/videoApi.js`）

详细说明见 `docs/前端骨架学习指南.md`（文件名未改动，仅做文字说明替换）。
