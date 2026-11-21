package cn.diaojiahong.myyoutube.chat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConversationMembersVO {
    private Long userId;
    private String userName;
    private String avatar;
    private Boolean isGroupOwner;
}
