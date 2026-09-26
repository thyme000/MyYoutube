package cn.diaojiahong.myyoutube.user.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserLongHistoryVideoVO extends UserShortsVideoVO {
    // 用户长视频的播放时长记录
    private Long playbackTime;
}
