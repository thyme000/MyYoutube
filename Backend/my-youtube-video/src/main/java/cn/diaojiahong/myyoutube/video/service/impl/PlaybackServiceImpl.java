package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.video.domain.dto.RecordUserWatchTimeDTO;
import cn.diaojiahong.myyoutube.video.service.IPlaybackService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 播放上报: Redis 记录观看时长(ZSet 时间戳 + Hash 时长), 供详情页续播
 */
@Service
@RequiredArgsConstructor
public class PlaybackServiceImpl implements IPlaybackService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void updatePlaybackResume(RecordUserWatchTimeDTO userWatchTimeDTO) {
        Long userId = userWatchTimeDTO.getUserId();
        // 记录当前的时间戳
        redisTemplate.opsForZSet().add(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId,
                userWatchTimeDTO.getVideoId().toString(),
                System.currentTimeMillis());
        // 记录用户观看的时长记录
        redisTemplate.opsForHash().put(RedisConstants.USER_PLAYBACK_RESUME + userId,
                userWatchTimeDTO.getVideoId().toString(), userWatchTimeDTO.getPlayTime().toString());

        // 设置过期时间
        redisTemplate.expire(RedisConstants.USER_PLAYBACK_RESUME + userId, RedisConstants.USER_PLAYBACK_RESUME_EXPIRE, TimeUnit.DAYS);
        redisTemplate.expire(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId, RedisConstants.USER_PLAYBACK_RESUME_EXPIRE, TimeUnit.DAYS);
    }

    @Override
    public Long getResumeTime(Long userId, Long videoId) {
        String playTimeStr = (String) redisTemplate.opsForHash().get(RedisConstants.USER_PLAYBACK_RESUME + userId, videoId.toString());
        return (playTimeStr != null && !playTimeStr.isBlank()) ? Long.parseLong(playTimeStr) : null;
    }
}
