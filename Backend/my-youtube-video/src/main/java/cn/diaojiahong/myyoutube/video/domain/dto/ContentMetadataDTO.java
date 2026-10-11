package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

/**
 * 草稿公共信息。
 */
@Data
public class ContentMetadataDTO {
    private String title;
    private String description;
    private Integer allowComment;
}
