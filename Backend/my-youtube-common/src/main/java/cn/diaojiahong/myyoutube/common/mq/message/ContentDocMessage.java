package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ消息体,用来更新统一内容池的 ES 文档。
 * 生产方在 video 服务, 消费方在 search 服务, 消息体放 common 单点。
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
    private String summary;
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
