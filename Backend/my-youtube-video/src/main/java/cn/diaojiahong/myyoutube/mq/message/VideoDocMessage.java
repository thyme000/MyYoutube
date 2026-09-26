package cn.diaojiahong.myyoutube.mq.message;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MQ消息体,用来更新视频文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoDocMessage {
    private Video video;
    private List<Long> tagIds;
}


