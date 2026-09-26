package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *  MQ消息体,用来生成视频封面
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoCoverMessage {
    private Long userId;
    private Long videoId;
    private String videoObjectKey;
}
