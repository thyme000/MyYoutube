package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.OcrPort;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OcrTaskExecutor implements MediaTaskExecutor {
    private final OcrPort ocr;
    private final cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard guard;
    private final S3StorageUtil storage;
    private final ContentArtifactStore artifacts;
    private final PipelineProperties properties;
    @Override public MediaTaskType type() { return MediaTaskType.OCR; }
    @Override public String execute(MediaTaskMessage msg) throws Exception {
        if (msg.getInput().media().preparation() != null) return recognizePrepared(msg);
        if (msg.getInput().media().dynamic() != null) return recognizeImages(msg);
        Path work = Files.createTempDirectory("myyoutube-ocr-");
        try {
            var options = new FFmpegUtil.Options();
            options.sampleCount = 8; options.sampleStartRatio = 0.01; options.sampleEndRatio = 0.99;
            String source = storage.signGetUrl(msg.getObjectKey(), 3600);
            long durationMs = FFmpegUtil.probeVideo(source, options).durationMs();
            var frames = FFmpegUtil.extractAndScoreCandidates(source, work, options)
                    .stream().sorted(Comparator.comparingDouble(FFmpegUtil.FrameScore::second)).toList();
            if (frames.size() != 8) throw new IllegalStateException("OCR 采样帧不完整: " + frames.size());
            List<String> keys = new ArrayList<>();
            for (var frame : frames) keys.add(storage.uploadForObjectKey(Files.readAllBytes(frame.framePath()),
                    "frame.jpg", msg.getUserId(), "content-ocr"));
            var recognized = recognize(keys, msg);
            List<ContentArtifacts.Frame> results = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                var item = recognized.get(i);
                results.add(new ContentArtifacts.Frame(i, Math.round(frames.get(i).second() * 1000), keys.get(i), item.text()));
            }
            return JsonUtil.toJson(Map.of("durationMs", durationMs, "artifactKey", artifacts.write(msg.getPipelineId(), "OCR", msg.getAttempt(), new ContentArtifacts.Ocr(results))));
        } finally {
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private String recognizePrepared(MediaTaskMessage msg) {
        var media = msg.getInput().media();
        PreparedMedia.validate(media, msg.getInput().coverObjectKey());
        String role = media.video() != null ? "FRAME" : "OCR_IMAGE";
        var inputs = media.preparation().items().stream().filter(item -> role.equals(item.role()))
                .sorted(Comparator.comparingInt(cn.diaojiahong.myyoutube.common.media.PreparationContracts.Item::index)).toList();
        var recognized = recognize(inputs.stream().map(i -> i.objectKey()).toList(), msg);
        var frames = new ArrayList<ContentArtifacts.Frame>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            String original = media.video() != null ? input.objectKey() : input.originalObjectKey();
            frames.add(new ContentArtifacts.Frame(input.index(), input.timeMs(), original, recognized.get(i).text(), input.objectKey()));
        }
        return JsonUtil.toJson(Map.of("durationMs", media.durationMs(), "artifactKey",
                artifacts.write(msg.getPipelineId(), "OCR", msg.getAttempt(), new ContentArtifacts.Ocr(frames))));
    }

    private String recognizeImages(MediaTaskMessage msg) {
        var media = msg.getInput().media().dynamic();
        var recognized = recognize(media.items().stream().map(i -> i.imageObjectKey()).toList(), msg);
        var frames = new ArrayList<ContentArtifacts.Frame>();
        for (int i = 0; i < media.items().size(); i++) {
            var result = recognized.get(i);
            var image = media.items().get(i);
            frames.add(new ContentArtifacts.Frame(i, image.startMs(), image.imageObjectKey(), result.text()));
        }
        return JsonUtil.toJson(Map.of("durationMs", media.exportDurationMs(), "artifactKey",
                artifacts.write(msg.getPipelineId(), "OCR", msg.getAttempt(), new ContentArtifacts.Ocr(frames))));
    }

    private List<OcrPort.Item> recognize(List<String> keys, MediaTaskMessage msg) {
        var results = new ArrayList<OcrPort.Item>();
        for (int start = 0; start < keys.size(); start += properties.getOcrBatchSize()) {
            int end = Math.min(keys.size(), start + properties.getOcrBatchSize());
            log.info("OCR batch pipelineId={} taskId={} attempt={} images={}..{} total={}",
                    msg.getPipelineId(), msg.getTaskId(), msg.getAttempt(), start, end - 1, keys.size());
            var urls = keys.subList(start, end).stream().map(k -> storage.signGetUrl(k, 600)).toList();
            var response = guard.call("OCR", () -> ocr.recognize(new OcrPort.Request(urls)));
            if (response == null || response.results() == null || response.results().size() != urls.size())
                throw new IllegalStateException("OCR 返回不完整: images=" + start + ".." + (end - 1));
            for (int i = 0; i < urls.size(); i++) {
                var item = response.results().get(i);
                if (item == null || item.error() != null || item.text() == null || !urls.get(i).equals(item.url()))
                    throw new IllegalStateException("图片 OCR 失败: index=" + (start + i)
                            + ", error=" + (item == null ? "missing result" : item.error()));
                results.add(item);
            }
        }
        return results;
    }
}
