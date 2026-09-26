package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_COMMENT_ACTION,
        consumerGroup = "comment_unlike_group",
        selectorExpression = MqConstants.TAG_UNLIKE,
        maxReconsumeTimes = 3)
public class UnlikeCommentListener implements RocketMQListener<LikeCommentMessage> {

    private final IUserNotificationService userNotificationService;

    /**
     * 删除原来的点赞评论通知
     */
    @Override
    public void onMessage(LikeCommentMessage lc) {
        log.info("handleUnlikeCommentMessage: {}", lc);
        userNotificationService.remove(new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getType, NotificationTypeEnum.COMMENT_LIKE.getCode())
                .eq(UserNotification::getSenderId, lc.getSenderId())
                .eq(UserNotification::getRefId, lc.getCommentId()));
    }
}
