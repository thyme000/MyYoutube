package cn.diaojiahong.myyoutube.search.domain.vo;

import cn.diaojiahong.myyoutube.content.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchContentVO extends ContentCardVO {
    /** 内容类型: video/dynamic */
    private String type;
    private Boolean isLike;
    private Boolean isFollow;
}
