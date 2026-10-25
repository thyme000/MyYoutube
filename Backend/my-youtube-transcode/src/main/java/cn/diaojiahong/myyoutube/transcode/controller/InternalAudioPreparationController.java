package cn.diaojiahong.myyoutube.transcode.controller;

import cn.diaojiahong.myyoutube.common.media.AudioAnalysisContracts;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.service.AudioAnalysisTaskService;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.*;
import cn.diaojiahong.myyoutube.transcode.api.AudioPreparationPort;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/media/audios")
@RequiredArgsConstructor
public class InternalAudioPreparationController implements AudioPreparationPort {
    private final MediaTaskService tasks;
    private final AudioAnalysisTaskService analysis;
    @Override public void analyze(AudioAnalysisContracts.Request request) { analysis.start(request); }
    @Override public AudioAnalysisContracts.View analysis(Long id, MediaTaskType type) { return analysis.view(id, type); }
    @Override public boolean retryAnalysis(Long id, MediaTaskType type) { return analysis.retry(id, type); }
    @Override public void prepare(Request request) { tasks.startAudioPreparation(request); }
    @Override public View preparation(Long id) { return tasks.audioPreparationView(id); }
    @Override public boolean retry(Long id) { return tasks.retryAudioPreparation(id); }
}
