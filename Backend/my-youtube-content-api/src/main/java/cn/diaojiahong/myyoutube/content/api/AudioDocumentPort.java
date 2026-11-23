package cn.diaojiahong.myyoutube.content.api;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "my-youtube-content-service",
        contextId = "audioDocumentPort",
        path = "/internal/audio-document",
        url = "${my-youtube.content-service.url:}")
public interface AudioDocumentPort {
    @GetMapping("/{id}")
    Document document(@PathVariable("id") Long id);

    @PostMapping("/ack")
    void acknowledge(@RequestBody Ack ack);
}
