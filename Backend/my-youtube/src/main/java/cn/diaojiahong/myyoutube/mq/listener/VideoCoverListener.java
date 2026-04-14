package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.common.utils.FFmpegUtil;
import cn.diaojiahong.myyoutube.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoCoverListener {

    private final AliOssUtil aliOssUtil;
    private final IVideoService videoService;
    private static final String OUTPUT_DIR = "src\\main\\resources\\temp";
    private static final String PREFIX_OSS = "video-cover";

    /**
     * 生成视频封面
     * @param msg
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = VIDEO_COVER_QUEUE, durable = "true"),
            exchange = @Exchange(name = VIDEO_COVER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = VIDEO_COVER_ROUTING_KEY
    ))
    public void handleCoverGenerate(VideoCoverMessage msg) {
        if (msg.getVideoId() == null) {
            throw new IllegalArgumentException("视频id不能为空");
        }

        String url;
        Path tempPath = null;
        Path taskDir = null;

        try {
            if (msg.getVideoOssObjectKey() == null || msg.getVideoOssObjectKey().isBlank()) {
                url = FFmpegUtil.DEFAULT_FALLBACK_COVER_URL;
            } else {
                String taskId = java.util.UUID.randomUUID().toString().replace("-", "");
                String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

                Path baseDir = Path.of(OUTPUT_DIR).resolve(day);
                taskDir = baseDir.resolve(taskId);
                Files.createDirectories(taskDir);

                // 1) 下载视频到本地
                tempPath = DownLoadMP4ToTemp.downloadToFixedDir(
                        aliOssUtil.signGetUrl(msg.getVideoOssObjectKey(), 3600 * 2),
                        taskDir, ".mp4");

                // 2) 生成封面
                FFmpegUtil.Options opt = new FFmpegUtil.Options();
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
                    url = aliOssUtil.uploadForObjectKey(bytes, fileName, msg.getUserId(), PREFIX_OSS);
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
                .set(Video::getCoverUrl, aliOssUtil.normalizeToObjectKey(url))
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
