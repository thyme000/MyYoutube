package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupDeletedMessage {
    // 会话id
    private String conversationId;
    // 要通知的用户id列表
    private List<Long> userIds;
}
