package cn.diaojiahong.myyoutube.chat.domain.po;

import lombok.Data;

/**
 * 统一消息内容
 */
@Data
public class ChatContent {

    // 文本消息用
    private String text;

    // 媒体消息通用字段
    private String objectKey;
    private String fileName;
    private String mimeType;
    private Long size;

    // 图片/视频可选
    private Integer width;
    private Integer height;

    // 音视频可选（秒）
    private Double duration;

    private Long contentId;
    private String mediaType;
    private String title;

    // 转发封面 key 必须随 content 一起序列化落库; coverUrl 只在读取时签名赋值, 写库时恒为 null
    private String coverObjectKey;

    private String coverUrl;
}
