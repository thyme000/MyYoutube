package cn.diaojiahong.myyoutube.content.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContentFeedVO extends ContentCardVO {
    private Boolean isLike;
    private Boolean isFollow;
    private String restrictionReason;
}
