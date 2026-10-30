package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AudioAnalysisTaskService {
    private final MediaTaskService tasks;

    public void start(AudioAnalysisContracts.Request request) {
        request.validate();
        AudioPreparationContracts.View prepared = tasks.audioPreparationView(request.audioId());
        if (prepared == null || prepared.status() != MediaTaskStatus.SUCCESS || prepared.result() == null
                || !request.userId().equals(prepared.result().userId())
                || !request.objectKey().equals(prepared.result().objectKey())
                || request.durationMs() != prepared.result()
                .durationMs()) throw new IllegalArgumentException("Audio is not prepared");
        if (request.type() == MediaTaskType.AUDIO_REVIEW) {
            AudioAnalysisContracts.View asr = view(request.audioId(), MediaTaskType.AUDIO_ASR);
            if (asr == null
                    || asr.status() != MediaTaskStatus.SUCCESS) throw new IllegalArgumentException("Audio transcript not ready");
            AudioAnalysisContracts.TranscriptResult result = JsonUtil.fromJson(
                    asr.resultJson(),
                    AudioAnalysisContracts.TranscriptResult.class);
            result.validate(request);
            if (!request.transcriptKey()
                    .equals(result.transcriptKey())) throw new IllegalArgumentException("Audio transcript mismatch");
        }
        tasks.registerAudioTask(request.type(), request.audioId(), JsonUtil.toJson(request));
    }

    public AudioAnalysisContracts.View view(Long id, MediaTaskType type) {
        requireAnalysis(type);
        MediaTask row = tasks.audioTask(type, id);
        return row == null ? null : new AudioAnalysisContracts.View(
                id,
                type,
                row.getStatus(),
                row.getAttempt(),
                row.getResultJson(),
                row.getFailureCode());
    }

    public boolean retry(Long id, MediaTaskType type) {
        requireAnalysis(type);
        return tasks.retryAudioTask(type, id);
    }

    private void requireAnalysis(MediaTaskType type) {
        if (type != MediaTaskType.AUDIO_ASR
                && type != MediaTaskType.AUDIO_REVIEW) throw new IllegalArgumentException("Invalid audio analysis type");
    }
}
