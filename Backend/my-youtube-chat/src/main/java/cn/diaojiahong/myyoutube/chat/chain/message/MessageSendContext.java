package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.hutool.crypto.digest.DigestUtil;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import lombok.Getter;

/**
 * 发消息守卫链上下文: 各守卫步骤查到的会话与互关判定沉淀在此,
 * 供写入阶段复用; 幂等命中时 ack 非空即为"成功短路"信号(stop 谓词)。
 */
@Getter
public class MessageSendContext {

    private final ChatMessageDTO dto;
    private final String userId;
    private final String conversationId;

    private ConversationMember member;
    private Conversation conversation;
    /** 私聊首条消息或等待回复的发起人发送时查到的互关状态。 */
    private boolean interrelated;
    private ChatMessageAckDTO ack;

    private MessageSendContext(ChatMessageDTO dto, String userId, String conversationId) {
        this.dto = dto;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public static MessageSendContext of(ChatMessageDTO dto, String userId, String conversationId) {
        return new MessageSendContext(dto, userId, conversationId);
    }

    public boolean hasAck() {
        return ack != null;
    }

    public void setMember(ConversationMember member) {
        this.member = member;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public void setInterrelated(boolean interrelated) {
        this.interrelated = interrelated;
    }

    public void setAck(ChatMessageAckDTO ack) {
        this.ack = ack;
    }

    public static ChatMessageAckDTO ackOf(Message doc, MessageStatusEnum status) {
        ChatMessageAckDTO ack = new ChatMessageAckDTO();
        ack.setMessageId(doc.getId());
        ack.setConversationId(doc.getConversationId());
        ack.setSeq(doc.getSeq());
        ack.setServerTime(System.currentTimeMillis());
        ack.setClientMsgId(doc.getClientMsgId());
        ack.setStatus(status);
        return ack;
    }

    public static String messageId(Long senderId, String clientMsgId) {
        return DigestUtil.sha256Hex(senderId + "\n" + clientMsgId);
    }
}
