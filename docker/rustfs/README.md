# 视频上传事件通知

上传链路为 RustFS → transcode `/internal/media/storage-event` → RocketMQ
`media_upload_event_topic` → 视频草稿异步媒体准备（PREPARE）。

上传事件消费者在没有已保存消费进度时从队列最早保留的消息开始，避免首条事件先于新消费组队列分配到达而被跳过；已有进度的消费组继续从原位置读取。重复事件仍按对象 key 查询草稿，已完成或已提交的草稿不会重新准备。修改消费起点不会恢复已经跳过的事件，这类草稿需要按下文确认对象后重放通知。

Compose 的 `RUSTFS_NOTIFY_*` 环境变量只注册 webhook 目标，不会创建桶订阅。
内容服务启动时会检查 `video-upload` 桶订阅，之后每 60 秒检查一次；
桶被删除后会重建桶并恢复该订阅。检查只更新 `video-upload`，保留其他通知规则。
若 RustFS 暂时不可用，检查会记录告警并在下一轮重试。

此行为仅适用于本项目的 RustFS webhook。使用其他 S3 存储时，设置
`S3_VIDEO_UPLOAD_NOTIFICATION_ENABLED=false`；如 webhook 目标名称不同，可设置
`S3_VIDEO_UPLOAD_NOTIFICATION_QUEUE_ARN`。检查间隔可通过
`S3_VIDEO_UPLOAD_NOTIFICATION_CHECK_INTERVAL_MS` 调整。

## 手动检查与恢复

正常启动内容服务后无需执行下列命令。需要手动排查时，先启动 RustFS、transcode 和内容服务。
安装 AWS CLI，并设置
`AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`，值与 RustFS 的 S3 凭据一致。
从仓库根目录执行：

```sh
aws --endpoint-url "${S3_ENDPOINT:-http://localhost:9000}" --region us-east-1 \
  s3api get-bucket-notification-configuration \
  --bucket "${S3_BUCKET_NAME:-myyoutube}"

aws --endpoint-url "${S3_ENDPOINT:-http://localhost:9000}" --region us-east-1 \
  s3api put-bucket-notification-configuration \
  --bucket "${S3_BUCKET_NAME:-myyoutube}" \
  --notification-configuration file://docker/rustfs/video-notification.json

aws --endpoint-url "${S3_ENDPOINT:-http://localhost:9000}" --region us-east-1 \
  s3api get-bucket-notification-configuration \
  --bucket "${S3_BUCKET_NAME:-myyoutube}"
```

手动 PUT 会替换整个桶的通知配置；已有其他订阅时，先备份并合并全部规则，再提交。
确认读回结果包含 `video-upload`、`s3:ObjectCreated:*` 和 `uploads/video/` 前缀。
桶订阅随存储卷持久化；新桶或重建数据卷后由内容服务自动恢复。

## 验证与恢复

- 在 RustFS 容器内检查 `host.docker.internal:8086` 能否访问；部署到服务器时按实际网络调整地址。
- 若启用了 `my-youtube.media.webhook-token`，webhook 目标 URL 必须携带相同的 `?token=`。
  修改 RustFS 环境变量后需重新创建容器。
- 上传 `uploads/video/` 下的临时对象，确认 transcode 出现“已桥接上传事件”日志，
  并检查 `transcode_media_upload_event_group` 的消费进度。没有业务草稿的临时对象不会创建媒体准备任务，验证后删除。
- 新增订阅不会补发以前的上传事件。对卡在 `uploading` 的草稿，先用 S3 HEAD 确认对象存在，
  再将该对象的 URL 编码 key 和真实 size 作为 `s3:ObjectCreated:Put` 的 `Records` 重放到 webhook。
  消费者仍按对象 key 反查草稿，并启动 PREPARE 固定原片、校验并准备公共帧；不要直接修改数据库状态。

## 浏览器上传与 HLS 播放 CORS

正式视频的 `GET /contents/{id}` 详情在 `media.video.playback` 中返回 HLS 总目录、
各画质目录、`playbackVersion` 和毫秒时间戳 `expiresAt`；不再提供独立 `/sources` 接口。
视频媒体统一返回 `durationMs` 与 `playback`，不再提供单文件 `previewUrl`。
卡片悬停满 500ms 后才请求详情，使用 HLS 总目录的自动档；hls.js 的预览缓冲目标为
8 秒、上限目标 12 秒，移出或滚动后销毁实例并取消未完成请求。原生 HLS 的缓冲由浏览器管理。

