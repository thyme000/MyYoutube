package cn.diaojiahong.myyoutube.like.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LikedContentVO extends ContentCardVO {
    private Boolean isFollow;
}
