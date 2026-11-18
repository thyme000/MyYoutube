package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportMessage;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 播放上报消费: 直接落库。失败抛回 MQ 重投，数据库靠唯一键和版本规则保留正确结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_PLAYBACK_REPORT,
        consumerGroup = "video_playback_report", maxReconsumeTimes = 3)
public class PlaybackReportListener implements RocketMQListener<PlaybackReportMessage> {

    private final PlaybackReportService reportService;

    @Override
    public void onMessage(PlaybackReportMessage message) {
        if (message == null || message.userId() == null
                || message.reports() == null || message.reports().isEmpty()) {
            throw new IllegalArgumentException("播放上报消息无效");
        }
        try {
            reportService.apply(message.userId(), message.reports());
        } catch (RuntimeException failure) {
            log.error("播放上报处理失败 userId={} size={}", message.userId(), message.reports().size(), failure);
            throw failure;
        }
    }
}
