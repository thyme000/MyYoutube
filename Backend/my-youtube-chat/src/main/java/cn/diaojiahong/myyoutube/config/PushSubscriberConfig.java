package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.handler.LocalPushDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;

/**
 * 订阅推送扇出频道: 每个实例都会收到全部广播, 只推给本地连接。
 */
@Configuration
public class PushSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer pushRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, LocalPushDispatcher dispatcher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(dispatcher, new ChannelTopic(PushFanoutPublisher.CHANNEL_WS));
        container.addMessageListener(dispatcher, new ChannelTopic(PushFanoutPublisher.CHANNEL_SSE));
        return container;
    }
}
