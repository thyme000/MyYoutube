package cn.diaojiahong.myyoutube.mq.message;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 *  rabbitMQ消息体,用来更新视频文档
 */
@Data
@Builder
public class VideoDocMessage {
    private Video video;
    private List<Long> tagIds;
}


