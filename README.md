# THYME

一个仿 YouTube/抖音风格的视频社区项目，包含用户端（视频/短视频/关注/聊天/评论举报等）与管理端（视频审核、举报/违规评论处理）。

> 本仓库同时包含前端与后端源码，适合本地一键跑起来调试与二次开发。

---

## 技术栈概览

**后端**
- Spring Boot（REST API + WebSocket）
- MyBatis-Plus（MySQL）
- MongoDB（聊天等模块）
- Redis（登录态/缓存等）
- Elasticsearch（搜索/检索相关）

**前端（用户端）**
- Vue 3 + Vite
- Remix Icon

**前端（管理端）**
- Vue 3 + Vite（`frontend-admin`，简易管理台）

---

## 目录结构

```
MyYoutube/
  Backend/
    my-youtube/                 # Spring Boot 后端工程
  Frontend/                     # 用户端（Vue3 + Vite）
    static/                     # 静态资源（favicon/默认图/音效）
    nginx-1.29.6/html/          # Vite build 输出目录（用户端）
  frontend-admin/               # 管理端（Vue3 + Vite）
```

---

## 本地运行（后端）

### 1) 环境准备

- JDK 17+（推荐）
- Maven 3.8+
- MySQL 8+
- Redis 6+
- MongoDB 4.4+
- Elasticsearch（可选；如功能依赖搜索则需要）

### 2) 配置文件

后端主配置：`Backend/my-youtube/src/main/resources/application.yml`  
敏感信息不要写进仓库，使用以下任意方式提供：

- 环境变量（如 `MONGODB_URI` / `DATASOURCE_USERNAME` / `DATASOURCE_PASSWORD` 等）
- 或创建本地文件（已加入 `.gitignore`）：  
  `Backend/my-youtube/src/main/resources/application-local.yml`

`application-local.yml` 示例（按你的本地环境修改）：

```yml
spring:
  data:
    mongodb:
      uri: mongodb://USERNAME:PASSWORD@localhost:27017/my_youtube?authSource=admin

  datasource:
    username: root
    password: root

  data:
    redis:
      host: localhost
      port: 6379
      password: ""
      database: 0

my-youtube:
  alioss:
    bucket-name: your-bucket
    access-key-id: your-ak
    access-key-secret: your-sk
    endpoint: https://oss-cn-hangzhou.aliyuncs.com

  sts:
    access-key-id: your-sts-ak
    access-key-secret: your-sts-sk
    role-arn: arn:acs:ram::1234567890123456:role/your-role
```

### 3) 启动后端

在 `Backend/my-youtube` 目录：

```bash
mvn spring-boot:run
```

默认端口：`8080`

Swagger（如启用）：  
- `/swagger-ui.html`
- `/v3/api-docs`

---

## 本地运行（用户端 Frontend）

在 `Frontend` 目录：

```bash
npm install
npm run dev
```

用户端开发服务器默认走 Vite 代理到后端（见 `Frontend/vite.config.js`），常见接口前缀如：
`/login` `/register` `/videos` `/comment` `/chat` `/ws` 等。

### 构建输出说明

`Frontend` 的 `npm run build` 会直接输出到：

`Frontend/nginx-1.29.6/html/`

用于配合本地 Nginx 直接托管静态资源（减少手动拷贝）。

---

## 本地运行（管理端 frontend-admin）

在 `frontend-admin` 目录：

```bash
npm install
npm run dev
```

管理端通过 Vite 代理访问后端（见 `frontend-admin/vite.config.js`）。

### 已接入的管理功能

- 视频审核列表：`GET /admin/list`
- 审核通过：`POST /admin/review/pass/{videoId}`
- 审核禁用：`POST /admin/review/ban/{videoId}`
- 举报评论列表：`GET /admin/comment/report`
  - 一键设为违规：`POST /admin/comment/blocked/{commentId}`
  - 忽略举报：`POST /admin/comment/report/ignore/{commentId}`
- 违规评论列表：`GET /admin/comment/list`
  - 恢复评论：`POST /admin/comment/restore/{commentId}`

### 登录与权限说明

- 后端要求请求头携带 `Authorization` token。
- 管理员权限由后端校验（项目默认管理员通常为 `userId = 1`，以你的后端实现为准）。

---

## 功能点（简要）

- 视频/短视频浏览、点赞、关注
- 评论发布、删除、举报
- 聊天（私聊/群聊、群成员列表、转发视频卡片、群公告等）
- 管理端：审核视频、处理举报/违规评论

---

## 常见问题排查

- **MongoDB 连接串错误**
  - `spring.data.mongodb.uri` 必须以 `mongodb://` 或 `mongodb+srv://` 开头。
- **MongoDB 未认证导致 Unauthorized**
  - 在 `uri` 中补 `username/password`，并设置 `authSource=admin`（或你实际创建用户所在库）。
- **接口 401**
  - 检查请求头 `Authorization` 是否携带登录返回的 token。

---

## License

仅供学习与交流使用；如需开源协议请自行补充 LICENSE 文件。

