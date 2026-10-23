package cn.diaojiahong.myyoutube.transcode.api;

import cn.diaojiahong.myyoutube.transcode.api.config.ModelRuntimeFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "asr-svc", contextId = "asrPort", url = "${my-youtube.asr.url:}",
        configuration = ModelRuntimeFeignConfiguration.class)
public interface AsrPort {
    @PostMapping("/asr")
    Response transcribe(@RequestBody Request request);
    record Request(String sourceUrl, String vttUploadUrl, String segmentsUploadUrl,
                   String title, String tags, String description, String language) {}
    record Response(long durationMs, int segmentCount, boolean speechEmpty) {}
}
