package cn.diaojiahong.myyoutube.recommend.domain.dto;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户观看某视频的行为
 */
@Data
public class BehaviorDTO {
    // 视频id
    private Long videoId;
    // 事件
    private EventEnum event;
    // 观看时间
    private LocalDateTime watchTime;
    // 观看时长（单位：毫秒）
    private Long watchDuration;
}
