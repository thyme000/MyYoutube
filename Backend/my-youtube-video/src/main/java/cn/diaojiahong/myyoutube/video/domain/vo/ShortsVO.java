package cn.diaojiahong.myyoutube.video.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummaryVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ShortsVO extends VideoSummaryVO {
    // 是否关注了该作者
    private Boolean isFollow;
    // 是否点赞了该视频
    private Boolean isLike;
}
