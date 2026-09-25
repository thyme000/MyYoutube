package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.common.utils.FFmpegUtil;
import cn.diaojiahong.myyoutube.mq.message.PictureToVideoMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.MergePictureJobRedisPayload;
import cn.diaojiahong.myyoutube.video.domain.vo.MergePictureJobStatusVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MergePictureToVideoListener {

    private static final long STORAGE_SIGN_SECONDS = 3600L * 2;
    private static final String FFMPEG = "ffmpeg";

    private final S3StorageUtil storageUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = MERGE_PICTURES_QUEUE, durable = "true"),
                    exchange = @Exchange(name = MERGE_PICTURES_EXCHANGE, type = ExchangeTypes.TOPIC),
                    key = MERGE_PICTURES_ROUTING_KEY
            ),
            concurrency = "1" // 限制并发数为 1，避免资源竞争
    )
    public void mergePicturesToVideo(PictureToVideoMessage msg) {
        if (msg == null || !StringUtils.hasText(msg.getJobId()) || msg.getUserId() == null
                || msg.getObjectKeys() == null || msg.getObjectKeys().isEmpty()) {
            log.warn("MergePictureToVideoListener 收到无效消息: {}", msg);
            return;
        }
        String jobKey = RedisConstants.MERGE_PICTURE_JOB + msg.getJobId();
        MergePictureJobRedisPayload current = readPayload(jobKey);
        if (current != null && MergePictureJobStatusVO.STATUS_DONE.equals(current.getStatus())) {
            log.info("任务已完成，跳过重复消费 jobId={}", msg.getJobId());
            return;
        }

        Path workRoot = null;
        try {
            int n = msg.getObjectKeys().size();
            double secondsPerImage = 2.0;
            if (n * secondsPerImage < 6.0) {
                secondsPerImage = 6.0 / n;
            }
            int durationSeconds = (int) Math.round(secondsPerImage * n);

            workRoot = Files.createTempDirectory("merge-pic-" + msg.getJobId() + "-");
            Path workDir = workRoot.resolve("work");
            Files.createDirectories(workDir);

            List<Path> imagePaths = new ArrayList<>();
            for (int i = 0; i < msg.getObjectKeys().size(); i++) {
                String key = msg.getObjectKeys().get(i);
                String url = storageUtil.signGetUrl(key, STORAGE_SIGN_SECONDS);
                String ext = suffixFromObjectKey(key);
                Path img = DownLoadMP4ToTemp.downloadToFixedDir(url, workDir, ext);
                imagePaths.add(img);
            }

            Path audioPath = null;
            if (StringUtils.hasText(msg.getAudioObjectKey())) {
                String aurl = storageUtil.signGetUrl(msg.getAudioObjectKey().trim(), STORAGE_SIGN_SECONDS);
                String aext = suffixFromObjectKey(msg.getAudioObjectKey());
                audioPath = DownLoadMP4ToTemp.downloadToFixedDir(aurl, workDir, aext);
            }

            Path output = workDir.resolve("slideshow-out.mp4");
            FFmpegUtil.PictureSlideshowFfmpegUtil.mergeToMp4(
                    imagePaths,
                    secondsPerImage,
                    audioPath,
                    workDir,
                    output,
                    FFMPEG
            );

            byte[] mp4 = Files.readAllBytes(output);
            String objectKey = storageUtil.uploadForObjectKey(
                    mp4,
                    "slideshow-" + msg.getJobId() + ".mp4",
                    msg.getUserId(),
                    "video"
            );

            savePayload(jobKey, MergePictureJobRedisPayload.builder()
                    .userId(msg.getUserId())
                    .status(MergePictureJobStatusVO.STATUS_DONE)
                    .videoObjectKey(objectKey)
                    .secondsPerImage(secondsPerImage)
                    .durationSeconds(durationSeconds)
                    .build());
            log.info("多图合成完成 jobId={} objectKey={} durationSec≈{}", msg.getJobId(), objectKey, durationSeconds);
            deleteMergeSourceObjects(msg, objectKey);
        } catch (Exception e) {
            log.error("多图合成失败 jobId={}", msg.getJobId(), e);
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (err.length() > 500) {
                err = err.substring(0, 500);
            }
            savePayload(jobKey, MergePictureJobRedisPayload.builder()
                    .userId(msg.getUserId())
                    .status(MergePictureJobStatusVO.STATUS_FAIL)
                    .errorMessage(err)
                    .build());
        } finally {
            if (workRoot != null) {
                deleteTreeQuietly(workRoot);
            }
        }
    }

    private MergePictureJobRedisPayload readPayload(String jobKey) {
        try {
            String json = redisTemplate.opsForValue().get(jobKey);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, MergePictureJobRedisPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("读取任务 Redis 失败 key={}", jobKey, e);
            return null;
        }
    }

    private void savePayload(String jobKey, MergePictureJobRedisPayload payload) {
        try {
            redisTemplate.opsForValue().set(
                    jobKey,
                    objectMapper.writeValueAsString(payload),
                    RedisConstants.MERGE_PICTURE_JOB_EXPIRE_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (JsonProcessingException e) {
            log.error("写入任务 Redis 失败 key={}", jobKey, e);
        }
    }

    /**
     * 合成成功后删除对象存储上的源图与源音频；不会去删成片 objectKey。
     */
    private void deleteMergeSourceObjects(PictureToVideoMessage msg, String mergedVideoObjectKey) {
        Set<String> keys = new LinkedHashSet<>();
        for (String k : msg.getObjectKeys()) {
            if (StringUtils.hasText(k)) {
                keys.add(storageUtil.normalizeToObjectKey(k.trim()));
            }
        }
        if (StringUtils.hasText(msg.getAudioObjectKey())) {
            keys.add(storageUtil.normalizeToObjectKey(msg.getAudioObjectKey().trim()));
        }
        if (StringUtils.hasText(mergedVideoObjectKey)) {
            keys.remove(storageUtil.normalizeToObjectKey(mergedVideoObjectKey));
        }
        for (String key : keys) {
            if (StringUtils.hasText(key)) {
                storageUtil.deleteObjectQuietly(key);
            }
        }
    }

    private static String suffixFromObjectKey(String key) {
        if (!StringUtils.hasText(key)) {
            return ".bin";
        }
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot >= key.length() - 1) {
            return ".img";
        }
        String ext = key.substring(dot).toLowerCase(Locale.ROOT);
        if (ext.matches("\\.(jpg|jpeg|png|webp|gif|bmp|mp3|m4a|aac|wav|flac|ogg)")) {
            return ext;
        }
        return ".bin";
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
