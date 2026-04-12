import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
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
      "/search": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/oss": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/behavior": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/like": {
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
      "/chat": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      // 与后端 NotifyWebSocketHandler：/ws/notify?token=...
      "/ws": {
        target: "http://localhost:8080",
        changeOrigin: true,
        ws: true,
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
