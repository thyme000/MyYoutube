package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * chat 服务内部接口: WebSocket 推送, 供 video 等服务经 Feign 调用
 */
@RestController
@RequestMapping("/internal/ws")
@RequiredArgsConstructor
public class InternalChatController {

    private final NotifyWebSocketHandler notifyWebSocketHandler;

    @PostMapping("/push")
    public void push(@RequestParam("userId") Long userId, @RequestParam("json") String json) {
        notifyWebSocketHandler.sendToUser(userId, json);
    }
}
