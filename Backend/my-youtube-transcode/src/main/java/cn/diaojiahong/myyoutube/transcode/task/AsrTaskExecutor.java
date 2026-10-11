package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.media.*;
import java.util.List;
import java.nio.charset.StandardCharsets;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.AsrPort;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AsrTaskExecutor implements MediaTaskExecutor {
    private final AsrPort asr;
    private final cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard guard;
    private final S3StorageUtil storage;
    private final ContentArtifactStore artifacts;
    @Override public MediaTaskType type() { return MediaTaskType.ASR; }
    @Override public String execute(MediaTaskMessage msg) {
        var input = msg.getInput();
        String base = "content/" + msg.getPipelineId() + "/asr/" + msg.getAttempt() + "/" + UUID.randomUUID();
        String vtt = base + ".vtt", segments = base + ".json";
        var media = input.media();
        if (media.dynamic() != null && media.dynamic().audio() == null) {
            storage.uploadWithKey("WEBVTT\n\n".getBytes(StandardCharsets.UTF_8), vtt);
            String emptySegments = artifacts.write(msg.getPipelineId(), "ASR", msg.getAttempt(), new ContentArtifacts.Transcript(List.of()));
            return JsonUtil.toJson(Map.of("vttKey", vtt, "segmentsKey", emptySegments, "durationMs", 0,
                    "segmentCount", 0, "speechEmpty", true, "noAudio", true));
        }
        String source = media.video() != null ? media.video().objectKey() : media.dynamic().audio().objectKey();
        var result = guard.call("ASR", () -> asr.transcribe(new AsrPort.Request(storage.signGetUrl(source, 3600),
                storage.presignPutUrl(vtt, 3600), storage.presignPutUrl(segments, 3600),
                input.title(), input.tags() == null ? "" : input.tags(), input.description() == null ? "" : input.description(), "")));
        if (result == null) throw new IllegalStateException("ASR 返回为空");
        return JsonUtil.toJson(Map.of("vttKey", vtt, "segmentsKey", segments,
                "durationMs", result.durationMs(), "segmentCount", result.segmentCount(), "speechEmpty", result.speechEmpty()));
    }
}
