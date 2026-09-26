package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForReadMessageVO;
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
        topic = MqConstants.TOPIC_CHAT_READ,
        consumerGroup = "chat_read_group",
        maxReconsumeTimes = 3)
public class ReadMessageListener implements RocketMQListener<ReadMessageMessage> {

    private final NotifyWebSocketHandler notifyWebSocketHandler;

    /**
     * 通知对方,自己最后已读的序列
     */
    @Override
    public void onMessage(ReadMessageMessage readMessageMessage) {
        NotificationForReadMessageVO json = new NotificationForReadMessageVO();
        json.setConversationId(readMessageMessage.getConversationId());
        json.setLastReadSequence(readMessageMessage.getLastReadSeq());
        notifyWebSocketHandler.sendToUser(Long.parseLong(readMessageMessage.getReceiverId()), new GsonBuilder().create().toJson(json));
    }
}
