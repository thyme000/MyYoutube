package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FindRecordVO {
    // 序列号
    private Long seq;
    // 消息类型
    private ContentTypeEnum contentType;
    // 消息内容
    private ChatContent content;
    // 发送人昵称
    private String senderNickname;
    // 发送人头像url
    private String senderAvatarUrl;
    // 消息创建时间
    private LocalDateTime createdAt;
}
