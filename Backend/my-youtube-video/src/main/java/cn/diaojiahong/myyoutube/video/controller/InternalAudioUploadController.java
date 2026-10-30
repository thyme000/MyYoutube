package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.video.api.AudioUploadPort;
import cn.diaojiahong.myyoutube.video.service.AudioUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/audio-upload")
@RequiredArgsConstructor
public class InternalAudioUploadController implements AudioUploadPort {
    private final AudioUploadService uploads;

    @Override
    public void uploaded(String objectKey) {
        uploads.uploaded(objectKey);
    }
}
