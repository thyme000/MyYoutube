package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import lombok.Data;

import java.util.List;

@Data
public class ConversationVO {
    private String id;
    private ConversationTypeEnum type;
    private List<Long> memberIds;
    private String name;
}
