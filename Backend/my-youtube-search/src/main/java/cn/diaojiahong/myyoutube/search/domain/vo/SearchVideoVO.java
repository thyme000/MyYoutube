package cn.diaojiahong.myyoutube.search.domain.vo;

import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import lombok.Data;

@Data
public class SearchVideoVO extends ShortsVO {
    // 视频类型
    private String videoType;
}
