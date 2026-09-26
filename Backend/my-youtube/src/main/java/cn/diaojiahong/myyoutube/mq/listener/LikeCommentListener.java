package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
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
        consumerGroup = "comment_like_group",
        selectorExpression = MqConstants.TAG_LIKE,
        maxReconsumeTimes = 3)
public class LikeCommentListener implements RocketMQListener<LikeCommentMessage> {

    private final IVideoCommentService videoCommentService;
    private final IUserNotificationService userNotificationService;

    /**
     * 推送点赞评论通知
     */
    @Override
    public void onMessage(LikeCommentMessage lc) {
        log.info("handleLikeCommentMessage: {}", lc);

        VideoComment one = videoCommentService.lambdaQuery()
                .eq(VideoComment::getId, lc.getCommentId()).eq(VideoComment::getStatus, CommentStatusEnum.VISIBLE).one();
        if (one == null) {
            // 评论不存在说明可能被删除了
            return;
        }
        // 获取发布被赞评论的用户id
        Long likedUserId = one.getUserId();

        // 保存点赞评论通知
        UserNotification userNotification = new UserNotification();
        userNotification.setSenderId(lc.getSenderId());
        userNotification.setType(NotificationTypeEnum.COMMENT_LIKE.getCode());
        userNotification.setReceiverId(likedUserId);
        userNotification.setRefId(lc.getCommentId());
        userNotificationService.save(userNotification);
    }
}
