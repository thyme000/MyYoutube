package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
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

    private final IUserNotificationService userNotificationService;
    private final PushFanoutPublisher pushFanoutPublisher;

    @Override
    public void onMessage(CommentMessage message) {
        log.info("收到发布评论消息: {}", message);
        // 保存到数据库
        UserNotification userNotification = getUserNotification(message);
        userNotificationService.save(userNotification);

        // 通知已落库, 实时侧只发门铃信号, 前端拉取后弹 toast
        pushFanoutPublisher.pushSseSignal(message.getReceiverId(), "NOTIFY_DIRTY");
    }

    private static @NonNull UserNotification getUserNotification(CommentMessage message) {
        UserNotification userNotification = new UserNotification();
        userNotification.setReceiverId(message.getReceiverId());
        userNotification.setSenderId(message.getSenderId());
        userNotification.setType(message.getIsVideoComment() ?
                NotificationTypeEnum.VIDEO_COMMENT.getCode() :
                NotificationTypeEnum.COMMENT_REPLY.getCode());
        userNotification.setContent(message.getContent());
        userNotification.setRefType(RefTypeEnum.COMMENT.getCode());
        userNotification.setRefId(message.getIsVideoComment() ? message.getVideoId() : message.getCommentId());
        return userNotification;
    }

}
