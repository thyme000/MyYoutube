package cn.diaojiahong.myyoutube.search.api;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-search-service", contextId = "audioSearchPort", path = "/internal/audio-search", url = "${my-youtube.search-service.url:}")
public interface AudioSearchPort {
    @PostMapping Slice search(@RequestBody Query query);
    @PostMapping("/{id}/rebuild") void rebuild(@PathVariable("id") Long id);
}
