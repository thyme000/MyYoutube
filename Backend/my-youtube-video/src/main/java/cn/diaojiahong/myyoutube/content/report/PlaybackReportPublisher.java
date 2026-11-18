package cn.diaojiahong.myyoutube.content.report;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.MqSendUnconfirmedException;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 播放上报的 MQ 生产门面。消息体携带已盖章的快照，确认投递后才回执成功。
 */
@Component
@RequiredArgsConstructor
public class PlaybackReportPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    /** 投递本次上报。SEND_OK 之外一律抛异常，失败由前端从蓄水池重传。 */
    public void send(PlaybackReportMessage message) {
        SendResult result = rocketMQTemplate.syncSend(MqConstants.TOPIC_PLAYBACK_REPORT, message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new MqSendUnconfirmedException(result == null ? null : result.getSendStatus());
        }
    }
}
