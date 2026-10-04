package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.dto.RecordUserWatchTimeDTO;

/**
 * 播放上报: 观看时长记录(续播)写入与读取
 */
public interface IPlaybackService {

    void updatePlaybackResume(RecordUserWatchTimeDTO userWatchTimeDTO);

    Long getResumeTime(Long userId, Long videoId);
}
