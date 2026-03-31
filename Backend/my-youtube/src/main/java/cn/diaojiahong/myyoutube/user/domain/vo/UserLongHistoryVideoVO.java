package cn.diaojiahong.myyoutube.user.domain.vo;

import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import lombok.Data;

@Data
public class UserLongHistoryVideoVO extends ShortsVO {
    // 用户长视频的播放时长记录
    private Long playbackTime;
}
