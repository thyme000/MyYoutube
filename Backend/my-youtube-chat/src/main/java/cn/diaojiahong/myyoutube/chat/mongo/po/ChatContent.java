package cn.diaojiahong.myyoutube.chat.mongo.po;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Transient;

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

    private Long contentId;
    private String mediaType;
    private String title;
    @JsonIgnore
    private String coverObjectKey;
    @Transient
    private String coverUrl;
}