管理端 `GET /admin/content/{id}/media` 返回审核用途的 HLS 播放信息，流路径为
`/admin/content/{id}/streams/**`。播放票的 `VIEW`/`REVIEW` 用途签入 JWT，禁止跨用途或跨视频使用。
审核目录每次经过 admin 服务重新检查当前角色，再通过内部内容接口读取未删除内容的 HLS；
未发布内容也可审核，播放资源未准备好时返回状态冲突，不回退原片。
管理端续期复用自己的登录通道，分片不读写主站共享缓存，也不记录观看行为。

应用端的播放目录 URL 携带 `playbackToken`，播放器不应向目录入口或存储附加主站 Bearer。
网关仅在指定视频及对应用途的播放路径上接受该凭证，并继续检查改密/封号的 token-ver；
校验后剥除 query 凭证和 Authorization，以内部头传给内容服务。各层目录使用相对 URL，
会显式携带同一凭证，兼容 API 代理前缀。子目录的初始化段和全部媒体字节范围共用
一个 GET 存储签名 URL，播放器直接发送 Range；应用层 `media.mp4` 及内部重签接口已删除。
每次获取子目录重新检查内容权限，存储 URL 最多有效 20 分钟，且不超过播放票的剩余有效期。

播放票使用登录密钥派生的独立签名密钥，不依赖新的 Redis 会话；网关和内容服务必须配置
一致的 `jwt.secret`。`my-youtube.playback.ttl-seconds` 默认 21600 秒，签发时至少覆盖视频
时长加一小时。长时间暂停后，前端重新请求详情并恢复进度；hls.js 的流入口 401 也只重试
一次重新授权。hls.js 在未缓存的分片下载前检查签名时间，剩余不足一分钟时重新授权并
读取原画质子目录；存储 403 也触发一次刷新，再重试原 Range。同档并发续期合并，取消
最后一个等待者时终止续期请求。分片缓存按存储对象路径、档位、版本和 Range 区分，
不使用签名参数作为身份。原生 HLS 在播放恢复或到期错误时换源并恢复进度；Safari、
投屏及长视频仍需设备验证。
入口代理的访问日志应对 `playbackToken` 和存储签名参数脱敏；播放目录、详情和鉴权失败响应禁止缓存。
下架、改私密、封号及管理员角色降级会阻止再次授权，但已发出的存储 URL 在到期前仍可使用；
已取得的字节也不能撤回。存储需要对签名到期的 403 响应同样返回正确的 CORS 头。

前端通过网关取得目录，媒体字节直接从 RustFS 下载，没有逐分片的 302。浏览器因此需要存储允许
站点 origin 的 GET/HEAD、Range 请求，以及视频直传使用的 PUT。`Content-Range` 和
`Accept-Ranges` 应暴露给页面；仅允许跨域请求不会自动使这些响应头可读。

`docker/rustfs/playback-cors.json` 是本机 Vite 默认端口的示例。部署时先把
`AllowedOrigins` 换成实际站点 origin（协议、主机、端口一致），再从仓库根目录执行：

```sh
aws --endpoint-url "${S3_ENDPOINT:-http://127.0.0.1:9000}" --region us-east-1 \
  s3api get-bucket-cors --bucket "${S3_BUCKET_NAME:-myyoutube}"

aws --endpoint-url "${S3_ENDPOINT:-http://127.0.0.1:9000}" --region us-east-1 \
  s3api put-bucket-cors --bucket "${S3_BUCKET_NAME:-myyoutube}" \
  --cors-configuration file://docker/rustfs/playback-cors.json

aws --endpoint-url "${S3_ENDPOINT:-http://127.0.0.1:9000}" --region us-east-1 \
  s3api get-bucket-cors --bucket "${S3_BUCKET_NAME:-myyoutube}"
```

`put-bucket-cors` 会替换桶的整套 CORS 规则；已有其他业务规则时，先导出并合并后再写入。
`NoSuchCORSConfiguration` 表示当前没有桶规则。凭据沿用上文 AWS CLI 环境变量，勿写入 JSON。
CORS 不代替签名或权限检查。若存储反代到应用同源，代理还应去掉站点 Authorization，
避免将主站 Bearer 转发到 S3。
