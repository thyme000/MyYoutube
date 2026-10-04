package cn.diaojiahong.myyoutube.like.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummaryVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LikeVideoVO extends VideoSummaryVO {
    /** 内容类型: video/dynamic(统一内容池) */
    private String type;

    /** 动态成片总时长(毫秒), 视频无 */
    private Integer totalDurationMs;

    // 是否关注作者
    private Boolean isFollow;
}
