package cn.diaojiahong.myyoutube.chat.mongo.po;

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

    // 系统消息可选
    private String systemText;

    // 转发视频所用字段
    private Long forwardVideoId;
    private String forwardVideoTitle;
    private String forwardVideoCoverObjectKey;

    private Long forwardDynamicId;
    private String forwardDynamicTitle;
    private String forwardDynamicCoverObjectKey;
}
