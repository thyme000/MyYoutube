package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.chat.api.WsPushPort;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationForJson;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.google.gson.GsonBuilder;
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
        consumerGroup = "comment_publish_group",
        selectorExpression = MqConstants.TAG_PUBLISH,
        maxReconsumeTimes = 3)
public class PublishCommentListener implements RocketMQListener<CommentMessage> {

    private final IUserNotificationService userNotificationService;
    private final UserPort userPort;
    private final IVideoCommentService videoCommentService;
    private final S3StorageUtil storageUtil;
    private final WsPushPort wsPushPort;

    @Override
    public void onMessage(CommentMessage message) {
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

        // 实时推送：视频评论与回复评论均推 WebSocket，前端 Toast 依赖此消息
        UserSummary user = userPort.getUser(message.getSenderId());
        if (user == null) {
            return;
        }
        NotificationForJson json = new NotificationForJson();
        json.setId(userNotification.getId());
        json.setSenderNickname(user.getNickname());
        json.setSenderAvatarUrl(storageUtil.signGetUrl(user.getAvatarUrl(), 300));
        json.setContent(message.getContent());
        if (Boolean.TRUE.equals(message.getIsVideoComment())) {
            json.setType(NotificationTypeEnum.VIDEO_COMMENT.getCode());
            json.setRefId(message.getVideoId());
            json.setVideoId(message.getVideoId());
        } else {
            json.setType(NotificationTypeEnum.COMMENT_REPLY.getCode());
            json.setRefId(message.getCommentId());
            json.setVideoId(message.getVideoId());
        }
        wsPushPort.sendToUser(message.getReceiverId(), new GsonBuilder().create().toJson(json));
    }

}
