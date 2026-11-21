package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.common.feign.InternalTokenFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "my-youtube-user-service", contextId = "userBlockPort",
        path = "/internal/user-block", url = "${my-youtube.user-service.url:}",
        configuration = InternalTokenFeignConfiguration.class)
public interface UserBlockPort {

    @GetMapping("/authors")
    List<Long> blockedAuthors(@RequestParam("viewerId") Long viewerId);

    @GetMapping("/private-chat")
    boolean blocksPrivateChat(@RequestParam("userId") Long userId, @RequestParam("peerId") Long peerId);
}
