package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationForJson;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import io.swagger.v3.core.util.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeCommentListener {

    private final IVideoCommentService videoCommentService;
    private final IUserNotificationService userNotificationService;

    /**
     * 推送点赞评论通知
     * @param lc
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = LIKE_COMMENT_QUEUE, durable = "true"),
            exchange = @Exchange(name = LIKE_COMMENT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = LIKE_COMMENT_ROUTING_KEY
    ))
    public void handleLikeComment(LikeCommentMessage lc) {
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
