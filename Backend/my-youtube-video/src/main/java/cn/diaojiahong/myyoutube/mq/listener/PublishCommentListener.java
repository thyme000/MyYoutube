package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_COMMENT_ACTION,
        consumerGroup = "comment_publish_group",
        selectorExpression = MqConstants.TAG_PUBLISH,
        maxReconsumeTimes = 3)
public class PublishCommentListener implements RocketMQListener<CommentMessage> {

    private final NotificationPublisher notificationPublisher;

    @Override
    public void onMessage(CommentMessage message) {
        log.info("收到发布评论消息: {}", message);
        // 落库 + 门铃由门面收口, 失败降级不影响消息消费
        notificationPublisher.publish(getUserNotification(message));
    }

    private static @NonNull UserNotification getUserNotification(CommentMessage message) {
        UserNotification userNotification = new UserNotification();
        userNotification.setReceiverId(message.getReceiverId());
        userNotification.setSenderId(message.getSenderId());
        userNotification.setType(message.getIsContentComment() ?
                NotificationTypeEnum.CONTENT_COMMENT.getCode() :
                NotificationTypeEnum.COMMENT_REPLY.getCode());
        userNotification.setContent(message.getContent());
        userNotification.setRefType(message.getIsContentComment() ? RefTypeEnum.CONTENT.getCode() : RefTypeEnum.COMMENT.getCode());
        userNotification.setRefId(message.getIsContentComment() ? message.getContentId() : message.getCommentId());
        return userNotification;
    }

}
