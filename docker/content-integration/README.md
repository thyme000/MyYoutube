# 内容统一隔离联调

使用 `docker/compose.content-integration.yml`，项目名固定为 `myyoutube-content-it`。它只发布本机专用端口，不复用 `docker/docker-compose.yml` 的应用容器、网络或数据卷。测试凭据仅用于这套临时环境；不要将其部署为业务服务。

| 服务 | 测试端口 |
| --- | --- |
| MySQL | 13306 |
| Mongo 副本集 | 17017 |
| Elasticsearch | 19200 |
| Redis | 16379 |
| RustFS S3 | 19000 |
| RocketMQ nameserver / broker | 19876 / 20911、20909 |
| Nacos HTTP / gRPC | 18848 / 19848 |

从仓库根目录启动。`--pull never` 要求镜像已缓存。等待 MySQL healthy、Mongo 副本集选出主节点、ES 响应、Nacos 启动完成、broker 注册完成后执行测试；同一套端口不要并行运行多次 Maven。

```sh
docker compose -f docker/compose.content-integration.yml up -d --pull never
docker compose -f docker/compose.content-integration.yml ps
docker compose -f docker/compose.content-integration.yml logs --tail 10 nacos broker

mvn -o -f Backend/pom.xml test \
  -Dcontent.integration=true -Dpipeline.live=true -Dffmpeg.tests=true \
  -Dffmpeg.command=ffmpeg -Dffprobe.command=ffprobe
```

需要 Java 17 或更高版本、Maven 依赖缓存，以及 PATH 中的 ffmpeg/ffprobe。未缓存 Maven 依赖时自行准备依赖，或按环境允许去掉 `-o`。普通 `mvn test` 使用原有 H2/单测边界，真实基础设施检查需显式启用。

`content.integration=true` 让草稿、审核事务、访问/评论和续播四组用例，以及媒体任务推进用例使用隔离 MySQL。测试固定连接 `127.0.0.1:13306/content_it`，先检查初始化标记表，再清理各用例所需的测试表。下载联调用最终 schema 的 transcode_task 建表语句；两个独立 JVM 通过真实 MQ 消费、真实 Redis 锁和真实 S3 下载/上传执行 FFmpeg 合成。测试进程的日志位于 `/private/tmp/content_it_*-worker-*.log`。

下载检查覆盖重复投递、缓存失效、没有初次投递的 PENDING 补偿，以及认领后执行进程被强制终止的 PROCESSING 恢复。测试将 Redis watchdog 调为 3 秒，先确认续期超过初始租期，再终止进程并确认锁释放。独立的 LiveInfrastructureTest 验证 Nacos 双实例发现/轮询及 MQ 重试耗尽后的死信保留和监控读数。

Mongo 使用名为 content-it 的临时副本集，ES 使用名为 content-it 的临时集群。LiveForwardContentTest 先核对副本集名称，只清理 content_it 库中的聊天测试集合；LiveContentSearchTest 先核对集群名称，只在该测试集群创建 content/user 索引。实际运行 Mongo 事务及 ES 映射、保存、bulk 更新和删除。

这些是隔离组件联调。聊天的成员/关系守卫和内容权限端口，搜索的内容/用户端口、向量模型，业务 HTTP 网关、用户计数和 SSE 等边界仍有 mock，不能视作完整部署链路验收，也不代表设备兼容或性能基准。

## 真实 ASR/OCR

本机需已安装两个服务的依赖并准备模型缓存。等隔离 Nacos 就绪后，在两个终端分别启动；无需额外 Uvicorn worker。这里将 ASR 缩为一个推理进程以控制联调内存，不验证生产双进程吞吐。

```sh
cd asr-svc
NACOS_ADDR=127.0.0.1:18848 SERVER_PORT=18089 ASR_CONCURRENCY=1 sh start.sh
```

```sh
cd ocr-svc
NACOS_ADDR=127.0.0.1:18848 SERVER_PORT=18088 PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True sh start.sh
```

ASR/OCR 每 15 秒向 Nacos 实时核对自己的注册；实例丢失时补注册，连接失效时重建客户端并退避重试。启动时 Nacos 暂不可用，模型进程仍运行并在后台重试，注册恢复前 `/health` 返回 503。`NACOS_CHECK_INTERVAL`、`NACOS_OPERATION_TIMEOUT`、`NACOS_RECONNECT_MAX_DELAY` 默认分别为 15、10、60 秒，可在服务进程环境调整。`/health` 返回 `discovery.registered`。联调可运行 `PYTHONPATH=. asr-svc/.venv/bin/python -m model_runtime.check_discovery_recovery`，用唯一临时服务验证补注册及连接恢复；它默认连接本机 8848，隔离 Nacos 需设置 `NACOS_ADDR=127.0.0.1:18848`。

确认两者 /health 就绪后，从仓库根目录执行，WAV 样本需至少十秒且包含语音：

```sh
mvn -o -f Backend/pom.xml -pl my-youtube-transcode -am test \
  -Dtest=LiveNativeMediaTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcontent.integration=true -Dcontent.models=true -Dcontent.asr.sample=/absolute/path/speech.wav
```

