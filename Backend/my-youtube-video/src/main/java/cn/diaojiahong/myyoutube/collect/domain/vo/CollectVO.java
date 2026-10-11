package cn.diaojiahong.myyoutube.collect.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CollectVO extends ContentCardVO {
    private Boolean isFollow;
    private java.time.LocalDateTime collectTime;
}
