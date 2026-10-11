package cn.diaojiahong.myyoutube.transcode.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "asr-svc", contextId = "asrPort", url = "${my-youtube.asr.url:}")
public interface AsrPort {
    @PostMapping("/asr")
    Response transcribe(@RequestBody Request request);
    record Request(String sourceUrl, String vttUploadUrl, String segmentsUploadUrl,
                   String title, String tags, String description, String language) {}
    record Response(long durationMs, int segmentCount, boolean speechEmpty) {}
}
