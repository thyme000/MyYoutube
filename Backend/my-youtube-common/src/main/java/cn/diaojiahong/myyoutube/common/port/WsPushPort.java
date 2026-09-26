package cn.diaojiahong.myyoutube.common.port;

/**
 * WebSocket 推送端口: 业务模块通过该接口推送消息, 由 chat 模块提供实现
 */
public interface WsPushPort {

    void sendToUser(Long userId, String json);
}
