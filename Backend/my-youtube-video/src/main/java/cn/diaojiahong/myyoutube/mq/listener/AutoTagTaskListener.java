package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.AutoTagTaskMessage;
import cn.diaojiahong.myyoutube.recommend.auto.AutoTagService;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_AUTO_TAG,
        consumerGroup = "auto_tag_group",
        maxReconsumeTimes = 3)
public class AutoTagTaskListener implements RocketMQListener<AutoTagTaskMessage> {

    private final AutoTagService autoTagService;

    @Override
    public void onMessage(AutoTagTaskMessage message) {
        if (message == null || message.getContentId() == null) {
            log.warn("打标任务消息无效: {}", message);
            return;
        }
        ContentTypeEnum type = ContentTypeEnum.fromCode(message.getContentType());
        if (type == null) {
            log.warn("打标任务类型无效, contentId={}, type={}", message.getContentId(), message.getContentType());
            return;
        }
        // 发布在本地事务 commit 前发出的消息, 极端回查/乱序场景查不到就静默吞掉; 夜巡兜底
        autoTagService.tagContent(message.getContentId(), type);
    }
}
