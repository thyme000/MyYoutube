package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;

/**
 * 会话通知策略: 按会话形态(群聊/私聊)分派"通知哪些人、用什么头像",
 * 事件维度(SEND/WITHDRAW)的报文构建与未读累加是共享语义, 收在基类。
 */
public interface ConversationNotifyStrategy {

    ConversationTypeEnum type();

    void notify(ChatConversationDoc conversation, ChatMessageMessage message, UserSummary sender);
}
