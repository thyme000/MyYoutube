package cn.diaojiahong.myyoutube.search.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummaryVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchVideoVO extends VideoSummaryVO {
    /** 内容类型: video/dynamic */
    private String type;
    // 是否点赞了该视频
    private Boolean isLike;
    // 是否关注了该作者
    private Boolean isFollow;
}
