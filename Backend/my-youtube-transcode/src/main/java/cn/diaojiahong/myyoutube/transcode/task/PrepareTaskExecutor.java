package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.transcode.domain.enums.PreparationItemStatus;
import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.DigestUtil;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts;
import cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings;
import cn.diaojiahong.myyoutube.common.media.VideoFrameSampling;
import cn.diaojiahong.myyoutube.common.media.PreparedMedia;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaPreparation;
import cn.diaojiahong.myyoutube.transcode.domain.po.PreparationItem;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PrepareTaskExecutor implements MediaTaskExecutor {
    private final PreparationRegistry registry;
    private final PreparationStorage storage;
    private final PreparedImageWriter images;
    private final PreparedVideoReader videos;
    private final PreparedVideoWriter playbackWriter;
    private final MediaPreparationProperties properties;
    private final S3StorageUtil urls;
    private final FrameProcessingSettings frameSettings;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    // 播放产物额外记录内容摘要；目录原文用于中断后的补传。
    private record Output(long bytes, long timeMs, int width, int height, String fingerprint, String playlist, long bandwidth, PlaybackMetadata playback) {
    }

    @Override
    public MediaTaskType type() {
        return MediaTaskType.PREPARE;
    }

    private record PreparedContent(
            List<Item> items, long duration, boolean hasAudio,
            int videoFrameCount, VideoFrameSampling.Plan videoSampling,
            int preparedFrames, int reusedFrames, int preparedImages, int reusedImages) {
    }

    @Override
    public String execute(MediaTaskMessage task) throws Exception {
        MediaPreparation job = registry.get(task.getOwnerId());
        Request request = JsonUtil.fromJson(job.getRequestJson(), Request.class);
        FrameProcessingSettings.Snapshot settings = validateInput(task, job, request);

        Path work = Files.createTempDirectory("media-preparation-");
        long startedAt = System.currentTimeMillis();
        try {
            PreparedContent prepared = request.media().video() != null
                    ? prepareVideo(task, request, settings, work)
                    : prepareDynamic(task, request.media().dynamic(), work);

            Manifest manifest = new Manifest(
                    request.preparationId(), request.draftId(), request.mediaVersion(),
                    request.userId(), job.getParameterVersion(), prepared.items(),
                    prepared.duration(), prepared.hasAudio(), prepared.videoFrameCount(), prepared.videoSampling());
            PreparedMedia.freeze(request, manifest);
            recordCompleted(request, prepared, startedAt);
            return JsonUtil.toJson(manifest);
        } catch (VideoFrameSampling.Rejected error) {
            metrics.counter(MetricNames.MEDIA_VIDEO_SAMPLING_ATTEMPTS, "outcome", error.reason()).increment();
            log.warn(
                    "Preparation video sampling rejected preparationId={} reason={} detail={}",
                    request.preparationId(), error.reason(), error.getMessage());
            throw error;
        } finally {
            cleanupDirectory(work);
        }
    }

    private FrameProcessingSettings.Snapshot validateInput(MediaTaskMessage task, MediaPreparation job, Request request) {
        if (!Objects.equals(request.userId(), task.getUserId()) || !request.media().equals(task.getInput().media())) {
            throw new IllegalStateException("Preparation task input mismatch");
        }

        FrameProcessingSettings.Snapshot settings = frameSettings.snapshot();
        if (!job.getParameterVersion().equals(registry.parameterVersion(settings))) {
            throw new IllegalStateException(PreparationContracts.PARAMETERS_CHANGED_MESSAGE);
        }
        return settings;
    }

    private PreparedContent prepareVideo(
            MediaTaskMessage task, Request request, FrameProcessingSettings.Snapshot settings, Path work) throws Exception {
        List<Item> result = new ArrayList<>();
        PreparationStorage.Source original = original(
                task, ItemRole.VIDEO, 0, request.media().video().objectKey(), properties.getVideoSourceBytes());
        String source = urls.signGetUrl(original.key(), 7200);
        PreparedVideoReader.Probe probe = videos.probe(source, urls.readHeadBytes(original.key(), 65536));
        boolean rotated = Math.floorMod(probe.rotation(), 180) != 0;
        int width = rotated ? probe.height() : probe.width();
        int height = rotated ? probe.width() : probe.height();
        result.add(new Item(ItemRole.VIDEO, 0, original.key(), original.key(), 0, width, height, 0, null));

        VideoFrameSampling.Plan videoSampling = settings.plan(probe.videoDurationMs(), probe.availableFrames());
        TreeSet<Integer> accepted = new TreeSet<>();
        // 复用与新建分开计数，便于观察重试时是否避免了重复抽帧。
        AtomicInteger reusedFrames = new AtomicInteger();
        AtomicInteger preparedFrames = new AtomicInteger();
        videos.frames(source, probe, work, videoSampling, index -> {
            boolean skip = reusable(registry.find(task.getOwnerId(), ItemRole.FRAME, index));
            if (skip) {
                reusedFrames.incrementAndGet();
                accepted.add(index);
            }
            return skip;
        }, frame -> {
            PreparationItem row = registry.reserve(task, ItemRole.FRAME, frame.index(), null);
            Output output = new Output(
                    frame.image().bytes(), frame.timeMs(), frame.image().width(), frame.image().height(),
                    null, null, 0, null);
            derivative(task, row, frame.path(), output, "image/jpeg");
            preparedFrames.incrementAndGet();
            accepted.add(frame.index());
        });
        videoSampling.validate(List.copyOf(accepted));
        for (int index : accepted) {
            result.add(item(task, ItemRole.FRAME, index, original.key()));
        }

        int videoFrameCount = accepted.size();
        for (PlaybackQuality quality : PlaybackQuality.forVideo(width, height)) {
            preparePlayback(task, original.key(), work.resolve("playback-" + quality.code()), quality, probe.durationMs(), result);
        }
        log.info("Preparation video sampling preparationId={} target={} minimum={} actual={} missing={} toleranceUsed={}",
                request.preparationId(), videoSampling.targetCount(), videoSampling.minimumCount(), videoFrameCount,
                videoSampling.missing(List.copyOf(accepted)), videoFrameCount < videoSampling.targetCount());

        return new PreparedContent(
                result, probe.durationMs(), probe.hasAudio(), videoFrameCount, videoSampling,
                preparedFrames.get(), reusedFrames.get(), 0, 0);
    }

    private PreparedContent prepareDynamic(MediaTaskMessage task, DynamicMedia media, Path work) throws Exception {
        List<Item> result = new ArrayList<>();
        long duration = media.exportDurationMs();
        boolean hasAudio = media.audio() != null;
        AtomicInteger reusedImages = new AtomicInteger();
        AtomicInteger preparedImages = new AtomicInteger();

        for (int i = 0; i < media.items().size(); i++) {
            DynamicMedia.Image input = media.items().get(i);
            if (registry.find(task.getOwnerId(), ItemRole.IMAGE, i) == null) {
                shareImage(task, i, input.imageObjectKey());
            }
            PreparationStorage.Source original = original(
                    task, ItemRole.IMAGE, i, input.imageObjectKey(), properties.getSourceBytes());
            result.add(new Item(ItemRole.IMAGE, i, original.key(), original.key(), input.startMs(), 0, 0, 0, null));

            PreparationItem row = registry.find(task.getOwnerId(), ItemRole.OCR_IMAGE, i);
            if (!reusable(row)) {
                row = registry.reserve(task, ItemRole.OCR_IMAGE, i, null);
                Path source = work.resolve("source");
                Path output = work.resolve("image.jpg");
                try {
                    storage.download(original, source, properties.getSourceBytes());
                    PreparedImageWriter.PreparedImageResult image = images.write(source, output);
                    Output metadata = new Output(
                            image.bytes(), input.startMs(), image.width(), image.height(), null, null, 0, null);
                    derivative(task, row, output, metadata, "image/jpeg");
                } finally {
                    cleanup(source);
                    cleanup(output);
                }
                preparedImages.incrementAndGet();
            } else {
                reusedImages.incrementAndGet();
            }

            Item prepared = item(task, ItemRole.OCR_IMAGE, i, original.key());
            result.add(new Item(
                    prepared.role(), i, original.key(), prepared.objectKey(),
                    input.startMs(), prepared.width(), prepared.height(), 0, null));
        }

        if (hasAudio) {
            PreparationStorage.Source audio = original(
                    task, ItemRole.AUDIO, 0, media.audio().objectKey(), properties.getVideoSourceBytes());
            result.add(new Item(ItemRole.AUDIO, 0, audio.key(), audio.key(), 0, 0, 0, 0, null));
        }
        return new PreparedContent(result, duration, hasAudio, 0, null, 0, 0, preparedImages.get(), reusedImages.get());
    }

    private void recordCompleted(Request request, PreparedContent prepared, long startedAt) {
        VideoFrameSampling.Plan videoSampling = prepared.videoSampling();
        if (videoSampling != null) {
            metrics.counter(MetricNames.MEDIA_VIDEO_SAMPLING_ATTEMPTS, "outcome",
                    prepared.videoFrameCount() == videoSampling.targetCount() ? "FULL" : "TOLERATED").increment();
        }
        log.info("Preparation completed preparationId={} draftId={} mediaVersion={} media={} items={} frames={} hasAudio={} durationMs={}"
                        + " preparedFrames={} reusedFrames={} preparedImages={} reusedImages={} elapsedMs={}",
                request.preparationId(), request.draftId(), request.mediaVersion(), request.media().type(),
                prepared.items().size(), prepared.videoFrameCount(), prepared.hasAudio(), prepared.duration(),
                prepared.preparedFrames(), prepared.reusedFrames(), prepared.preparedImages(), prepared.reusedImages(),
                System.currentTimeMillis() - startedAt);
    }

    private static void cleanupDirectory(Path work) {
        try (Stream<Path> paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                cleanup(path);
            }
        } catch (Exception e) {
            log.warn("Preparation temporary directory cleanup failed", e);
        }
    }

    private static void cleanup(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("Preparation temporary file cleanup failed", e);
        }
    }

    private void shareImage(MediaTaskMessage task, int index, String key) {
        PreparationStorage.Source source = storage.inspect(key);
        if (source.bytes() > properties.getSourceBytes()) throw new IllegalArgumentException("素材文件大小超限");
        for (PreparationItem original : registry.reusableImages(task, source)) {
            if (!source.equals(JsonUtil.fromJson(original.getSourceJson(), PreparationStorage.Source.class))) continue;
            PreparationItem derived = registry.find(original.getPreparationId(), ItemRole.OCR_IMAGE, original.getItemIndex());
            if (reusable(original) && reusable(derived) && registry.shareImages(task, index, original, derived)) return;
        }
    }

    private PreparationStorage.Source original(
            MediaTaskMessage task,
            ItemRole role,
            int index,
            String key,
            long maxBytes) {
        PreparationItem row = registry.find(task.getOwnerId(), role, index);
        if (row == null) {
            PreparationStorage.Source source = storage.inspect(key);
            if (source.bytes() > maxBytes) throw new IllegalArgumentException("素材文件大小超限");
            row = registry.reserve(task, role, index, JsonUtil.toJson(source));
        }
        PreparationStorage.Source source = JsonUtil.fromJson(row.getSourceJson(), PreparationStorage.Source.class);
        if (!source.key().equals(key)
                || source.bytes() > maxBytes) throw new IllegalStateException("Original input mismatch");
        if (!Objects.equals(row.getObjectOwnerId(), task.getOwnerId())) {
            if (!reusable(row)) throw new IllegalStateException("Shared original is missing");
            return storage.inspect(row.getObjectKey());
        }
        row = registry.planned(task, row, JsonUtil.toJson(new Output(source.bytes(), 0, 0, 0, null, null, 0, null)));
        PreparationStorage.Source frozen = storage.inspectIfPresent(row.getObjectKey());
        if (frozen == null) frozen = storage.freeze(source, row.getObjectKey());
        if (frozen.bytes() != source.bytes()) throw new IllegalStateException("Frozen original size mismatch");
        registry.confirmed(task, row);
        return frozen;
    }

    private PreparationItem derivative(MediaTaskMessage task, PreparationItem row, Path file, Output output, String contentType) {
        row = registry.planned(task, row, JsonUtil.toJson(output));
        PrepareTaskExecutor.Output expected = JsonUtil.fromJson(row.getResultJson(), Output.class);
        if (output.fingerprint() != null && !expected.equals(output))
            throw new IllegalStateException("Playback output differs from registered pair");
        PreparationStorage.Source existing = storage.inspectIfPresent(row.getObjectKey());
        if (existing == null) {
            if (!expected.equals(output)) throw new IllegalStateException("Prepared output differs from registered metadata");
            storage.upload(file, row.getObjectKey(), contentType);
            existing = storage.inspect(row.getObjectKey());
        }
        if (existing.bytes() != expected.bytes()) throw new IllegalStateException("Prepared output size mismatch");
        registry.confirmed(task, row);
        return row.setStatus(PreparationItemStatus.READY);
    }

    private void preparePlayback(MediaTaskMessage task, String original, Path work, PlaybackQuality quality, long durationMs, List<Item> result)
            throws Exception {
        PreparationItem media = registry.reserve(task, ItemRole.HLS_MEDIA, quality);
        PreparationItem playlist = registry.reserve(task, ItemRole.HLS_PLAYLIST, quality);
        boolean mediaReusable = reusable(media);
        boolean playlistReusable = reusable(playlist);
        if (!mediaReusable || !playlistReusable) {
            Files.createDirectories(work);
            Output plannedPlaylist = playlist.getResultJson() == null ? null
                    : JsonUtil.fromJson(playlist.getResultJson(), Output.class);
            if (mediaReusable && plannedPlaylist != null) {
                // 目录上传中断时使用已登记的原文，确保范围始终对应已上传的视频。
                Path file = work.resolve(HlsPlaylist.PLAYLIST_FILENAME);
                Files.writeString(file, plannedPlaylist.playlist());
                playlist = derivative(task, playlist, file, plannedPlaylist, HlsPlaylist.CONTENT_TYPE);
            } else {
                // 各档开始时重新签名，覆盖本档预算并为网络建立连接留出余量。
                long sourceTtl = Math.max(7200, properties.getPlayback().totalTimeout(durationMs).toSeconds() + 300);
                String source = urls.signGetUrl(original, sourceTtl);
                PreparedVideoWriter.Result output = playbackWriter.write(source, work, quality, durationMs);
                String filename = media.getObjectKey().substring(media.getObjectKey().lastIndexOf('/') + 1);
                String text = Files.readString(output.playlist()).replace(HlsPlaylist.MEDIA_FILENAME, filename);
                Files.writeString(output.playlist(), text);
                Output mediaMetadata = new Output(Files.size(output.media()), 0, output.width(), output.height(),
                        fingerprint(output.media()), null, output.bandwidth(), output.playback());
                Output playlistMetadata = new Output(Files.size(output.playlist()), 0, 0, 0,
                        fingerprint(output.playlist()), text, 0, null);
                // 视频上传前先登记目录原文，进程中断后可直接补传目录而不重新编码。
                playlist = registry.planned(task, playlist, JsonUtil.toJson(playlistMetadata));
                media = derivative(task, media, output.media(), mediaMetadata, HlsPlaylist.MEDIA_CONTENT_TYPE);
                playlist = derivative(task, playlist, output.playlist(), playlistMetadata, HlsPlaylist.CONTENT_TYPE);
            }
        }
        result.add(item(media, original));
        result.add(item(playlist, original));
    }

    private static String fingerprint(Path file) throws Exception {
        MessageDigest digest = DigestUtil.digester(DigestAlgorithm.SHA256).getDigest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean reusable(PreparationItem row) {
        if (row == null || !PreparationItemStatus.READY.equals(row.getStatus())) return false;
        PrepareTaskExecutor.Output expected = JsonUtil.fromJson(row.getResultJson(), Output.class);
        PreparationStorage.Source actual = storage.inspectIfPresent(row.getObjectKey());
        if (actual == null) return false;
        if (actual.bytes() != expected.bytes()) throw new IllegalStateException("Prepared object changed");
        return true;
    }

    private Item item(MediaTaskMessage task, ItemRole role, int index, String original) {
        PreparationItem row = registry.find(task.getOwnerId(), role, index);
        if (!reusable(row)) throw new IllegalStateException("Incomplete preparation manifest");
        return item(row, original);
    }

    private Item item(PreparationItem row, String original) {
        PrepareTaskExecutor.Output output = JsonUtil.fromJson(row.getResultJson(), Output.class);
        return new Item(row.getRole(), row.getItemIndex(), original, row.getObjectKey(), output.timeMs(), output.width(), output.height(),
                output.bandwidth(), output.playback());
    }
}