LiveNativeMediaTest 使用生产 ASR/OCR 执行器、真实 Python HTTP 服务和隔离 RustFS，验证原始长配乐、两张有文字原图的顺序/时间映射、无配乐、缺图失败以及视频八帧回归。测试中的 HTTP 适配使用 RestClient，未覆盖生产 Feign/网关/鉴权部署。

OCR 服务空闲且没有其他模型测试运行时，可从根目录执行 `ocr-svc/.venv/bin/python ocr-svc/check_runtime.py`。它验证单图/批量共用队列、满载 503 和 Retry-After、合法空结果及名额释放；这是容量边界功能检查，不是吞吐基准。结束时先停止两个 Python 测试服务，使其注销并关闭模型子进程，再清理 compose。

结束后仅删除测试项目：

```sh
docker compose -f docker/compose.content-integration.yml down -v
```

生产下载恢复依赖最终 transcode_task.next_dispatch_at 和 idx_dispatch，需与转码服务协调部署。每 30 秒查询最多 100 条到期任务，数据库时钟条件认领后延后 60 秒；活跃成片持锁时跳过执行。MQ 投递失败保留任务 ID，由后续扫描恢复。数据库/Redis/MQ 持续故障时恢复继续等待；这不是跨系统 exactly-once 保证。

## 完整业务路径

本机 Ollama 11434 已缓存 qwen3.5:9b 和 qwen3-embedding:8b 时，可执行真实模型检查。它启动临时本机 Tomcat，使用生产 LlmPort 的 Spring MVC Feign 契约连接真实内部控制器，再调用本机模型；包含两种内容的 SUMMARY/TAG/文本 REVIEW 与显式图片 REVIEW。产物存储、词库和策略登记端口使用内存/mock，模型输出和 HTTP 不使用桩。

```sh
mvn -o -f Backend/pom.xml -pl my-youtube-llm -am test \
  -Dtest=LiveContentModelTest -Dsurefire.failIfNoSpecifiedTests=false -Dcontent.llm=true
```

基础设施就绪后，下面的运行器启动八个 Java 服务和两个 Python 服务，通过真实 Nacos/Feign/MQ、网关登录和 Bearer 鉴权，检查两类内容到发布、列表、搜索、转发、下载、私密权限、作者计数、作者审核通知与 SSE。可加 `--components` 在 Java 服务启动前执行全部显式启用的组件套件。

```sh
mvn -o -f Backend/pom.xml package -DskipTests
python3 docker/content-integration/run_workflow.py --components --speech /absolute/path/speech.wav
```

**此运行器会重建测试项目内的 user_db/video_db/chat_db/transcode_db，并清空测试 Redis/Mongo/ES。**配置固定在本目录 application.yml，检查 MySQL 标记及 Mongo/ES 名称，使用专用端口 18080~18089；不要与其他测试并行。依赖已经缓存的模型和工具，不下载模型或调用云服务。日志写到新的 content-final-workflow-* 临时目录，结束自动停止自己创建的 Java/Python/检查子进程；之后仍需执行 compose down -v。

视频素材经真实签名 POST 上传，但测试手动重放 RustFS 通知载荷到真实 storage-event/MQ 桥接，不覆盖对象存储通知配置本身。测试配置明确为无密码 Redisson 连接，仅用于本机隔离实例；不得作为业务部署配置。正式部署顺序和剩余运行验收见 [交付说明](../../docs/content-unification-handover.md)。


## 媒体准备与页面验收

视频上传事件启动 PREPARE，保留原片、不强制转码；动态保存后也需等 READY 再提交。`PreparationWorkflowTest` 支持 `-Dcontent.integration=true` 使用隔离 MySQL。新增 `LiveMediaPreparationTest` 还需 `-Dpreparation.live=true`，覆盖真实 RustFS 条件复制、版本删除、图片复用、视频字节保留／公共 8 帧及共享引用回收；`-Dcontent.models=true` 额外启用真实 OCR 正常文字对比。普通回归默认跳过这些用例。原始 4K OCR 会消耗较多内存，避免与其他模型负载同时执行。

页面状态与恢复检查使用实际 Vue 页面及受控 API：

```sh
cd Frontend/my-youtube
node scripts/e2e/smoke-preparation.mjs
```

真实播放检查需完整业务运行器已经通过，并通过 `--hold-seconds 600` 保留服务；在另一个终端执行：

```sh
cd Frontend/my-youtube
node scripts/e2e/smoke-prepared-playback.mjs
```

两份脚本使用既有浏览器 CDP harness 并自动清理自己的页面与 Vite 进程。真实脚本连接隔离网关 18080 和测试账号，仅用于该隔离环境。`--hold-seconds` 最大 1800 秒，结束后运行器停止自己的子进程；仍需 compose down -v。浏览器通过不代表跨设备兼容或内存稳定性通过。本次实际结果及未验收边界见 [媒体准备报告第六批](../../docs/media-preparation-optimization-report.md)。
