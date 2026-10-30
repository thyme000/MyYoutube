package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioPrepareTaskExecutor implements MediaTaskExecutor {
    private final PreparationStorage storage;
    private final PreparedAudioReader audio;
    private final PreparedImageWriter images;
    private final AudioPreparationProperties properties;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.AUDIO_PREPARE;
    }

    @Override
    public String execute(MediaTaskMessage task) throws Exception {
        AudioPreparationContracts.Request request = Objects.requireNonNull(task.getAudioInput(), "Missing audio input");
        request.validate();
        if (!request.audioId().equals(task.getOwnerId()) || !request.userId().equals(task.getUserId()))
            throw new IllegalArgumentException("音频任务身份不匹配");
        Path directory = Files.createTempDirectory("audio-preparation-");
        try {
            PreparationStorage.Source frozen = freeze(
                    request.objectKey(),
                    request.prefix() + "source",
                    properties.getSourceBytes());
            Path local = directory.resolve("source");
            storage.download(frozen, local, properties.getSourceBytes());
            PreparedAudioReader.Probe probe = audio.read(local);
            String coverKey = null;
            int width = 0, height = 0;
            if (request.coverObjectKey() != null) {
                PreparationStorage.Source cover = freeze(
                        request.coverObjectKey(),
                        request.prefix() + "cover-source",
                        properties.getCoverBytes());
                Path source = directory.resolve("cover-source"), output = directory.resolve("cover.jpg");
                storage.download(cover, source, properties.getCoverBytes());
                PreparedImageWriter.PreparedImageResult checked = images.write(source, output);
                coverKey = request.prefix() + "cover.jpg";
                if (storage.inspectIfPresent(coverKey) == null) {
                    try {
                        storage.upload(output, coverKey);
                    } catch (S3Exception e) {
                        if (e.statusCode() != 412) throw e;
                        storage.inspect(coverKey);
                    }
                }
                width = checked.width();
                height = checked.height();
            }
            var result = new AudioPreparationContracts.AudioPreparationResult(
                    request.audioId(),
                    request.userId(),
                    frozen.key(),
                    coverKey,
                    probe.durationMs(), frozen.bytes(), probe.mime(), width, height);
            result.validate(request);
            return JsonUtil.toJson(result);
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) {
                log.warn("Audio preparation temporary cleanup failed audioId={}", request.audioId(), e);
            }
        }
    }

    private PreparationStorage.Source freeze(String key, String destination, long budget) {
        PreparationStorage.Source existing = storage.inspectIfPresent(destination);
        if (existing != null) {
            if (existing.bytes() > budget) throw new InvalidMediaInputException(InvalidMediaInputException.Reason.MEDIA_TOO_LARGE);
            return existing;
        }
        PreparationStorage.Source source = storage.inspect(key);
        if (source.bytes() > budget) throw new InvalidMediaInputException(InvalidMediaInputException.Reason.MEDIA_TOO_LARGE);
        try {
            return storage.freeze(source, destination);
        } catch (S3Exception e) {
            if (e.statusCode() != 412) throw e;
            return storage.inspect(destination);
        }
    }
}
