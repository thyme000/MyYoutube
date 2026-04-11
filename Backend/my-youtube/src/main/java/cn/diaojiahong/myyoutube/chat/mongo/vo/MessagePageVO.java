package cn.diaojiahong.myyoutube.chat.mongo.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class MessagePageVO {
    // 数据
    private List<MessagePage> messages;
    // 下次查询的seq值
    private Long nextSeq;
    // 是否还有更多数据
    private Boolean hasMore;
}
