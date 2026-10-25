package cn.diaojiahong.myyoutube.transcode.api;

import cn.diaojiahong.myyoutube.common.media.AudioAnalysisContracts;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;

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
    @PostMapping("/analysis")
    void analyze(@RequestBody AudioAnalysisContracts.Request request);
    @GetMapping("/{audioId}/analysis/{type}")
    AudioAnalysisContracts.View analysis(@PathVariable("audioId") Long id,
            @PathVariable("type") MediaTaskType type);
    @PostMapping("/{audioId}/analysis/{type}/retry")
    boolean retryAnalysis(@PathVariable("audioId") Long id, @PathVariable("type") MediaTaskType type);
    @PostMapping("/{audioId}/retry")
    boolean retry(@PathVariable("audioId") Long audioId);
}
