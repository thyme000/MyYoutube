package cn.diaojiahong.myyoutube.video.api.message;

import java.time.LocalDateTime;
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
    private Long videoId;
    private String title;
    private String description;
    private String coverUrl;
    private String tags;
    private String categoryCode;
    private String statusCode;
    private String visibilityCode;
    private Long authorId;
    private Long playCount;
    private Long likeCount;
    private LocalDateTime createTime;
    private List<Long> tagIds;
}


