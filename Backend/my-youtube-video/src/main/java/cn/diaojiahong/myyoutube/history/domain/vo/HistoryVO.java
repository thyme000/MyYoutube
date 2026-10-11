package cn.diaojiahong.myyoutube.history.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.ContentCardVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class HistoryVO extends ContentCardVO {
    private Boolean isFollow;
    private java.time.LocalDateTime viewTime;
}
