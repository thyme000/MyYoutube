package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.api.AsrPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AudioAsrTaskExecutor implements MediaTaskExecutor {
    private final AsrPort asr;
    private final ModelCallGuard guard;
    private final S3StorageUtil storage;
    private final ContentArtifactStore artifacts;

    @Override
    public MediaTaskType type() {
        return MediaTaskType.AUDIO_ASR;
    }

    @Override
    public String execute(MediaTaskMessage task) {
        AudioAnalysisContracts.Request input = task.getAudioAnalysisInput();
        input.validate();
        if (input.type() != type()
                || !input.audioId().equals(task.getOwnerId())
                || !input.userId().equals(task.getUserId()))
            throw new IllegalArgumentException("Audio ASR task mismatch");
        String base = input.prefix() + "asr/" + task.getAttempt() + "/" + UUID.randomUUID();
        String key = base + ".json";
        AsrPort.Response response = guard.call(
                "ASR",
                () -> asr.transcribe(new AsrPort.Request(storage.signGetUrl(input.objectKey(), 3600),
                storage.presignPutUrl(base + ".vtt", 3600), storage.presignPutUrl(key, 3600), "", "", "", "")));
        if (response == null
                || response.durationMs() <= 0
                || Math.abs(response.durationMs() - input.durationMs()) > 1000)
            throw new IllegalStateException("ASR returned no result or an inconsistent decoded duration");
        ContentArtifacts.Transcript transcript = artifacts.read(key, ContentArtifacts.Transcript.class);
        AudioTranscript.validate(transcript, input.durationMs());
        if (response.segmentCount() != transcript.segments().size()
                || response.speechEmpty() != transcript.segments().isEmpty())
            throw new IllegalStateException("ASR result does not match artifact");
        return JsonUtil.toJson(new AudioAnalysisContracts.TranscriptResult(
                input.audioId(),
                input.objectKey(),
                key,
                transcript.segments().size()));
    }
}
