package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverResultMessage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_VIDEO_COVER_RESULT,
        consumerGroup = "video_cover_result_group",
        maxReconsumeTimes = 3)
public class VideoCoverResultListener implements RocketMQListener<VideoCoverResultMessage> {

    private final S3StorageUtil storageUtil;
    private final IVideoService videoService;

    /** 消费 transcode 回传的封面结果, 回写 cover_url */
    @Override
    public void onMessage(VideoCoverResultMessage msg) {
        if (msg == null || msg.getVideoId() == null || !StringUtils.hasText(msg.getCoverUrl())) {
            log.warn("VideoCoverResultListener 收到无效消息: {}", msg);
            return;
        }
        videoService.lambdaUpdate()
                .set(Video::getCoverUrl, storageUtil.normalizeToObjectKey(msg.getCoverUrl()))
                .eq(Video::getId, msg.getVideoId())
                .update();
    }
}
