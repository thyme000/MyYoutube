package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverResultMessage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_VIDEO_COVER,
        consumerGroup = "transcode_video_cover_group",
        maxReconsumeTimes = 3)
public class VideoCoverListener implements RocketMQListener<VideoCoverMessage> {

    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    // 系统临时目录, JVM 已按平台适配, 不再用项目内相对路径
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "video-cover");
    private static final String PREFIX_STORAGE = "video-cover";
    private static final String DEFAULT_COVER_RESOURCE = "cover/default-video.png";
    private static final String DEFAULT_COVER_OBJECT_KEY = "video-cover/default-video.png";

    /** 默认图上传成功后缓存 objectKey, 避免重复上传 */
    private volatile String defaultCoverKey;

    /**
     * 生成视频封面: 下载视频 -> 抽帧评分 -> 上传, 结果经 MQ 回传 video 服务回写
     */
    @Override
    public void onMessage(VideoCoverMessage msg) {
        if (msg == null || msg.getVideoId() == null) {
            log.warn("VideoCoverListener 收到无效消息: {}", msg);
            return;
        }
        String url = generateCoverUrl(msg);
        rocketMQTemplate.syncSend(MqConstants.TOPIC_VIDEO_COVER_RESULT, MessageBuilder
                .withPayload(VideoCoverResultMessage.builder()
                        .videoId(msg.getVideoId())
                        .coverUrl(url)
                        .build())
                .build());
    }

    private String generateCoverUrl(VideoCoverMessage msg) {
        if (msg.getVideoObjectKey() == null || msg.getVideoObjectKey().isBlank()) {
            return defaultCoverObjectKey();
        }
        Path tempPath = null;
        Path taskDir = null;
        try {
            String taskId = java.util.UUID.randomUUID().toString().replace("-", "");
            String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

            Path baseDir = OUTPUT_DIR.resolve(day);
            taskDir = baseDir.resolve(taskId);
            Files.createDirectories(taskDir);

            // 1) 下载视频到本地
            tempPath = DownLoadMP4ToTemp.downloadToFixedDir(
                    storageUtil.signGetUrl(msg.getVideoObjectKey(), 3600 * 2),
                    taskDir, ".mp4");

            // 2) 生成封面
            FFmpegUtil.Options opt = new FFmpegUtil.Options();
            opt.sampleCount = 5;
            opt.jpegQuality = 4;
            opt.frameMaxWidth = 640;
            opt.ffmpegThreads = 1;
            FFmpegUtil.Result result = FFmpegUtil.generateBestCover(
                    tempPath,
                    taskDir,
                    "video-cover-" + msg.getVideoId() + "-" + taskId + ".jpg",
                    opt
            );

            // 3) 上传或回退
            if (result.selectedCoverPath() != null) {
                byte[] bytes = Files.readAllBytes(result.selectedCoverPath());
                String fileName = result.selectedCoverPath().getFileName().toString();
                return storageUtil.uploadForObjectKey(bytes, fileName, msg.getUserId(), PREFIX_STORAGE);
            }
            return defaultCoverObjectKey();
        } catch (Exception e) {
            log.error("生成视频封面失败, 视频id={}", msg.getVideoId(), e);
            return defaultCoverObjectKey();
        } finally {
            // 只删本次任务产生的文件/目录，避免并发误删
            try {
                if (tempPath != null) {
                    Files.deleteIfExists(tempPath);
                }
            } catch (Exception ignore) {
                // 不做处理
            }
            if (taskDir != null) {
                try (java.util.stream.Stream<Path> s = Files.walk(taskDir)) {
                    s.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {Files.deleteIfExists(p); } catch (Exception ignore) {}
                            });
                    deleteIfEmpty(taskDir.getParent());
                } catch (Exception ignore) {
                    // 不做处理
                }
            }
        }
    }

    /** 抽帧失败时的兜底: 上传内置默认封面到固定 objectKey, 失败抛异常交给 MQ 重试 */
    private String defaultCoverObjectKey() {
        String cached = defaultCoverKey;
        if (cached != null) {
            return cached;
        }
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_COVER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少默认封面资源: " + DEFAULT_COVER_RESOURCE);
            }
            storageUtil.uploadWithKey(in.readAllBytes(), DEFAULT_COVER_OBJECT_KEY);
            defaultCoverKey = DEFAULT_COVER_OBJECT_KEY;
            return DEFAULT_COVER_OBJECT_KEY;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取默认封面资源失败: " + DEFAULT_COVER_RESOURCE, e);
        }
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isPresent()) {
                return; // 非空，不删
            }
        }
        Files.deleteIfExists(dir);
    }
}
