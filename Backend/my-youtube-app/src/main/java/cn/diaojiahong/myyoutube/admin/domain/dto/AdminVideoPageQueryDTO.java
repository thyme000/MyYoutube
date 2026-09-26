package cn.diaojiahong.myyoutube.admin.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum;
import lombok.Data;

@Data
public class AdminVideoPageQueryDTO extends PageQuery {
    // 视频类型
    private VideoTypeEnum videoType;
    // 视频种类
    private VideoCategoryEnum videoCategory;
    // 视频状态
    private VideoStatusEnum videoStatus;
    // 作者id
    private Long userId;
}
