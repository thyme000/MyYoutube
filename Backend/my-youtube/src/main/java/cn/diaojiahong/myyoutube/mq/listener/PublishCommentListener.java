package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationForJson;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.google.gson.GsonBuilder;
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
public class PublishCommentListener {
    private final IUserNotificationService userNotificationService;
    private final IUserService userService;
    private final AliOssUtil aliOssUtil;
    private final NotifyWebSocketHandler notifyWebSocketHandler;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = PUBLISH_COMMENT_QUEUE, durable = "true"),
            exchange = @Exchange(name = PUBLISH_COMMENT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = PUBLISH_COMMENT_ROUTING_KEY
    ))
    public void handlePublishComment(CommentMessage message) {
        log.info("收到发布评论消息: {}", message);
        // 保存到数据库
        UserNotification userNotification = new UserNotification();
        userNotification.setReceiverId(message.getReceiverId());
        userNotification.setSenderId(message.getSenderId());
        userNotification.setType(message.getIsVideoComment() ?
                NotificationTypeEnum.VIDEO_COMMENT.getCode() :
                NotificationTypeEnum.COMMENT_REPLY.getCode());
        userNotification.setContent(message.getContent());
        userNotification.setRefType(RefTypeEnum.COMMENT.getCode());
        userNotification.setRefId(message.getIsVideoComment() ? message.getVideoId() : message.getCommentId());

        userNotificationService.save(userNotification);

        // 判断是不是视频评论,视频评论不发送消息,回复评论才发送
        if (!message.getIsVideoComment()) {
            User user = userService.getById(message.getSenderId());
            NotificationForJson json = new NotificationForJson();
            json.setSenderNickname(user.getNickname());
            json.setSenderAvatarUrl(aliOssUtil.signGetUrl(user.getAvatarUrl(), 300));
            json.setType(NotificationTypeEnum.COMMENT_REPLY.getCode());
            json.setRefId(message.getCommentId());
            json.setVideoId(message.getVideoId());
            notifyWebSocketHandler.sendToUser(message.getReceiverId(), new GsonBuilder().create().toJson(json));
        }
    }
}
