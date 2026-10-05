package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForReadMessageVO;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_CHAT_READ,
        consumerGroup = "chat_read_group",
        maxReconsumeTimes = 3)
public class ReadMessageListener implements RocketMQListener<ReadMessageMessage> {

    private final PushFanoutPublisher pushFanoutPublisher;

    /**
     * 通知对方,自己最后已读的序列
     */
    @Override
    public void onMessage(ReadMessageMessage readMessageMessage) {
        NotificationForReadMessageVO json = new NotificationForReadMessageVO();
        json.setConversationId(readMessageMessage.getConversationId());
        json.setLastReadSequence(readMessageMessage.getLastReadSeq());
        pushFanoutPublisher.pushWs(Long.parseLong(readMessageMessage.getReceiverId()), JsonUtil.toJson(json));
    }
}
