package cn.diaojiahong.myyoutube.chat.mongo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementPublishVO {
    private String conversationId;
    private Integer version;
    private String text;
}
