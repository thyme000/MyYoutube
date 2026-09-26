package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.common.utils.FFmpegUtil;
import cn.diaojiahong.myyoutube.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_VIDEO_COVER,
        consumerGroup = "video_cover_group",
        maxReconsumeTimes = 3)
public class VideoCoverListener implements RocketMQListener<VideoCoverMessage> {

    private final S3StorageUtil storageUtil;
    private final IVideoService videoService;
    // 系统临时目录, JVM 已按平台适配, 不再用项目内相对路径
    private static final Path OUTPUT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "video-cover");
    private static final String PREFIX_STORAGE = "video-cover";

    /**
     * 生成视频封面
     */
    @Override
    public void onMessage(VideoCoverMessage msg) {
        if (msg.getVideoId() == null) {
            throw new IllegalArgumentException("视频id不能为空");
        }

        String url;
        Path tempPath = null;
        Path taskDir = null;

        try {
            if (msg.getVideoObjectKey() == null || msg.getVideoObjectKey().isBlank()) {
                url = FFmpegUtil.DEFAULT_FALLBACK_COVER_URL;
            } else {
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
                    url = storageUtil.uploadForObjectKey(bytes, fileName, msg.getUserId(), PREFIX_STORAGE);
                } else {
                    url = (result.selectedCoverUrl() != null && !result.selectedCoverUrl().isBlank())
                            ? result.selectedCoverUrl()
                            : FFmpegUtil.DEFAULT_FALLBACK_COVER_URL;
                }
            }
        } catch (Exception e) {
            url = FFmpegUtil.DEFAULT_FALLBACK_COVER_URL;
            log.error("生成视频封面失败, 视频id={}", msg.getVideoId(), e);
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

        videoService.lambdaUpdate()
                .set(Video::getCoverUrl, storageUtil.normalizeToObjectKey(url))
                .eq(Video::getId, msg.getVideoId())
                .update();
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
