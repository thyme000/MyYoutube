package cn.diaojiahong.myyoutube.mq.tx;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 视频发布事务消息: half 消息先投出, 本地事务(视频入库)成功才 COMMIT,
 * 保证"视频入库"与"封面生成任务"的原子性。
 */
@Slf4j
@RocketMQTransactionListener
@RequiredArgsConstructor
public class VideoPublishTransactionListener implements RocketMQLocalTransactionListener {

    private final IVideoService videoService;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Video video = (Video) arg;
        try {
            videoService.persistNewVideo(video);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("视频入库失败, 回滚事务消息 videoId={}", video.getId(), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 回查: 以入库结果为准
        String videoIdStr = (String) msg.getHeaders().get("videoId");
        Long videoId = videoIdStr != null ? Long.valueOf(videoIdStr) : null;
        return videoId != null && videoService.getById(videoId) != null
                ? RocketMQLocalTransactionState.COMMIT
                : RocketMQLocalTransactionState.ROLLBACK;
    }
}
