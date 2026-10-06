package cn.diaojiahong.myyoutube.mq.publisher;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.AutoTagTaskMessage;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 机器打标任务发布器: 延迟 5s 投递, 等发布事务提交 + 短窗口合并
 */
@Component
@RequiredArgsConstructor
public class AutoTagPublisher {

    private static final int AUTO_TAG_DELAY_SECONDS = 5;

    private final RocketMQTemplate rocketMQTemplate;

    public void sendAutoTagTask(Long contentId, ContentTypeEnum type) {
        rocketMQTemplate.syncSendDelayTimeSeconds(MqConstants.TOPIC_AUTO_TAG,
                new AutoTagTaskMessage(contentId, type.getCode()), AUTO_TAG_DELAY_SECONDS);
    }
}
