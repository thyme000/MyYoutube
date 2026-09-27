# Frontend

Vue 3 + Vite。开发：`npm install && npm run dev`；构建产物直接输出到 `nginx-1.29.6/html`。

## 外部服务依赖

- 后端网关：dev 由 `vite.config.js` 代理到 `localhost:8080`，生产由 `nginx-1.29.6/conf/nginx.conf` 反代。
- 音乐解析服务（`/music-svc`）：独立的本地网易云解析服务，监听 `localhost:5000`，**不在本仓库内**。dev 与 Nginx 均按前缀 `/music-svc` 反代到该端口；未启动时音乐页功能降级，其余页面不受影响。

## 字体与图标

图标用 npm 包 `remixicon`（经 main.js 引入，随构建产物本地打包），无任何 CDN / Google Fonts 外链，离线环境可用。
