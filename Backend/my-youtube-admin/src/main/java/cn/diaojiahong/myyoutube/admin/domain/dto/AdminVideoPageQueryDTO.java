package cn.diaojiahong.myyoutube.admin.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import lombok.Data;

@Data
public class AdminVideoPageQueryDTO extends PageQuery {
    // 视频种类
    private VideoCategoryEnum videoCategory;
    // 视频状态
    private VideoStatusEnum videoStatus;
    // 作者id
    private Long userId;
}
