package cn.diaojiahong.myyoutube.transcode.api;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.*;
import cn.diaojiahong.myyoutube.transcode.api.config.AudioPreparationClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-transcode-service", contextId = "audioPreparationPort",
        configuration = AudioPreparationClientConfiguration.class,
        path = "/internal/media/audios", url = "${my-youtube.transcode-service.url:}")
public interface AudioPreparationPort {
    @PostMapping
    void prepare(@RequestBody Request request);
    @GetMapping("/{audioId}")
    View preparation(@PathVariable("audioId") Long audioId);
    @PostMapping("/{audioId}/retry")
    boolean retry(@PathVariable("audioId") Long audioId);
}
