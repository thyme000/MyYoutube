package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MQ消息体, transcode 封面生成结果回传, video 消费后回写 cover_url
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoCoverResultMessage {
    private Long videoId;
    /** 最终封面地址(已上传的 objectKey/URL, 失败时为默认封面 URL) */
    private String coverUrl;
}
