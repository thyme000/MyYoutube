package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupDeletedMessage {
    public static final String TOPIC = "chat_group_deleted_topic";

    private String conversationId;
    private List<Long> userIds;
}
