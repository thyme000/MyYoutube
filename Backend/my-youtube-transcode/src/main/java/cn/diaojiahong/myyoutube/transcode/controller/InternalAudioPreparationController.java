package cn.diaojiahong.myyoutube.transcode.controller;

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
    @Override public void prepare(Request request) { tasks.startAudioPreparation(request); }
    @Override public View preparation(Long id) { return tasks.audioPreparationView(id); }
    @Override public boolean retry(Long id) { return tasks.retryAudioPreparation(id); }
}
