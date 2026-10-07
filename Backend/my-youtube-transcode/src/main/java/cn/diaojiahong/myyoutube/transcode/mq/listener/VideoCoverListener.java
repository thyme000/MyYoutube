package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverResultMessage;
import cn.diaojiahong.myyoutube.transcode.cover.VideoCoverGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 旧发布链路的封面消息消费: 具体生成逻辑已下沉 {@link VideoCoverGenerator}, 与新草稿管线共用
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_VIDEO_COVER,
        consumerGroup = "transcode_video_cover_group",
        maxReconsumeTimes = 3)
public class VideoCoverListener implements RocketMQListener<VideoCoverMessage> {

    private final RocketMQTemplate rocketMQTemplate;
    private final VideoCoverGenerator coverGenerator;

    @Override
    public void onMessage(VideoCoverMessage msg) {
        if (msg == null || msg.getVideoId() == null) {
            log.warn("VideoCoverListener 收到无效消息: {}", msg);
            return;
        }
        String coverKey = coverGenerator.generate(msg.getVideoId(), msg.getUserId(), msg.getVideoObjectKey());
        rocketMQTemplate.syncSend(MqConstants.TOPIC_VIDEO_COVER_RESULT, MessageBuilder
                .withPayload(VideoCoverResultMessage.builder()
                        .videoId(msg.getVideoId())
                        .coverUrl(coverKey)
                        .build())
                .build());
    }
}
