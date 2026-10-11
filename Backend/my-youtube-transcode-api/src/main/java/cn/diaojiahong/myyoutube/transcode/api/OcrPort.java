package cn.diaojiahong.myyoutube.transcode.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

@FeignClient(name = "ocr-svc", contextId = "ocrPort", url = "${my-youtube.ocr.url:}")
public interface OcrPort {
    @PostMapping("/ocr/batch")
    Response recognize(@RequestBody Request request);
    record Request(List<String> urls) {}
    record Item(String url, String text, String error) {}
    record Response(List<Item> results) {}
}
