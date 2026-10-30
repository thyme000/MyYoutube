package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatMessageDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import lombok.Getter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 发消息守卫链上下文: 各守卫步骤查到的会话文档与互关判定沉淀在此,
 * 供写入阶段复用; 幂等命中时 ack 非空即为"成功短路"信号(stop 谓词)。
 */
@Getter
public class MessageSendContext {

    private final ChatMessageDTO dto;
    private final String userId;
    private final String conversationId;

    private ChatUserConversationDoc userConversationDoc;
    private ChatConversationDoc conversationDoc;
    /** 私聊且非互关时, 写入阶段要给每日配额计数; 非私聊恒为 false */
    private boolean interrelated;
    private ChatMessageAckDTO ack;

    private MessageSendContext(ChatMessageDTO dto, String userId) {
        this.dto = dto;
        this.userId = userId;
        this.conversationId = dto.getConversationId();
    }

    public static MessageSendContext of(ChatMessageDTO dto, String userId) {
        return new MessageSendContext(dto, userId);
    }

    public boolean hasAck() {
        return ack != null;
    }

    public void setUserConversationDoc(ChatUserConversationDoc userConversationDoc) {
        this.userConversationDoc = userConversationDoc;
    }

    public void setConversationDoc(ChatConversationDoc conversationDoc) {
        this.conversationDoc = conversationDoc;
    }

    public void setInterrelated(boolean interrelated) {
        this.interrelated = interrelated;
    }

    public void setAck(ChatMessageAckDTO ack) {
        this.ack = ack;
    }

    public static ChatMessageAckDTO ackOf(ChatMessageDoc doc, MessageStatusEnum status) {
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
        try {
            byte[] key = (senderId + "\n" + clientMsgId).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
