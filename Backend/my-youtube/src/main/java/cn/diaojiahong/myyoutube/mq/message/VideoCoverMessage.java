package cn.diaojiahong.myyoutube.mq.message;

import lombok.Data;

/**
 *  rabbitMQ消息体,用来生成视频封面
 */
@Data
public class VideoCoverMessage {
    private Long userId;
    private Long videoId;
    private String videoOssUrl;
}
