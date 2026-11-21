package cn.diaojiahong.myyoutube.search.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchVideoVO extends ContentCardVO {
    /** 内容类型: video/dynamic */
    private String type;
    private Boolean isLike;
    private Boolean isFollow;
}
