# 视频上传事件通知

上传链路为 RustFS → transcode `/internal/media/storage-event` → RocketMQ
`media_upload_event_topic` → 视频草稿异步媒体准备（PREPARE）。

Compose 的 `RUSTFS_NOTIFY_*` 环境变量只注册 webhook 目标，不会创建桶订阅。
后端启动时也只检查桶是否存在。新建桶后必须单独应用 `video-notification.json`，
否则视频对象上传成功，草稿仍停在 `uploading`。

## 配置桶订阅

先启动 RustFS、transcode 和 video，确保桶已创建。安装 AWS CLI，并设置
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

PUT 会替换整个桶的通知配置；已有其他订阅时，先备份并合并全部规则，再提交。
确认读回结果包含 `video-upload`、`s3:ObjectCreated:*` 和 `uploads/video/` 前缀。
桶订阅随存储卷持久化；新桶或重建数据卷后需要重新配置。

## 验证与恢复

- 在 RustFS 容器内检查 `host.docker.internal:8086` 能否访问；部署到服务器时按实际网络调整地址。
- 若启用了 `my-youtube.media.webhook-token`，webhook 目标 URL 必须携带相同的 `?token=`。
  修改 RustFS 环境变量后需重新创建容器。
- 上传 `uploads/video/` 下的临时对象，确认 transcode 出现“已桥接上传事件”日志，
  并检查 `transcode_media_upload_event_group` 的消费进度。没有业务草稿的临时对象不会创建媒体准备任务，验证后删除。
- 新增订阅不会补发以前的上传事件。对卡在 `uploading` 的草稿，先用 S3 HEAD 确认对象存在，
  再将该对象的 URL 编码 key 和真实 size 作为 `s3:ObjectCreated:Put` 的 `Records` 重放到 webhook。
  消费者仍按对象 key 反查草稿，并启动 PREPARE 固定原片、校验并准备公共帧；不要直接修改数据库状态。
