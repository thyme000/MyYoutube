package cn.diaojiahong.myyoutube.chat.port;

import cn.diaojiahong.myyoutube.common.port.WsPushPort;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WsPushPortAdapter implements WsPushPort {

    private final NotifyWebSocketHandler notifyWebSocketHandler;

    @Override
    public void sendToUser(Long userId, String json) {
        notifyWebSocketHandler.sendToUser(userId, json);
    }
}
