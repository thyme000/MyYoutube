package cn.diaojiahong.myyoutube.chat.mongo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConversationMembersVO {
    // 用户id
    private Long userId;
    // 用户名
    private String userName;
    // 用户头像
    private String avatar;
    // 是否是群主
    private Boolean isGroupOwner;
}
