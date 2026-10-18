# 视频上传事件通知

上传链路为 RustFS → transcode `/internal/media/storage-event` → RocketMQ
`media_upload_event_topic` → 视频草稿异步媒体准备（PREPARE）。

上传事件消费者在没有已保存消费进度时从队列最早保留的消息开始，避免首条事件先于新消费组队列分配到达而被跳过；已有进度的消费组继续从原位置读取。重复事件仍按对象 key 查询草稿，已完成或已提交的草稿不会重新准备。修改消费起点不会恢复已经跳过的事件，这类草稿需要按下文确认对象后重放通知。

Compose 的 `RUSTFS_NOTIFY_*` 环境变量只注册 webhook 目标，不会创建桶订阅。
video 服务启动时会检查 `video-upload` 桶订阅，之后每 60 秒检查一次；
桶被删除后会重建桶并恢复该订阅。检查只更新 `video-upload`，保留其他通知规则。
若 RustFS 暂时不可用，检查会记录告警并在下一轮重试。

此行为仅适用于本项目的 RustFS webhook。使用其他 S3 存储时，设置
`S3_VIDEO_UPLOAD_NOTIFICATION_ENABLED=false`；如 webhook 目标名称不同，可设置
`S3_VIDEO_UPLOAD_NOTIFICATION_QUEUE_ARN`。检查间隔可通过
`S3_VIDEO_UPLOAD_NOTIFICATION_CHECK_INTERVAL_MS` 调整。

## 手动检查与恢复

正常启动 video 后无需执行下列命令。需要手动排查时，先启动 RustFS、transcode 和 video。
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
桶订阅随存储卷持久化；新桶或重建数据卷后由 video 自动恢复。

## 验证与恢复

- 在 RustFS 容器内检查 `host.docker.internal:8086` 能否访问；部署到服务器时按实际网络调整地址。
- 若启用了 `my-youtube.media.webhook-token`，webhook 目标 URL 必须携带相同的 `?token=`。
  修改 RustFS 环境变量后需重新创建容器。
- 上传 `uploads/video/` 下的临时对象，确认 transcode 出现“已桥接上传事件”日志，
  并检查 `transcode_media_upload_event_group` 的消费进度。没有业务草稿的临时对象不会创建媒体准备任务，验证后删除。
- 新增订阅不会补发以前的上传事件。对卡在 `uploading` 的草稿，先用 S3 HEAD 确认对象存在，
  再将该对象的 URL 编码 key 和真实 size 作为 `s3:ObjectCreated:Put` 的 `Records` 重放到 webhook。
  消费者仍按对象 key 反查草稿，并启动 PREPARE 固定原片、校验并准备公共帧；不要直接修改数据库状态。
