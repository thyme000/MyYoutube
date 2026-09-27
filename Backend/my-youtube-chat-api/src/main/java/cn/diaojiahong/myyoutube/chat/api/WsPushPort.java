package cn.diaojiahong.myyoutube.chat.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * chat 服务对外契约: WebSocket 消息推送。
 * 拆部署前由 chat 模块的 WsPushPortAdapter 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.chat-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-chat-service", contextId = "wsPushPort",
        url = "${my-youtube.chat-service.url:}")
public interface WsPushPort {

    @PostMapping("/internal/ws/push")
    void sendToUser(@RequestParam("userId") Long userId, @RequestParam("json") String json);
}
