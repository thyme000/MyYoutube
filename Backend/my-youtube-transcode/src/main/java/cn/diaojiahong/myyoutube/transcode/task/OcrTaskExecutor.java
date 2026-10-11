package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.ContentArtifactStore;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.OcrPort;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OcrTaskExecutor implements MediaTaskExecutor {
    private final OcrPort ocr;
    private final cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard guard;
    private final S3StorageUtil storage;
    private final ContentArtifactStore artifacts;
    @Override public MediaTaskType type() { return MediaTaskType.OCR; }
    @Override public String execute(MediaTaskMessage msg) throws Exception {
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
            var response = guard.call("OCR", () -> ocr.recognize(new OcrPort.Request(keys.stream().map(k -> storage.signGetUrl(k, 600)).toList())));
            if (response == null || response.results() == null || response.results().size() != frames.size())
                throw new IllegalStateException("OCR 返回不完整");
            List<ContentArtifacts.Frame> results = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                var item = response.results().get(i);
                if (item.error() != null || item.text() == null) throw new IllegalStateException("OCR 单帧识别失败");
                results.add(new ContentArtifacts.Frame(i, Math.round(frames.get(i).second() * 1000), keys.get(i), item.text()));
            }
            return JsonUtil.toJson(Map.of("durationMs", durationMs, "artifactKey", artifacts.write(msg.getPipelineId(), "OCR", msg.getAttempt(), new ContentArtifacts.Ocr(results))));
        } finally {
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private String recognizeImages(MediaTaskMessage msg) {
        var media = msg.getInput().media().dynamic();
        var urls = media.items().stream().map(i -> storage.signGetUrl(i.imageObjectKey(), 600)).toList();
        var response = guard.call("OCR", () -> ocr.recognize(new OcrPort.Request(urls)));
        if (response == null || response.results() == null || response.results().size() != media.items().size())
            throw new IllegalStateException("图集 OCR 返回不完整");
        var frames = new ArrayList<ContentArtifacts.Frame>();
        for (int i = 0; i < media.items().size(); i++) {
            var result = response.results().get(i);
            var image = media.items().get(i);
            if (result == null || result.error() != null || result.text() == null || !urls.get(i).equals(result.url()))
                throw new IllegalStateException("图片 OCR 失败: " + i);
            frames.add(new ContentArtifacts.Frame(i, image.startMs(), image.imageObjectKey(), result.text()));
        }
        return JsonUtil.toJson(Map.of("durationMs", media.exportDurationMs(), "artifactKey",
                artifacts.write(msg.getPipelineId(), "OCR", msg.getAttempt(), new ContentArtifacts.Ocr(frames))));
    }
}
