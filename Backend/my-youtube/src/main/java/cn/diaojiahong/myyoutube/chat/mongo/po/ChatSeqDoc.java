package cn.diaojiahong.myyoutube.chat.mongo.po;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document("chat_conversation_seq")
public class ChatSeqDoc {
    @Id
    private String id;      // = conversationId
    private Long seq;
}
