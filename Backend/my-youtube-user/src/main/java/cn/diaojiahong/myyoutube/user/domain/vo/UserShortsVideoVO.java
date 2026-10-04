package cn.diaojiahong.myyoutube.user.domain.vo;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummaryVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户历史的短视频/长视频通用展示 VO, 公共字段见 video-api 的 VideoSummaryVO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserShortsVideoVO extends VideoSummaryVO {
    private Boolean isFollow;
    private Boolean isLike;
}
