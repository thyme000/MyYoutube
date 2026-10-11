package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContentCardVO extends ContentInfoVO {
    private String videoUrl;
    private Long durationMs;
    private Long exportDurationMs;
}
