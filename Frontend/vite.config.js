import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  test: {
    // 刻意不用 jsdom：本机 jsdom 环境初始化要 50 秒以上（纯 util 用例从 3.9s 涨到 57s）。
    // store 单测真正需要的浏览器 API 只有 localStorage，由 setup 文件给桩。
    // 注：test 是 Vitest 的扩展键，若要开 vite.config.js 的 @ts-check，defineConfig 需改从 vitest/config 引入。
    setupFiles: ["./vitest.setup.js"],
  },
  server: {
    proxy: {
      /** 必须排在 /search 等前缀之前，避免 dev 环境下路径匹配歧义；见 src/api/musicApi.js */
      "/music-svc": {
        target: "http://localhost:5000",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/music-svc/, ""),
      },
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/login": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/register": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/captcha": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/forget-password": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/user": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/videos": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 统一内容池(视频/动态混排); 无同名 SPA 路由, 裸前缀即可
      "/contents": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 带尾斜杠: 裸 /dynamics 是旧动态页的重定向 SPA 路由, 深链刷新不应转发给后端
      "/dynamics/": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/audios": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/search": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/storage": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/behavior": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 带尾斜杠: 裸 /liked 是"赞过的视频"页的 SPA 路由, 不应转发给后端
      "/like/": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/follow": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/comment": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/notification": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 带尾斜杠: 裸 /chat 是聊天页的 SPA 路由, 不应转发给后端
      "/chat/": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/emoji": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 与后端 ChatWebSocketHandler：/ws/chat?token=...（聊天页专用, 通知类走 /sse）
      "/ws": {
        target: "http://localhost:8080",
        changeOrigin: true,
        ws: true,
      },
      // SSE 门铃通知: http-proxy 默认流式转发, 无需特殊配置
      "/sse": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    // 直接输出到你现有的 Nginx 静态目录，减少手动拷贝步骤
    outDir: "nginx-1.29.6/html",
    assetsDir: "assets",
    emptyOutDir: true,
  },
});
