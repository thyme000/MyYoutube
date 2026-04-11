package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import lombok.Data;

import java.util.List;

@Data
public class ConversationVO {
    // 会话id
    private String id;
    // 会话类型
    private ConversationTypeEnum type;
    // 成员用户id列表
    private List<Long> memberIds;
    // 会话名称
    private String name;
}
