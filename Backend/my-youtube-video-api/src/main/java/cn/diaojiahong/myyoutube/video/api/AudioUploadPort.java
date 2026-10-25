package cn.diaojiahong.myyoutube.video.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "my-youtube-video-service", contextId = "audioUploadPort", path = "/internal/audio-upload", url = "${my-youtube.video-service.url:}")
public interface AudioUploadPort {
    @PostMapping("/uploaded") void uploaded(@RequestParam("objectKey") String objectKey);
}
