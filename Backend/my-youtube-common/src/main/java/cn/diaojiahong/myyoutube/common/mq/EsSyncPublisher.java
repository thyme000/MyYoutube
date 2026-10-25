package cn.diaojiahong.myyoutube.common.mq;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * ES 文档同步的 MQ 生产门面: TOPIC+TAG 拼接、延迟合并秒数全项目只此一处,
 * 新增生产方不再各自拼字符串与硬编码延迟。
 * 内容文档的消息体组装(video/dynamic 领域对象 → ContentDocMessage)在 video 模块的 ContentDocMessages。
 */
@Component
@RequiredArgsConstructor
public class EsSyncPublisher {

    /** 延迟 5s 投递: 短时间多次变更在消费端天然合并为一次重建 */
    private static final int ES_SYNC_DELAY_SECONDS = 5;

    private final RocketMQTemplate rocketMQTemplate;

    /** 用户文档同步: 消费端以 MySQL 为准重建 */
    public void syncUserDoc(long userId) {
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(userId), ES_SYNC_DELAY_SECONDS);
    }

    public void syncAudioDoc(long id) {
        var result = rocketMQTemplate.syncSendDelayTimeSeconds(MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_AUDIO,
                new AudioDiscoveryContracts.Sync(id), ES_SYNC_DELAY_SECONDS);
        if (result == null || result.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK)
            throw new MqSendUnconfirmedException(result == null ? null : result.getSendStatus());
    }

    /** 内容文档同步(视频/动态), 消息体经 ContentDocMessages 工厂组装 */
    public void syncContentDoc(ContentDocMessage message) {
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_CONTENT,
                message, ES_SYNC_DELAY_SECONDS);
    }
}
