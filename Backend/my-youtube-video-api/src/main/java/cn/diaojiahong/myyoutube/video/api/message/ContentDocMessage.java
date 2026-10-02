package cn.diaojiahong.myyoutube.video.api.message;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MQ消息体,用来更新统一内容池的 ES 文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDocMessage {
    /** 与 ContentTypeEnum.code 一致: video/dynamic */
    private String type;
    private Long contentId;
    private String title;
    private String description;
    /** 封面 objectKey: 视频用封面, 动态用首帧 */
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
