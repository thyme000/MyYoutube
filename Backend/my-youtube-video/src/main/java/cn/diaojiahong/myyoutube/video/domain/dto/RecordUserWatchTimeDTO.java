package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

/**
 * 记录用户观看长视频时长的DTO
 */
@Data
public class RecordUserWatchTimeDTO {
    // 视频id
    public Long videoId;
    // 视频播放时长(毫秒)
    public Long playTime;
    // 用户id
    public Long userId;
}
