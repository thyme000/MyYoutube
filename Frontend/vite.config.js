import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
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
    },
  },
});
