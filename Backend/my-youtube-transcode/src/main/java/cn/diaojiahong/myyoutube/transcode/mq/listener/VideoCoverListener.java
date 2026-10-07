package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverResultMessage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.cover.CoverVisionSelector;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
    private final CoverVisionSelector coverVisionSelector;

    // 系统临时目录, JVM 已按平台适配
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "video-cover");
    private static final String PREFIX_STORAGE = "video-cover";
    private static final String DEFAULT_COVER_RESOURCE = "cover/default-video.png";
    private static final String DEFAULT_COVER_OBJECT_KEY = "video-cover/default-video.png";

    /** 默认图上传成功后缓存 objectKey, 避免重复上传 */
    private volatile String defaultCoverKey;

    /**
     * 生成视频封面: ffmpeg 直读 S3 URL 抽帧评分 -> LLM 选帧 -> 高清重抽 -> 上传, 结果经 MQ 回传 video 服务回写
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
        Path taskDir = null;
        try {
            String taskId = UUID.randomUUID().toString().replace("-", "");
            String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

            taskDir = OUTPUT_DIR.resolve(day).resolve(taskId);
            Files.createDirectories(taskDir);
            // 直读 URL: ffmpeg 按 Range 按需拉取, 不落全量视频; 预签名 2h 足够秒级抽帧
            String source = storageUtil.signGetUrl(msg.getVideoObjectKey(), 3600 * 2);

            FFmpegUtil.Options opt = new FFmpegUtil.Options();
            opt.sampleCount = 8;
            opt.commandTimeout = Duration.ofSeconds(60);

            List<FFmpegUtil.FrameScore> candidates = FFmpegUtil.extractAndScoreCandidates(source, taskDir, opt);
            if (candidates.isEmpty()) {
                return defaultCoverObjectKey();
            }
            FFmpegUtil.FrameScore best = selectBest(candidates, opt);

            // 成品按选定时间点高清重抽; 失败退回候选帧原图
            Path finalCover = taskDir.resolve("video-cover-" + msg.getVideoId() + "-" + taskId + ".jpg");
            if (!FFmpegUtil.extractFrameAt(source, best.second(), finalCover, opt.coverMaxLongEdge, opt)) {
                Files.copy(best.framePath(), finalCover, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return storageUtil.uploadForObjectKey(Files.readAllBytes(finalCover),
                    finalCover.getFileName().toString(), msg.getUserId(), PREFIX_STORAGE);
        } catch (Exception e) {
            log.error("生成视频封面失败, 视频id={}", msg.getVideoId(), e);
            return defaultCoverObjectKey();
        } finally {
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

    /** LLM 决胜, 失败/不可用回落启发式最高分; 预筛通过者为零时全员参与 */
    private FFmpegUtil.FrameScore selectBest(List<FFmpegUtil.FrameScore> candidates, FFmpegUtil.Options opt) {
        List<FFmpegUtil.FrameScore> survivors = candidates.stream()
                .filter(c -> FFmpegUtil.passesPrefilter(c, opt))
                .toList();
        List<FFmpegUtil.FrameScore> pool = survivors.isEmpty() ? candidates : survivors;
        Integer picked = coverVisionSelector.pickBest(pool);
        if (picked != null) {
            log.info("LLM 选帧命中: {}/{}", picked + 1, pool.size());
            return pool.get(picked);
        }
        return pool.stream()
                .max(Comparator.comparingDouble(FFmpegUtil.FrameScore::totalScore))
                .orElseThrow();
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
