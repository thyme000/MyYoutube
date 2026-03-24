package cn.diaojiahong.myyoutube.mq.message;

import lombok.Builder;
import lombok.Data;

/**
 *  rabbitMQ消息体,用来生成视频封面
 */
@Data
@Builder
public class VideoCoverMessage {
    private Long userId;
    private Long videoId;
    private String videoOssObjectKey;
}
