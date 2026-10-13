package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.PreparationItem;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
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
    private final MediaPreparationProperties properties;
    private final S3StorageUtil urls;

    private record Output(long bytes, long timeMs, int width, int height) {}

    @Override public MediaTaskType type() { return MediaTaskType.PREPARE; }

    @Override public String execute(MediaTaskMessage task) throws Exception {
        var job = registry.get(task.getOwnerId());
        var request = JsonUtil.fromJson(job.getRequestJson(), Request.class);
        if (!Objects.equals(request.userId(), task.getUserId()) || !request.media().equals(task.getInput().media()))
            throw new IllegalStateException("Preparation task input mismatch");
        if (!job.getParameterVersion().equals(registry.parameterVersion()))
            throw new IllegalStateException(PreparationContracts.PARAMETERS_CHANGED_MESSAGE);
        Path work = Files.createTempDirectory("media-preparation-");
        try {
            var result = new ArrayList<Item>();
            long duration;
            boolean hasAudio;
            if (request.media().video() != null) {
                var original = original(task, "VIDEO", 0, request.media().video().objectKey(), properties.getVideoSourceBytes());
                result.add(new Item("VIDEO", 0, original.key(), original.key(), 0, 0, 0));
                String source = urls.signGetUrl(original.key(), 7200);
                var probe = videos.probe(source, urls.readHeadBytes(original.key(), 65536));
                duration = probe.durationMs(); hasAudio = probe.hasAudio();
                videos.frames(source, probe, work, index -> reusable(registry.find(task.getOwnerId(), "FRAME", index)), frame -> {
                    var row = registry.reserve(task, "FRAME", frame.index(), null);
                    derivative(task, row, frame.path(), new Output(frame.image().bytes(), frame.timeMs(),
                            frame.image().width(), frame.image().height()));
                });
                for (int i = 0; i < 8; i++) result.add(item(task, "FRAME", i, original.key()));
            } else {
                var media = request.media().dynamic();
                duration = media.exportDurationMs(); hasAudio = media.audio() != null;
                for (int i = 0; i < media.items().size(); i++) {
                    var input = media.items().get(i);
                    if (registry.find(task.getOwnerId(), "IMAGE", i) == null)
                        shareImage(task, i, input.imageObjectKey());
                    var original = original(task, "IMAGE", i, input.imageObjectKey(), properties.getSourceBytes());
                    result.add(new Item("IMAGE", i, original.key(), original.key(), input.startMs(), 0, 0));
                    var row = registry.find(task.getOwnerId(), "OCR_IMAGE", i);
                    if (!reusable(row)) {
                        row = registry.reserve(task, "OCR_IMAGE", i, null);
                        Path source = work.resolve("source"), output = work.resolve("image.jpg");
                        try {
                            storage.download(original, source, properties.getSourceBytes());
                            var image = images.write(source, output);
                            derivative(task, row, output, new Output(image.bytes(), input.startMs(), image.width(), image.height()));
                        } finally { cleanup(source); cleanup(output); }
                    }
                    var prepared = item(task, "OCR_IMAGE", i, original.key());
                    result.add(new Item(prepared.role(), i, original.key(), prepared.objectKey(), input.startMs(), prepared.width(), prepared.height()));
                }
                if (hasAudio) {
                    var audio = original(task, "AUDIO", 0, media.audio().objectKey(), properties.getVideoSourceBytes());
                    result.add(new Item("AUDIO", 0, audio.key(), audio.key(), 0, 0, 0));
                }
            }
            return JsonUtil.toJson(new Manifest(request.preparationId(), request.draftId(), request.mediaVersion(),
                    request.userId(), job.getParameterVersion(), result, duration, hasAudio));
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    cleanup(path);
                }
            } catch (Exception e) { log.warn("Preparation temporary directory cleanup failed", e); }
        }
    }

    private static void cleanup(Path path) {
        try { Files.deleteIfExists(path); }
        catch (Exception e) { log.warn("Preparation temporary file cleanup failed", e); }
    }

    private void shareImage(MediaTaskMessage task, int index, String key) {
        var source = storage.inspect(key);
        if (source.bytes() > properties.getSourceBytes()) throw new IllegalArgumentException("素材文件大小超限");
        for (var original : registry.reusableImages(task, source)) {
            if (!source.equals(JsonUtil.fromJson(original.getSourceJson(), PreparationStorage.Source.class))) continue;
            var derived = registry.find(original.getPreparationId(), "OCR_IMAGE", original.getItemIndex());
            if (reusable(original) && reusable(derived) && registry.shareImages(task, index, original, derived)) return;
        }
    }

    private PreparationStorage.Source original(MediaTaskMessage task, String role, int index, String key, long maxBytes) {
        var row = registry.find(task.getOwnerId(), role, index);
        if (row == null) {
            var source = storage.inspect(key);
            if (source.bytes() > maxBytes) throw new IllegalArgumentException("素材文件大小超限");
            row = registry.reserve(task, role, index, JsonUtil.toJson(source));
        }
        var source = JsonUtil.fromJson(row.getSourceJson(), PreparationStorage.Source.class);
        if (!source.key().equals(key) || source.bytes() > maxBytes) throw new IllegalStateException("Original input mismatch");
        if (!Objects.equals(row.getObjectOwnerId(), task.getOwnerId())) {
            if (!reusable(row)) throw new IllegalStateException("Shared original is missing");
            return storage.inspect(row.getObjectKey());
        }
        row = registry.planned(task, row, JsonUtil.toJson(new Output(source.bytes(), 0, 0, 0)));
        var frozen = storage.inspectIfPresent(row.getObjectKey());
        if (frozen == null) frozen = storage.freeze(source, row.getObjectKey());
        if (frozen.bytes() != source.bytes()) throw new IllegalStateException("Frozen original size mismatch");
        registry.confirmed(task, row);
        return frozen;
    }

    private void derivative(MediaTaskMessage task, PreparationItem row, Path file, Output output) {
        row = registry.planned(task, row, JsonUtil.toJson(output));
        var expected = JsonUtil.fromJson(row.getResultJson(), Output.class);
        var existing = storage.inspectIfPresent(row.getObjectKey());
        if (existing == null) {
            if (!expected.equals(output)) throw new IllegalStateException("Prepared output differs from registered metadata");
            storage.upload(file, row.getObjectKey());
            existing = storage.inspect(row.getObjectKey());
        }
        if (existing.bytes() != expected.bytes()) throw new IllegalStateException("Prepared output size mismatch");
        registry.confirmed(task, row);
    }

    private boolean reusable(PreparationItem row) {
        if (row == null || !"READY".equals(row.getStatus())) return false;
        var expected = JsonUtil.fromJson(row.getResultJson(), Output.class);
        var actual = storage.inspectIfPresent(row.getObjectKey());
        if (actual == null) return false;
        if (actual.bytes() != expected.bytes()) throw new IllegalStateException("Prepared object changed");
        return true;
    }

    private Item item(MediaTaskMessage task, String role, int index, String original) {
        var row = registry.find(task.getOwnerId(), role, index);
        if (!reusable(row)) throw new IllegalStateException("Incomplete preparation manifest");
        var output = JsonUtil.fromJson(row.getResultJson(), Output.class);
        return new Item(role, index, original, row.getObjectKey(), output.timeMs(), output.width(), output.height());
    }
}
