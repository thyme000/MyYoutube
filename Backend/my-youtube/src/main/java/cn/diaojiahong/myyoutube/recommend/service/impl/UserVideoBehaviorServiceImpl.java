package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.domain.dto.BehaviorDTO;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.domain.po.VideoTag;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import cn.diaojiahong.myyoutube.recommend.service.IVideoTagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户-视频行为流水表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserVideoBehaviorServiceImpl extends ServiceImpl<UserVideoBehaviorMapper, UserVideoBehavior> implements IUserVideoBehaviorService {

    private final IUserTagPreferenceService userTagPreferenceService;
    private final IVideoTagService videoTagService;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    @Override
    public Result<Void> watchVideo(BehaviorDTO behaviorDTO) {
        Long userId = UserContext.getUser().getId();

        UserVideoBehavior userVideoBehavior = new UserVideoBehavior();
        userVideoBehavior.setUserId(userId);
        userVideoBehavior.setVideoId(behaviorDTO.getVideoId());
        userVideoBehavior.setCreatedAt(behaviorDTO.getWatchTime());
        userVideoBehavior.setDurationMs(behaviorDTO.getWatchDuration());
        userVideoBehavior.setEventType(behaviorDTO.getEvent());
        save(userVideoBehavior);

        // 获得这个视频的所有标签
        List<Long> list = videoTagService.lambdaQuery()
                .eq(VideoTag::getVideoId, behaviorDTO.getVideoId())
                .list().stream().map(VideoTag::getTagId).toList();


        // 更新用户喜好表
        userTagPreferenceService.saveUserTagPreferenceAsync(list, userId, behaviorDTO.getEvent());

        // 更新用户看过的视频id
        if (behaviorDTO.getEvent() == EventEnum.DISLIKE) {
            redisTemplate.opsForSet().add(RedisConstants.USER_DISLIKE + userId, behaviorDTO.getVideoId().toString());
        } else {
            redisTemplate.opsForSet().add(RedisConstants.USER_VIEWED + userId, behaviorDTO.getVideoId().toString());
            redisTemplate.expire(RedisConstants.USER_VIEWED + userId, RedisConstants.USER_VIEWED_EXPIRE, TimeUnit.DAYS);
        }

        return Result.success();
    }
}
