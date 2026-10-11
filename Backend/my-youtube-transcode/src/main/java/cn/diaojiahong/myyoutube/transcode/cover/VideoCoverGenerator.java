package cn.diaojiahong.myyoutube.transcode.cover;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 视频封面生成: ffmpeg 直读 S3 URL 抽帧评分 -> LLM 选帧 -> 高清重抽 -> 上传
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoCoverGenerator {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "my-youtube", "video-cover");
    private static final String PREFIX_STORAGE = "video-cover";
    private final S3StorageUtil storageUtil;
    private final CoverVisionSelector coverVisionSelector;

    /**
     * 生成真实封面并上传；技术失败交给任务调度器重试
     */
    public String generate(Long refId, Long userId, String videoObjectKey) {
        if (videoObjectKey == null || videoObjectKey.isBlank()) {
            throw new IllegalStateException("封面输入或抽帧失败");
        }
        Path taskDir = null;
        try {
            String taskId = UUID.randomUUID().toString().replace("-", "");
            String day = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

            taskDir = OUTPUT_DIR.resolve(day).resolve(taskId);
            Files.createDirectories(taskDir);
            // 直读 URL: ffmpeg 按 Range 按需拉取, 不落全量视频; 预签名 2h 足够秒级抽帧
            String source = storageUtil.signGetUrl(videoObjectKey, 3600 * 2);

            FFmpegUtil.Options opt = new FFmpegUtil.Options();
            opt.sampleCount = 8;
            opt.commandTimeout = Duration.ofSeconds(60);

            List<FFmpegUtil.FrameScore> candidates = FFmpegUtil.extractAndScoreCandidates(source, taskDir, opt);
            if (candidates.isEmpty()) {
                throw new IllegalStateException("封面输入或抽帧失败");
            }
            FFmpegUtil.FrameScore best = selectBest(candidates, opt);

            // 成品按选定时间点高清重抽; 失败退回候选帧原图
            Path finalCover = taskDir.resolve("video-cover-" + refId + "-" + taskId + ".jpg");
            if (!FFmpegUtil.extractFrameAt(source, best.second(), finalCover, opt.coverMaxLongEdge, opt)) {
                Files.copy(best.framePath(), finalCover, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return storageUtil.uploadForObjectKey(Files.readAllBytes(finalCover),
                    finalCover.getFileName().toString(), userId, PREFIX_STORAGE);
        } catch (Exception e) {
            throw new IllegalStateException("生成封面失败 videoId=" + refId, e);
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

    /** 关闭视觉模型时取质量最高分；开启后的调用失败交由任务重试 */
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
