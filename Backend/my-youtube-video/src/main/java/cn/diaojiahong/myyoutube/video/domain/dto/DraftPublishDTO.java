package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

/**
 * 草稿发布: 标题/简介由用户填, 其余(视频文件/封面/时长/标签)取管线任务结果
 */
@Data
public class DraftPublishDTO {
    private Long draftId;
    private String title;
    private String description;
}
