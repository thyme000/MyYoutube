package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 转码任务: tika 真身校验 + ffprobe 探测 + 标准化转码(长边720 H.264/AAC faststart)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeTaskExecutor implements MediaTaskExecutor {

    private static final Path WORK_DIR = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "media-transcode");
    private static final int TARGET_LONG_EDGE = 720;

    private final S3StorageUtil storageUtil;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.TRANSCODE;
    }

    @Override
    public String execute(MediaTaskMessage msg) throws Exception {
        long startedAt = System.currentTimeMillis();
        // 先校验文件签名再交给 FFmpeg 直读 URL
        byte[] head = storageUtil.readHeadBytes(msg.getObjectKey(), 64);
        String mime = new Tika().detect(head, msg.getObjectKey());
        if (!mime.startsWith("video/")) {
            throw new IllegalStateException("Magic Number 校验失败, 非视频文件: " + mime);
        }

        String source = storageUtil.signGetUrl(msg.getObjectKey(), 3600 * 2);
        FFmpegUtil.Options opt = new FFmpegUtil.Options();
        opt.commandTimeout = Duration.ofSeconds(60);
        FFmpegUtil.VideoProbe probe = FFmpegUtil.probeVideo(source, opt);

        String taskId = UUID.randomUUID().toString().replace("-", "");
        Path work = WORK_DIR.resolve(LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                .resolve(taskId);
        Files.createDirectories(work);
        try {
            Path out = work.resolve("processed-" + msg.getOwnerId() + ".mp4");
            // 外部进程限制 14 分钟，任务租约由 worker 独立续期
            FFmpegUtil.transcodeStandard(source, out, TARGET_LONG_EDGE, opt, Duration.ofMinutes(14));
            String processedKey = "video-processed/" + msg.getUserId() + "/" + msg.getOwnerId() + "/"
                    + taskId + ".mp4";
            long bytes = Files.size(out);
            storageUtil.uploadWithKey(Files.readAllBytes(out), processedKey);
            log.info("Transcode completed draftId={} taskId={} attempt={} processedObjectKey={} durationMs={} width={} height={} bytes={} elapsedMs={}",
                    msg.getOwnerId(),
                    msg.getTaskId(),
                    msg.getAttempt(),
                    processedKey,
                    probe.durationMs(),
                    probe.width(),
                    probe.height(), bytes, System.currentTimeMillis() - startedAt);
            return JsonUtil.toJson(Map.of(
                    "processedObjectKey", processedKey,
                    "durationMs", probe.durationMs(),
                    "width", probe.width(),
                    "height", probe.height(),
                    "mime", mime));
        } finally {
            try (Stream<Path> s = Files.walk(work)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignore) {
                    }
                });
            } catch (Exception ignore) {
            }
        }
    }
}
